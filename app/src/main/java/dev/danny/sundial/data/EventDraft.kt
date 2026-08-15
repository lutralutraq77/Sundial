package dev.danny.sundial.data

import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.EventReminders
import dev.danny.sundial.core.TimeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The editable shape of an event. For all-day drafts [end] is the *inclusive* last day
 * at midnight — the exclusive end Google wants is derived when serialising, so the UI
 * never has to reason about the off-by-one.
 */
data class EventDraft(
    val calendarId: String,
    val eventId: String? = null,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zoneId: String = ZoneId.systemDefault().id,
    val recurrence: List<String> = emptyList(),
    val reminders: EventReminders = EventReminders(),
    val colorId: String? = null,
    val attendeeEmails: List<String> = emptyList(),
    val busy: Boolean = true,
    /** Set when editing a single instance of a series. */
    val recurringEventId: String? = null,
) {

    val isNew: Boolean get() = eventId == null

    fun validationError(): String? = when {
        allDay && end.toLocalDate().isBefore(start.toLocalDate()) -> "The end date is before the start date."
        !allDay && !end.isAfter(start) -> "The end time must be after the start time."
        else -> null
    }

    fun toJson(includeRecurrence: Boolean): JsonObject = buildJsonObject {
        put("summary", summary.trim())
        put("description", description.trim())
        put("location", location.trim())

        val zone = TimeUtil.safeZone(zoneId)
        if (allDay) {
            putJsonObject("start") { put("date", TimeUtil.toDateString(start.toLocalDate())) }
            // Google's all-day end date is exclusive.
            putJsonObject("end") { put("date", TimeUtil.toDateString(end.toLocalDate().plusDays(1))) }
        } else {
            putJsonObject("start") {
                put("dateTime", TimeUtil.toRfc3339(start.atZone(zone).toInstant().toEpochMilli(), zone))
                put("timeZone", zone.id)
            }
            putJsonObject("end") {
                put("dateTime", TimeUtil.toRfc3339(end.atZone(zone).toInstant().toEpochMilli(), zone))
                put("timeZone", zone.id)
            }
        }

        if (includeRecurrence) {
            putJsonArray("recurrence") { recurrence.forEach { add(it) } }
        }

        putJsonObject("reminders") {
            put("useDefault", reminders.useDefault)
            if (!reminders.useDefault) {
                putJsonArray("overrides") {
                    reminders.overrides.take(5).forEach { reminder ->
                        addJsonObject {
                            put("method", reminder.method)
                            put("minutes", reminder.minutes)
                        }
                    }
                }
            }
        }

        put("transparency", if (busy) "opaque" else "transparent")
        colorId?.let { put("colorId", it) }

        if (attendeeEmails.isNotEmpty()) {
            putJsonArray("attendees") {
                attendeeEmails.filter { it.isNotBlank() }.forEach { email ->
                    addJsonObject { put("email", email.trim()) }
                }
            }
        }
    }

    companion object {

        fun blank(calendarId: String, at: LocalDateTime, defaultReminderMinutes: Int): EventDraft {
            val truncated = at.withSecond(0).withNano(0)
            // Snap to the next half hour, the way Google Calendar's quick-add does.
            val start = if (truncated.minute < 30) {
                truncated.withMinute(30)
            } else {
                truncated.withMinute(0).plusHours(1)
            }
            return EventDraft(
                calendarId = calendarId,
                start = start,
                end = start.plusHours(1),
                reminders = EventReminders(
                    useDefault = false,
                    overrides = listOf(dev.danny.sundial.core.Reminder("popup", defaultReminderMinutes)),
                ),
            )
        }

        fun from(event: EventItem): EventDraft {
            val start: LocalDateTime
            val end: LocalDateTime
            if (event.allDay) {
                start = event.firstDay().atStartOfDay()
                end = event.lastDay().atStartOfDay()
            } else {
                start = TimeUtil.toLocalDateTime(event.startMillis)
                end = TimeUtil.toLocalDateTime(event.endMillis)
            }
            return EventDraft(
                calendarId = event.calendarId,
                eventId = event.id,
                summary = event.summary.orEmpty(),
                description = event.description.orEmpty(),
                location = event.location.orEmpty(),
                allDay = event.allDay,
                start = start,
                end = end,
                zoneId = event.timeZone ?: ZoneId.systemDefault().id,
                recurrence = event.recurrence,
                reminders = event.reminders,
                colorId = event.colorId,
                attendeeEmails = event.attendees.map { it.email },
                busy = true,
                recurringEventId = event.recurringEventId,
            )
        }
    }
}
