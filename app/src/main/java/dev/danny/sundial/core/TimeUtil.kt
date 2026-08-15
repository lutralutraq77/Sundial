package dev.danny.sundial.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Conversions between the three time representations this app juggles:
 *  - Google's RFC 3339 strings (timed events) and yyyy-MM-dd strings (all-day events)
 *  - epoch millis, used for ordering and range queries in SQLite
 *  - java.time values, used for everything the user sees
 */
object TimeUtil {

    val zone: ZoneId get() = ZoneId.systemDefault()

    // ---- parsing -------------------------------------------------------

    /** Parses an RFC 3339 timestamp, e.g. 2026-08-14T10:00:00+02:00 or ...Z. */
    fun parseTimestamp(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    fun parseTimestampOrNull(value: String?): Long? = value?.let {
        runCatching { parseTimestamp(it) }.getOrNull()
    }

    /** Parses a yyyy-MM-dd date. */
    fun parseDate(value: String): LocalDate = LocalDate.parse(value)

    fun parseDateOrNull(value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    // ---- formatting for the API ---------------------------------------

    fun toRfc3339(millis: Long, zoneId: ZoneId = zone): String =
        Instant.ofEpochMilli(millis)
            .atZone(zoneId)
            .truncatedTo(ChronoUnit.SECONDS)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun toDateString(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    // ---- epoch <-> calendar --------------------------------------------

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun toLocalDateTime(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun millisOf(dateTime: LocalDateTime): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    fun today(): LocalDate = LocalDate.now(zone)

    fun nowMillis(): Long = System.currentTimeMillis()

    // ---- display -------------------------------------------------------

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    private val dayHeaderFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

    private val dayHeaderWithYearFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())

    private val monthTitleFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    private val shortMonthFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    private val fullDateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

    fun formatTime(millis: Long): String = toLocalDateTime(millis).format(timeFmt)

    fun formatTime(time: LocalDateTime): String = time.format(timeFmt)

    fun formatDayHeader(date: LocalDate): String =
        if (date.year == today().year) date.format(dayHeaderFmt) else date.format(dayHeaderWithYearFmt)

    fun formatMonthTitle(date: LocalDate): String = date.format(monthTitleFmt)

    fun formatShortDate(date: LocalDate): String = date.format(shortMonthFmt)

    fun formatFullDate(date: LocalDate): String = date.format(fullDateFmt)

    /** "10:00 – 11:30" or "All day", used in list rows. */
    fun formatRange(startMillis: Long, endMillis: Long, allDay: Boolean): String = when {
        allDay -> "All day"
        else -> "${formatTime(startMillis)} – ${formatTime(endMillis)}"
    }

    fun relativeDayLabel(date: LocalDate): String? = when (date) {
        today() -> "Today"
        today().plusDays(1) -> "Tomorrow"
        today().minusDays(1) -> "Yesterday"
        else -> null
    }

    /** Human description of a reminder offset, e.g. "10 minutes before". */
    fun formatReminderOffset(minutes: Int): String = when {
        minutes == 0 -> "At time of event"
        minutes % (60 * 24 * 7) == 0 -> plural(minutes / (60 * 24 * 7), "week")
        minutes % (60 * 24) == 0 -> plural(minutes / (60 * 24), "day")
        minutes % 60 == 0 -> plural(minutes / 60, "hour")
        else -> plural(minutes, "minute")
    }

    private fun plural(n: Int, unit: String): String =
        if (n == 1) "1 $unit before" else "$n ${unit}s before"

    /** The IANA zone id, falling back to the device zone when the value is unusable. */
    fun safeZone(id: String?): ZoneId =
        id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone

    fun zonedAt(millis: Long, zoneId: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(zoneId)
}
