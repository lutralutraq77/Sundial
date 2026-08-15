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
        sync()
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

    private fun reload() {
        viewModelScope.launch {
            val current = _state.value
            val (from, to) = rangeFor(current.view, current.anchor, current.firstDayOfWeek)
            val events = container.repository.eventsBetween(from, to)
            val calendars = container.repository.calendars()
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
            container.reminderScheduler.rescheduleAll()
            _state.update {
                it.copy(
                    syncing = false,
                    lastSyncMillis = container.prefs.lastSyncMillis,
                    lastSyncError = outcome.error,
                    message = outcome.error,
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
