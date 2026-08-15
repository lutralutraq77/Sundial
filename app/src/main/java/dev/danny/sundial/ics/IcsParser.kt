package dev.danny.sundial.ics

import dev.danny.sundial.core.TimeUtil
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A VEVENT lifted out of an .ics file, before it is mapped onto the Google API. */
data class IcsEvent(
    val uid: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val startDate: String? = null,
    val endDateExclusive: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val timeZone: String? = null,
    val recurrence: List<String> = emptyList(),
    val reminderMinutes: List<Int> = emptyList(),
    val recurrenceId: String? = null,
    val status: String? = null,
    val transparent: Boolean = false,
    val organizerEmail: String? = null,
    val attendeeEmails: List<String> = emptyList(),
) {
    val isOverride: Boolean get() = recurrenceId != null

    val hasUsableTimes: Boolean
        get() = if (allDay) startDate != null else startMillis != null
}

data class IcsParseResult(
    val events: List<IcsEvent>,
    val calendarName: String?,
    val warnings: List<String>,
)

/**
 * A small RFC 5545 reader covering what calendar exports actually contain: folded
 * lines, parameters, escaped TEXT, DATE and DATE-TIME values with or without TZID,
 * DURATION, RRULE/EXDATE/RDATE and display alarms.
 */
object IcsParser {

    private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun parse(text: String): IcsParseResult {
        val lines = unfold(text)
        val events = mutableListOf<IcsEvent>()
        val warnings = mutableListOf<String>()
        var calendarName: String? = null

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    val end = findBlockEnd(lines, index + 1, "VEVENT")
                    val block = lines.subList(index + 1, end)
                    runCatching { parseEvent(block) }
                        .onSuccess { event ->
                            if (event.hasUsableTimes) {
                                events += event
                            } else {
                                warnings += "Skipped \"${event.summary ?: event.uid ?: "untitled"}\": no usable start time."
                            }
                        }
                        .onFailure { warnings += "Skipped an event: ${it.message}" }
                    index = end
                }
                line.startsWith("X-WR-CALNAME", ignoreCase = true) -> {
                    calendarName = unescapeText(property(line).value)
                }
            }
            index++
        }
        return IcsParseResult(events, calendarName, warnings)
    }

    // ---- block parsing -------------------------------------------------

    private fun findBlockEnd(lines: List<String>, from: Int, component: String): Int {
        var depth = 0
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            if (line.equals("BEGIN:$component", ignoreCase = true)) depth++
            if (line.equals("END:$component", ignoreCase = true)) {
                if (depth == 0) return i
                depth--
            }
            i++
        }
        return lines.size
    }

    private fun parseEvent(block: List<String>): IcsEvent {
        var uid: String? = null
        var summary: String? = null
        var description: String? = null
        var location: String? = null
        var status: String? = null
        var transparent = false
        var organizer: String? = null
        var recurrenceId: String? = null
        val attendees = mutableListOf<String>()
        val recurrence = mutableListOf<String>()
        val reminders = mutableListOf<Int>()

        var start: IcsTimeValue? = null
        var end: IcsTimeValue? = null
        var durationMinutes: Long? = null

        var i = 0
        while (i < block.size) {
            val raw = block[i]
            if (raw.equals("BEGIN:VALARM", ignoreCase = true)) {
                val alarmEnd = findBlockEnd(block, i + 1, "VALARM")
                parseAlarmMinutes(block.subList(i + 1, alarmEnd))?.let { reminders += it }
                i = alarmEnd + 1
                continue
            }
            val property = property(raw)
            when (property.name.uppercase()) {
                "UID" -> uid = property.value.trim().ifBlank { null }
                "SUMMARY" -> summary = unescapeText(property.value)
                "DESCRIPTION" -> description = unescapeText(property.value)
                "LOCATION" -> location = unescapeText(property.value)
                "STATUS" -> status = property.value.trim().lowercase()
                "TRANSP" -> transparent = property.value.trim().equals("TRANSPARENT", ignoreCase = true)
                "ORGANIZER" -> organizer = property.value.substringAfter("mailto:", property.value).trim().ifBlank { null }
                "ATTENDEE" -> property.value.substringAfter("mailto:", "").trim().takeIf { it.isNotBlank() }
                    ?.let { attendees += it }
                "RECURRENCE-ID" -> recurrenceId = property.value.trim()
                "DTSTART" -> start = parseTimeValue(property)
                "DTEND" -> end = parseTimeValue(property)
                "DURATION" -> durationMinutes = parseDurationMinutes(property.value)
                "RRULE" -> recurrence += "RRULE:${property.value.trim()}"
                "EXDATE" -> recurrence += rebuildDateListLine("EXDATE", property)
                "RDATE" -> recurrence += rebuildDateListLine("RDATE", property)
            }
            i++
        }

        val startValue = start ?: throw IllegalArgumentException("no DTSTART")
        val startDate = startValue.date

        return if (startDate != null) {
            val endDate = end?.date
                ?: durationMinutes?.let { startDate.plusDays(maxOf(1L, it / (60 * 24))) }
                ?: startDate.plusDays(1)
            IcsEvent(
                uid = uid,
                summary = summary,
                description = description,
                location = location,
                allDay = true,
                startDate = TimeUtil.toDateString(startDate),
                endDateExclusive = TimeUtil.toDateString(if (endDate.isAfter(startDate)) endDate else startDate.plusDays(1)),
                recurrence = recurrence,
                reminderMinutes = reminders.distinct().sorted(),
                recurrenceId = recurrenceId,
                status = status,
                transparent = transparent,
                organizerEmail = organizer,
                attendeeEmails = attendees.distinct(),
            )
        } else {
            val startMillis = startValue.millis ?: throw IllegalArgumentException("unreadable DTSTART")
            val endMillis = end?.millis
                ?: durationMinutes?.let { startMillis + it * 60_000L }
                ?: (startMillis + 60 * 60_000L)
            IcsEvent(
                uid = uid,
                summary = summary,
                description = description,
                location = location,
                allDay = false,
                startMillis = startMillis,
                endMillis = if (endMillis > startMillis) endMillis else startMillis + 60 * 60_000L,
                timeZone = startValue.zoneId,
                recurrence = recurrence,
                reminderMinutes = reminders.distinct().sorted(),
                recurrenceId = recurrenceId,
                status = status,
                transparent = transparent,
                organizerEmail = organizer,
                attendeeEmails = attendees.distinct(),
            )
        }
    }

    private fun parseAlarmMinutes(block: List<String>): Int? {
        var trigger: IcsProperty? = null
        var action: String? = null
        block.forEach { line ->
            val property = property(line)
            when (property.name.uppercase()) {
                "TRIGGER" -> trigger = property
                "ACTION" -> action = property.value.trim().uppercase()
            }
        }
        val value = trigger?.value?.trim() ?: return null
        // Absolute triggers cannot be expressed as "minutes before" — skip them.
        if (trigger?.params?.get("VALUE").equals("DATE-TIME", ignoreCase = true)) return null
        if (action == "AUDIO" || action == "EMAIL" || action == null || action == "DISPLAY") {
            val minutes = parseDurationMinutes(value) ?: return null
            // Negative offsets are "before the event", which is the only thing Google takes.
            return if (minutes <= 0) (-minutes).toInt().coerceIn(0, 40320) else 0
        }
        return null
    }

    // ---- values --------------------------------------------------------

    private data class IcsTimeValue(
        val date: LocalDate? = null,
        val millis: Long? = null,
        val zoneId: String? = null,
    )

    private fun parseTimeValue(property: IcsProperty): IcsTimeValue? {
        val raw = property.value.trim().substringBefore(',')
        if (raw.isEmpty()) return null

        val isDateOnly = property.params["VALUE"].equals("DATE", ignoreCase = true) ||
            (raw.length == 8 && !raw.contains('T'))
        if (isDateOnly) {
            return runCatching { IcsTimeValue(date = LocalDate.parse(raw, BASIC_DATE)) }.getOrNull()
        }

        val utc = raw.endsWith("Z")
        val stamp = raw.removeSuffix("Z")
        val local = runCatching { LocalDateTime.parse(stamp, BASIC_DATE_TIME) }.getOrNull() ?: return null

        val tzid = property.params["TZID"]
        return when {
            utc -> IcsTimeValue(millis = local.toInstant(ZoneOffset.UTC).toEpochMilli(), zoneId = "UTC")
            tzid != null -> {
                val zone = runCatching { ZoneId.of(tzid) }.getOrNull() ?: ZoneId.systemDefault()
                IcsTimeValue(millis = local.atZone(zone).toInstant().toEpochMilli(), zoneId = zone.id)
            }
            // Floating time: interpret in the device's zone, as calendar clients do.
            else -> IcsTimeValue(
                millis = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                zoneId = ZoneId.systemDefault().id,
            )
        }
    }

    /** EXDATE/RDATE must keep their TZID parameter when handed to Google. */
    private fun rebuildDateListLine(name: String, property: IcsProperty): String {
        val params = property.params.entries.joinToString("") { ";${it.key}=${it.value}" }
        return "$name$params:${property.value.trim()}"
    }

    internal fun parseDurationMinutes(value: String): Long? {
        val text = value.trim().uppercase()
        if (text.isEmpty()) return null
        val negative = text.startsWith("-")
        val body = text.removePrefix("-").removePrefix("+")
        if (!body.startsWith("P")) return null

        var minutes = 0L
        var number = StringBuilder()
        var inTime = false
        for (ch in body.drop(1)) {
            when {
                ch.isDigit() -> number.append(ch)
                ch == 'T' -> inTime = true
                else -> {
                    val amount = number.toString().toLongOrNull() ?: return null
                    number = StringBuilder()
                    minutes += when (ch) {
                        'W' -> amount * 7 * 24 * 60
                        'D' -> amount * 24 * 60
                        'H' -> if (inTime) amount * 60 else return null
                        'M' -> if (inTime) amount else return null
                        'S' -> if (inTime) amount / 60 else return null
                        else -> return null
                    }
                }
            }
        }
        return if (negative) -minutes else minutes
    }

    // ---- lexing --------------------------------------------------------

    internal data class IcsProperty(
        val name: String,
        val params: Map<String, String>,
        val value: String,
    )

    internal fun property(line: String): IcsProperty {
        val colon = findValueSeparator(line)
        if (colon < 0) return IcsProperty(line, emptyMap(), "")
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val segments = splitParams(head)
        val name = segments.firstOrNull().orEmpty()
        val params = segments.drop(1).mapNotNull { segment ->
            val key = segment.substringBefore('=', "")
            val raw = segment.substringAfter('=', "")
            if (key.isBlank()) null else key.uppercase() to raw.trim('"')
        }.toMap()
        return IcsProperty(name, params, value)
    }

    /** The first colon that is not inside a quoted parameter value. */
    private fun findValueSeparator(line: String): Int {
        var quoted = false
        line.forEachIndexed { index, ch ->
            when {
                ch == '"' -> quoted = !quoted
                ch == ':' && !quoted -> return index
            }
        }
        return -1
    }

    private fun splitParams(head: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        head.forEach { ch ->
            when {
                ch == '"' -> {
                    quoted = !quoted
                    current.append(ch)
                }
                ch == ';' && !quoted -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** RFC 5545 line folding: a leading space or tab continues the previous line. */
    internal fun unfold(text: String): List<String> {
        val result = mutableListOf<String>()
        text.split("\r\n", "\n", "\r").forEach { line ->
            if (line.isEmpty()) return@forEach
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + line.substring(1)
            } else {
                result += line
            }
        }
        return result
    }

    internal fun unescapeText(value: String): String {
        val builder = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> builder.append('\n')
                    ',' -> builder.append(',')
                    ';' -> builder.append(';')
                    '\\' -> builder.append('\\')
                    else -> builder.append(next)
                }
                i += 2
            } else {
                builder.append(ch)
                i++
            }
        }
        return builder.toString()
    }
}
