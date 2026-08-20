package dev.danny.sundial.ics

import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Writes cached events back out as an .ics file. */
object IcsExporter {

    private val UTC_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val DATE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    private val LOCAL_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

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
                // A recurring event's RRULE/EXDATE lines are zone-anchored, so its
                // DTSTART must stay in that zone or BYDAY/EXDATE stop matching (and
                // the weekly time drifts an hour across DST on re-import).
                val zone = event.recurrence.takeIf { it.isNotEmpty() }
                    ?.let { event.timeZone }
                    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                if (zone != null) {
                    builder.appendLine("DTSTART;TZID=${zone.id}:${LOCAL_STAMP.format(TimeUtil.zonedAt(event.startMillis, zone))}")
                    builder.appendLine("DTEND;TZID=${zone.id}:${LOCAL_STAMP.format(TimeUtil.zonedAt(event.endMillis, zone))}")
                } else {
                    builder.appendLine("DTSTART:${UTC_STAMP.format(Instant.ofEpochMilli(event.startMillis))}")
                    builder.appendLine("DTEND:${UTC_STAMP.format(Instant.ofEpochMilli(event.endMillis))}")
                }
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
        // RFC 5545 3.1 requires CRLF throughout; appendLine and fold() both emit LF,
        // so one pass normalizes everything (escape() already turned literal newlines
        // in event text into "\\n").
        return builder.toString().replace("\n", "\r\n")
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

    /**
     * RFC 5545 says content lines wrap at 75 octets with a leading space. The budget
     * is counted in UTF-8 octets (not UTF-16 chars) and the walk is by code point, so
     * a fold boundary can never split a surrogate pair mid-emoji.
     */
    private fun fold(line: String): String {
        val builder = StringBuilder()
        var index = 0
        var octets = 0
        var limit = 73
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (octets + size > limit) {
                builder.append("\n ")
                octets = 0
                limit = 72
            }
            builder.appendCodePoint(codePoint)
            octets += size
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }
}
