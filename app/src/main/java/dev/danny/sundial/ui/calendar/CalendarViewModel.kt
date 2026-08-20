package dev.danny.sundial.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.danny.sundial.AppContainer
import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.RecurrenceScope
import dev.danny.sundial.core.TimeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

enum class CalendarView(val label: String) {
    SCHEDULE("Schedule"),
    DAY("Day"),
    THREE_DAY("3 days"),
    WEEK("Week"),
    MONTH("Month"),
}

data class CalendarUiState(
    val view: CalendarView = CalendarView.MONTH,
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    val events: List<EventItem> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val syncing: Boolean = false,
    val lastSyncMillis: Long = 0L,
    val lastSyncError: String? = null,
    val message: String? = null,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val accountEmail: String? = null,
) {
    val writableCalendars: List<CalendarInfo> get() = calendars.filter { it.canWrite }
}

class CalendarViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        CalendarUiState(
            view = runCatching { CalendarView.valueOf(container.prefs.defaultView) }
                .getOrDefault(CalendarView.MONTH),
            firstDayOfWeek = container.prefs.firstDayOfWeek,
            lastSyncMillis = container.prefs.lastSyncMillis,
            lastSyncError = container.prefs.lastSyncError,
            accountEmail = container.auth.state.value.email,
        ),
    )

    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.repository.changes.collect { reload() }
        }
        // Sync on every signed-in transition, not once in init: this ViewModel is
        // Activity-scoped and survives a sign-out, so after a re-login init has long
        // since run and the freshly-wiped cache would otherwise stay empty forever.
        // The initial emission covers the first launch (signedIn is already true when
        // this ViewModel is created); the sign-out emission drops the old account's
        // events from the UI.
        viewModelScope.launch {
            container.auth.state.map { it.signedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) sync() else reload()
            }
        }
    }

    // ---- navigation ----------------------------------------------------

    fun setView(view: CalendarView) {
        container.prefs.defaultView = view.name
        _state.update { it.copy(view = view) }
        reload()
    }

    fun setAnchor(date: LocalDate) {
        if (_state.value.anchor == date) return
        _state.update { it.copy(anchor = date) }
        reload()
    }

    fun selectDate(date: LocalDate) {
        _state.update { it.copy(selected = date, anchor = date) }
        reload()
    }

    fun goToToday() {
        val today = TimeUtil.today()
        _state.update { it.copy(anchor = today, selected = today) }
        reload()
    }

    // ---- data ----------------------------------------------------------

    /** Bumped per reload; a completed query publishes only if no newer reload started. Main-thread only. */
    private var reloadGeneration = 0L

    private fun reload() {
        val generation = ++reloadGeneration
        viewModelScope.launch {
            val current = _state.value
            val (from, to) = rangeFor(current.view, current.anchor, current.firstDayOfWeek)
            val events = container.repository.eventsBetween(from, to)
            val calendars = container.repository.calendars()
            // Queries run on IO and resume in completion order — a slow query for an
            // old range must not overwrite a newer range's published results.
            if (generation != reloadGeneration) return@launch
            _state.update {
                it.copy(
                    events = events,
                    calendars = calendars,
                    firstDayOfWeek = container.prefs.firstDayOfWeek,
                    lastSyncMillis = container.prefs.lastSyncMillis,
                    lastSyncError = container.prefs.lastSyncError,
                    accountEmail = container.auth.state.value.email,
                )
            }
        }
    }

    fun sync() {
        if (_state.value.syncing) return
        if (!container.auth.state.value.signedIn) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val outcome = container.syncEngine.sync()
            // A sign-out can land while the sync was in flight; re-arming alarms from
            // rows the cleanup is deleting would resurrect the old account's reminders.
            if (container.auth.state.value.signedIn) {
                container.reminderScheduler.rescheduleAll()
            }
            _state.update {
                it.copy(
                    syncing = false,
                    lastSyncMillis = container.prefs.lastSyncMillis,
                    lastSyncError = outcome.error,
                    // A successful sync must not null a queued confirmation ("Event
                    // saved"/"Event deleted") — that restarts the snackbar effect
                    // and cancels it mid-display.
                    message = outcome.error ?: it.message,
                )
            }
            reload()
        }
    }

    fun toggleCalendar(calendarId: String, visible: Boolean) {
        viewModelScope.launch { container.repository.setCalendarVisible(calendarId, visible) }
    }

    fun deleteEvent(event: EventItem, scope: RecurrenceScope) {
        viewModelScope.launch {
            val result = container.repository.deleteEvent(event, scope)
            result.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "Could not delete the event.") }
            }
            result.onSuccess {
                _state.update { it.copy(message = "Event deleted") }
                if (scope == RecurrenceScope.ALL) sync()
            }
        }
    }

    fun respond(event: EventItem, response: String) {
        viewModelScope.launch {
            container.repository.respond(event, response)
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "Could not send your response.") }
                }
        }
    }

    fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** A generous window around the anchor so paging one step does not stall on a query. */
    private fun rangeFor(
        view: CalendarView,
        anchor: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): Pair<Long, Long> {
        val start: LocalDate
        val end: LocalDate
        when (view) {
            CalendarView.SCHEDULE -> {
                start = anchor.minusMonths(2)
                end = anchor.plusMonths(12)
            }
            CalendarView.DAY -> {
                start = anchor.minusDays(10)
                end = anchor.plusDays(11)
            }
            CalendarView.THREE_DAY -> {
                start = anchor.minusDays(15)
                end = anchor.plusDays(18)
            }
            CalendarView.WEEK -> {
                start = DateGrid.weekStart(anchor, firstDayOfWeek).minusWeeks(3)
                end = DateGrid.weekStart(anchor, firstDayOfWeek).plusWeeks(4)
            }
            CalendarView.MONTH -> {
                start = DateGrid.monthGridStart(anchor.minusMonths(1), firstDayOfWeek)
                end = DateGrid.monthGridStart(anchor.plusMonths(1), firstDayOfWeek).plusWeeks(6)
            }
        }
        return TimeUtil.startOfDay(start) to TimeUtil.startOfDay(end)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CalendarViewModel(container) as T
            }
    }
}
