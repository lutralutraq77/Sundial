package dev.danny.sundial.data

import dev.danny.sundial.core.Attendee
import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.EventReminders
import dev.danny.sundial.core.Reminder
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.net.dto.ApiEvent
import dev.danny.sundial.net.dto.ApiEventDateTime
import dev.danny.sundial.net.dto.ApiReminder
import dev.danny.sundial.net.dto.CalendarListEntry

fun CalendarListEntry.toCalendarInfo(): CalendarInfo = CalendarInfo(
    id = id,
    summary = displayName,
    description = description,
    timeZone = timeZone,
    colorHex = backgroundColor?.takeIf { it.startsWith("#") } ?: "#4285F4",
    accessRole = accessRole,
    isPrimary = primary,
    visible = selected || primary,
    defaultReminders = defaultReminders.map { it.toReminder() },
)

fun ApiReminder.toReminder(): Reminder = Reminder(method = method, minutes = minutes)

/**
 * Converts an expanded Google event instance into a cache row. Returns null for
 * anything we cannot place on a timeline (no id, or no usable start).
 */
fun ApiEvent.toEventItem(
    calendarId: String,
    calendarColorHex: String,
    eventColors: Map<String, String>,
): EventItem? {
    val eventId = id ?: return null
    val start = start ?: return null

    val allDay = start.date != null
    val startMillis: Long
    val endMillis: Long

    if (allDay) {
        val startDate = TimeUtil.parseDateOrNull(start.date) ?: return null
        val endDate = TimeUtil.parseDateOrNull(end?.date) ?: startDate.plusDays(1)
        startMillis = TimeUtil.startOfDay(startDate)
        endMillis = TimeUtil.startOfDay(if (endDate.isAfter(startDate)) endDate else startDate.plusDays(1))
    } else {
        val parsedStart = TimeUtil.parseTimestampOrNull(start.dateTime) ?: return null
        val parsedEnd = TimeUtil.parseTimestampOrNull(end?.dateTime)
        startMillis = parsedStart
        // Google omits the end for open-ended events; show them as an hour long.
        endMillis = parsedEnd?.takeIf { it > parsedStart } ?: (parsedStart + 60 * 60 * 1000L)
    }

    return EventItem(
        id = eventId,
        calendarId = calendarId,
        summary = summary,
        description = description,
        location = location,
        allDay = allDay,
        startMillis = startMillis,
        endMillis = endMillis,
        startDate = start.date,
        endDate = end?.date,
        timeZone = start.timeZone,
        status = status,
        htmlLink = htmlLink,
        colorId = colorId,
        recurringEventId = recurringEventId,
        recurrence = recurrence ?: emptyList(),
        organizer = organizer?.displayName ?: organizer?.email,
        attendees = attendees.orEmpty().mapNotNull { it.toAttendee() },
        reminders = EventReminders(
            useDefault = reminders?.useDefault ?: true,
            overrides = reminders?.overrides.orEmpty().map { it.toReminder() },
        ),
        hangoutLink = hangoutLink,
        updatedMillis = TimeUtil.parseTimestampOrNull(updated) ?: 0L,
        colorHex = colorId?.let { eventColors[it] } ?: calendarColorHex,
    )
}

private fun dev.danny.sundial.net.dto.ApiAttendee.toAttendee(): Attendee? {
    val address = email ?: displayName ?: return null
    return Attendee(
        email = address,
        displayName = displayName,
        responseStatus = responseStatus,
        self = self,
        organizer = organizer,
        optional = optional,
    )
}

/** The response status of the signed-in user, when the event has a guest list. */
fun EventItem.selfResponse(): String? = attendees.firstOrNull { it.self }?.responseStatus

fun EventItem.isDeclinedBySelf(): Boolean = selfResponse() == "declined"

fun ApiEventDateTime.describe(): String = dateTime ?: date ?: ""
