package dev.danny.sundial.ui.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.danny.sundial.AppContainer
import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.RecurrenceScope
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.data.EventDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EventEditViewModel(
    private val container: AppContainer,
    private val calendarId: String?,
    private val eventId: String?,
    private val startMillis: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val draft: EventDraft? = null,
        val calendars: List<CalendarInfo> = emptyList(),
        val original: EventItem? = null,
        val error: String? = null,
        val saved: Boolean = false,
    ) {
        val isNew: Boolean get() = original == null
        val isRecurringInstance: Boolean get() = original?.recurringEventId != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val calendars = container.repository.writableCalendars()
            val existing = if (calendarId != null && eventId != null) {
                container.repository.event(calendarId, eventId)
            } else {
                null
            }

            val draft = when {
                existing != null -> EventDraft.from(existing)
                else -> {
                    val target = calendarId?.takeIf { id -> calendars.any { it.id == id } }
                        ?: calendars.firstOrNull { it.isPrimary }?.id
                        ?: calendars.firstOrNull()?.id
                    if (target == null) {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = "No calendar you can write to was found. Sync first.",
                            )
                        }
                        return@launch
                    }
                    EventDraft.blank(
                        calendarId = target,
                        at = TimeUtil.toLocalDateTime(startMillis),
                        defaultReminderMinutes = container.prefs.defaultReminderMinutes,
                    )
                }
            }

            _state.update {
                it.copy(loading = false, draft = draft, calendars = calendars, original = existing)
            }
        }
    }

    fun update(transform: (EventDraft) -> EventDraft) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = transform(draft), error = null)
        }
    }

    fun save(scope: RecurrenceScope) {
        val current = _state.value
        val draft = current.draft ?: return
        if (current.saving) return

        draft.validationError()?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = if (current.isNew) {
                container.repository.createEvent(draft).map { }
            } else {
                container.repository.updateEvent(draft, scope, current.original).map { }
            }

            result
                .onSuccess {
                    container.reminderScheduler.rescheduleAll()
                    _state.update { it.copy(saving = false, saved = true) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(saving = false, error = error.message ?: "Could not save the event.")
                    }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        fun factory(
            container: AppContainer,
            calendarId: String?,
            eventId: String?,
            startMillis: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EventEditViewModel(container, calendarId, eventId, startMillis) as T
        }
    }
}
