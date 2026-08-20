package dev.danny.sundial.core

import java.time.LocalDate

data class Reminder(
    val method: String = "popup",
    val minutes: Int = 10,
)

data class CalendarInfo(
    val id: String,
    val summary: String,
    val description: String?,
    val timeZone: String?,
    val colorHex: String,
    val accessRole: String,
    val isPrimary: Boolean,
    val visible: Boolean,
    val defaultReminders: List<Reminder>,
) {
    val canWrite: Boolean get() = accessRole == "owner" || accessRole == "writer"
}

data class Attendee(
    /** Null for entries Google sends without an address (rooms, contact-only guests). */
    val email: String?,
    val displayName: String?,
    val responseStatus: String?,
    val self: Boolean,
    val organizer: Boolean,
    val optional: Boolean,
)

data class EventReminders(
    val useDefault: Boolean = true,
    val overrides: List<Reminder> = emptyList(),
)

data class EventItem(
    val id: String,
    val calendarId: String,
    val summary: String?,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    /** Inclusive start, epoch millis. For all-day events this is local midnight. */
    val startMillis: Long,
    /** Exclusive end, epoch millis. */
    val endMillis: Long,
    /** yyyy-MM-dd, all-day events only. */
    val startDate: String?,
    /** yyyy-MM-dd, exclusive, all-day events only. */
    val endDate: String?,
    val timeZone: String?,
    val status: String?,
    val htmlLink: String?,
    val colorId: String?,
    val recurringEventId: String?,
    val recurrence: List<String>,
    val organizer: String?,
    val attendees: List<Attendee>,
    val reminders: EventReminders,
    val hangoutLink: String?,
    val updatedMillis: Long,
    /** Colour resolved from the event override or its calendar; "#RRGGBB". */
    val colorHex: String,
    /** False when the event is marked free (transparency = "transparent"). */
    val busy: Boolean = true,
) {
    val isRecurring: Boolean get() = recurringEventId != null || recurrence.isNotEmpty()

    val title: String get() = summary?.takeIf { it.isNotBlank() } ?: "(No title)"

    /** First local day the event touches. */
    fun firstDay(): LocalDate =
        startDate?.let { TimeUtil.parseDate(it) } ?: TimeUtil.toLocalDate(startMillis)

    /** Last local day the event touches, inclusive. */
    fun lastDay(): LocalDate {
        if (allDay) {
            val end = endDate?.let { TimeUtil.parseDate(it) } ?: return firstDay()
            val inclusive = end.minusDays(1)
            return if (inclusive.isBefore(firstDay())) firstDay() else inclusive
        }
        // A timed event that ends exactly at midnight belongs to the previous day.
        val endDay = TimeUtil.toLocalDate(endMillis)
        val startsSameInstant = endMillis <= startMillis
        if (startsSameInstant) return firstDay()
        val endsAtMidnight = TimeUtil.startOfDay(endDay) == endMillis
        val last = if (endsAtMidnight) endDay.minusDays(1) else endDay
        return if (last.isBefore(firstDay())) firstDay() else last
    }

    fun spansMultipleDays(): Boolean = firstDay() != lastDay()
}

/** How much of a recurring series an edit or delete applies to. */
enum class RecurrenceScope { SINGLE, ALL }

data class SyncOutcome(
    val calendarsSynced: Int,
    val eventsWritten: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null
}
