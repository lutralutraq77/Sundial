package dev.danny.sundial.ics

import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Writes cached events back out as an .ics file. */
object IcsExporter {

    private val UTC_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val DATE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun export(events: List<EventItem>, calendarName: String): String {
        val builder = StringBuilder()
        builder.appendLine("BEGIN:VCALENDAR")
        builder.appendLine("VERSION:2.0")
        builder.appendLine("PRODID:-//Sundial//Calendar//EN")
        builder.appendLine("CALSCALE:GREGORIAN")
        builder.appendLine(fold("X-WR-CALNAME:${escape(calendarName)}"))

        val stamp = UTC_STAMP.format(Instant.now())
        events.forEach { event ->
            builder.appendLine("BEGIN:VEVENT")
            builder.appendLine("UID:${event.id}@sundial")
            builder.appendLine("DTSTAMP:$stamp")

            if (event.allDay) {
                val start = event.firstDay()
                val endExclusive = event.lastDay().plusDays(1)
                builder.appendLine("DTSTART;VALUE=DATE:${DATE_STAMP.format(start.atStartOfDay(ZoneOffset.UTC))}")
                builder.appendLine("DTEND;VALUE=DATE:${DATE_STAMP.format(endExclusive.atStartOfDay(ZoneOffset.UTC))}")
            } else {
                builder.appendLine("DTSTART:${UTC_STAMP.format(Instant.ofEpochMilli(event.startMillis))}")
                builder.appendLine("DTEND:${UTC_STAMP.format(Instant.ofEpochMilli(event.endMillis))}")
            }

            event.summary?.takeIf { it.isNotBlank() }?.let { builder.appendLine(fold("SUMMARY:${escape(it)}")) }
            event.description?.takeIf { it.isNotBlank() }?.let { builder.appendLine(fold("DESCRIPTION:${escape(it)}")) }
            event.location?.takeIf { it.isNotBlank() }?.let { builder.appendLine(fold("LOCATION:${escape(it)}")) }
            event.recurrence.forEach { builder.appendLine(fold(it)) }
            event.status?.takeIf { it == "confirmed" || it == "tentative" || it == "cancelled" }
                ?.let { builder.appendLine("STATUS:${it.uppercase()}") }

            event.reminders.overrides.forEach { reminder ->
                builder.appendLine("BEGIN:VALARM")
                builder.appendLine("ACTION:DISPLAY")
                builder.appendLine("DESCRIPTION:${escape(event.title)}")
                builder.appendLine("TRIGGER:-PT${reminder.minutes}M")
                builder.appendLine("END:VALARM")
            }

            builder.appendLine("END:VEVENT")
        }

        builder.appendLine("END:VCALENDAR")
        return builder.toString()
    }

    fun suggestedFileName(calendarName: String): String {
        val safe = calendarName.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "calendar" }
        return "$safe ${TimeUtil.toDateString(TimeUtil.today())}.ics"
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /** RFC 5545 says content lines wrap at 75 octets with a leading space. */
    private fun fold(line: String): String {
        if (line.length <= 73) return line
        val builder = StringBuilder()
        var index = 0
        while (index < line.length) {
            val take = if (index == 0) 73 else 72
            val end = minOf(index + take, line.length)
            if (index > 0) builder.append("\r\n ")
            builder.append(line, index, end)
            index = end
        }
        return builder.toString()
    }
}
