package dev.danny.sundial.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

enum class RecurrencePreset(val label: String) {
    NONE("Does not repeat"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    WEEKDAYS("Every weekday (Mon–Fri)"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
    CUSTOM("Custom"),
}

/**
 * Just enough RRULE handling to offer Google Calendar's repeat presets and to describe
 * whatever rule an event already carries. Expansion of recurring events is done
 * server-side, so nothing here needs to compute occurrences.
 */
object Recurrence {

    fun rulesFor(preset: RecurrencePreset, start: LocalDate): List<String> = when (preset) {
        RecurrencePreset.NONE, RecurrencePreset.CUSTOM -> emptyList()
        RecurrencePreset.DAILY -> listOf("RRULE:FREQ=DAILY")
        RecurrencePreset.WEEKLY -> listOf("RRULE:FREQ=WEEKLY;BYDAY=${icalDay(start.dayOfWeek)}")
        RecurrencePreset.WEEKDAYS -> listOf("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        RecurrencePreset.MONTHLY -> listOf("RRULE:FREQ=MONTHLY;BYMONTHDAY=${start.dayOfMonth}")
        RecurrencePreset.YEARLY -> listOf("RRULE:FREQ=YEARLY")
    }

    fun presetFor(rules: List<String>, start: LocalDate): RecurrencePreset {
        val rule = rules.firstOrNull { it.startsWith("RRULE", ignoreCase = true) }
            ?: return RecurrencePreset.NONE
        if (rules.size > 1) return RecurrencePreset.CUSTOM
        val parts = parseRule(rule)
        if (parts.containsKey("COUNT") || parts.containsKey("UNTIL")) return RecurrencePreset.CUSTOM
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        if (interval != 1) return RecurrencePreset.CUSTOM
        val byDay = parts["BYDAY"]
        return when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> if (byDay == null) RecurrencePreset.DAILY else RecurrencePreset.CUSTOM
            "WEEKLY" -> when {
                byDay == null -> RecurrencePreset.WEEKLY
                byDay.split(',').map { it.trim() }.toSet() == WEEKDAY_SET -> RecurrencePreset.WEEKDAYS
                byDay == icalDay(start.dayOfWeek) -> RecurrencePreset.WEEKLY
                else -> RecurrencePreset.CUSTOM
            }
            "MONTHLY" -> {
                val byMonthDay = parts["BYMONTHDAY"]?.toIntOrNull()
                if (byDay == null && (byMonthDay == null || byMonthDay == start.dayOfMonth)) {
                    RecurrencePreset.MONTHLY
                } else {
                    RecurrencePreset.CUSTOM
                }
            }
            "YEARLY" -> if (byDay == null) RecurrencePreset.YEARLY else RecurrencePreset.CUSTOM
            else -> RecurrencePreset.CUSTOM
        }
    }

    /** A readable summary such as "Every 2 weeks on Tue, Thu, until 3 Dec 2026". */
    fun describe(rules: List<String>, start: LocalDate): String {
        val rule = rules.firstOrNull { it.startsWith("RRULE", ignoreCase = true) } ?: return "Does not repeat"
        val parts = parseRule(rule)
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val freq = parts["FREQ"]?.uppercase()

        val base = when (freq) {
            "DAILY" -> if (interval == 1) "Daily" else "Every $interval days"
            "WEEKLY" -> {
                val days = parts["BYDAY"]
                    ?.split(',')
                    ?.mapNotNull { fromIcalDay(it.trim()) }
                    ?.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
                val stem = if (interval == 1) "Weekly" else "Every $interval weeks"
                if (days.isNullOrBlank()) stem else "$stem on $days"
            }
            "MONTHLY" -> if (interval == 1) "Monthly" else "Every $interval months"
            "YEARLY" -> if (interval == 1) "Yearly" else "Every $interval years"
            else -> rule.removePrefix("RRULE:")
        }

        val ending = when {
            parts["COUNT"] != null -> ", ${parts["COUNT"]} times"
            parts["UNTIL"] != null -> parseUntil(parts["UNTIL"])?.let { ", until ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.year}" } ?: ""
            else -> ""
        }
        return base + ending
    }

    fun withCount(rules: List<String>, count: Int): List<String> = rules.map { rule ->
        if (!rule.startsWith("RRULE", ignoreCase = true)) return@map rule
        stripEnding(rule) + ";COUNT=$count"
    }

    fun withUntil(rules: List<String>, until: LocalDate): List<String> = rules.map { rule ->
        if (!rule.startsWith("RRULE", ignoreCase = true)) return@map rule
        val stamp = "%04d%02d%02dT235959Z".format(until.year, until.monthValue, until.dayOfMonth)
        stripEnding(rule) + ";UNTIL=$stamp"
    }

    fun withInterval(rules: List<String>, interval: Int): List<String> = rules.map { rule ->
        if (!rule.startsWith("RRULE", ignoreCase = true)) return@map rule
        val cleaned = rule.split(';').filterNot { it.startsWith("INTERVAL=", ignoreCase = true) }.joinToString(";")
        if (interval <= 1) cleaned else "$cleaned;INTERVAL=$interval"
    }

    fun intervalOf(rules: List<String>): Int =
        rules.firstOrNull { it.startsWith("RRULE", ignoreCase = true) }
            ?.let { parseRule(it)["INTERVAL"]?.toIntOrNull() }
            ?: 1

    private fun stripEnding(rule: String): String = rule.split(';')
        .filterNot { it.startsWith("COUNT=", ignoreCase = true) || it.startsWith("UNTIL=", ignoreCase = true) }
        .joinToString(";")

    private fun parseRule(rule: String): Map<String, String> =
        rule.substringAfter(':', rule)
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "")
                val value = part.substringAfter('=', "")
                if (name.isBlank() || value.isBlank()) null else name.uppercase() to value
            }
            .toMap()

    private fun parseUntil(value: String?): LocalDate? {
        val digits = value?.takeWhile { it.isDigit() } ?: return null
        if (digits.length < 8) return null
        return runCatching {
            LocalDate.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt(),
            )
        }.getOrNull()
    }

    private fun icalDay(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    private fun fromIcalDay(value: String): DayOfWeek? = when (value.takeLast(2).uppercase()) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }

    private val WEEKDAY_SET = setOf("MO", "TU", "WE", "TH", "FR")
}
