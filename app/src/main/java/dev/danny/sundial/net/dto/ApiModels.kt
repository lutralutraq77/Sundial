package dev.danny.sundial.net.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CalendarListResponse(
    val items: List<CalendarListEntry> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class CalendarListEntry(
    val id: String = "",
    val summary: String? = null,
    val summaryOverride: String? = null,
    val description: String? = null,
    val timeZone: String? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val colorId: String? = null,
    val selected: Boolean = false,
    val accessRole: String = "reader",
    val primary: Boolean = false,
    val deleted: Boolean = false,
    val defaultReminders: List<ApiReminder> = emptyList(),
) {
    val displayName: String
        get() = summaryOverride?.takeIf { it.isNotBlank() }
            ?: summary?.takeIf { it.isNotBlank() }
            ?: id
}

@Serializable
data class ApiReminder(
    val method: String = "popup",
    val minutes: Int = 10,
)

@Serializable
data class EventsResponse(
    val items: List<ApiEvent> = emptyList(),
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
    val timeZone: String? = null,
    val defaultReminders: List<ApiReminder> = emptyList(),
)

@Serializable
data class ApiEvent(
    val id: String? = null,
    val status: String? = null,
    val htmlLink: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val colorId: String? = null,
    val start: ApiEventDateTime? = null,
    val end: ApiEventDateTime? = null,
    val endTimeUnspecified: Boolean = false,
    val recurrence: List<String>? = null,
    val recurringEventId: String? = null,
    val originalStartTime: ApiEventDateTime? = null,
    val transparency: String? = null,
    val visibility: String? = null,
    val iCalUID: String? = null,
    val sequence: Int? = null,
    val attendees: List<ApiAttendee>? = null,
    val organizer: ApiPerson? = null,
    val creator: ApiPerson? = null,
    val reminders: ApiReminders? = null,
    val hangoutLink: String? = null,
    val updated: String? = null,
    val created: String? = null,
    val etag: String? = null,
    val guestsCanModify: Boolean = false,
)

@Serializable
data class ApiEventDateTime(
    val date: String? = null,
    val dateTime: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class ApiAttendee(
    val email: String? = null,
    val displayName: String? = null,
    val responseStatus: String? = null,
    val self: Boolean = false,
    val organizer: Boolean = false,
    val optional: Boolean = false,
    val resource: Boolean = false,
    val comment: String? = null,
)

@Serializable
data class ApiPerson(
    val email: String? = null,
    val displayName: String? = null,
    val self: Boolean = false,
)

@Serializable
data class ApiReminders(
    val useDefault: Boolean = true,
    val overrides: List<ApiReminder>? = null,
)

@Serializable
data class ColorsResponse(
    val calendar: Map<String, ColorPair> = emptyMap(),
    val event: Map<String, ColorPair> = emptyMap(),
)

@Serializable
data class ColorPair(
    val background: String = "#4285F4",
    val foreground: String = "#FFFFFF",
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(
    val code: Int = 0,
    val message: String? = null,
    val status: String? = null,
    val errors: List<ApiErrorDetail> = emptyList(),
)

@Serializable
data class ApiErrorDetail(
    val domain: String? = null,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
data class FreeBusyResponse(
    val calendars: Map<String, FreeBusyCalendar> = emptyMap(),
)

@Serializable
data class FreeBusyCalendar(
    val busy: List<FreeBusySlot> = emptyList(),
)

@Serializable
data class FreeBusySlot(
    val start: String? = null,
    val end: String? = null,
)

@Serializable
data class SettingsResponse(
    val items: List<SettingEntry> = emptyList(),
)

@Serializable
data class SettingEntry(
    val id: String = "",
    @SerialName("value") val value: String = "",
)
