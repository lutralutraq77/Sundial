package dev.danny.sundial.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {

    // 2026-08-14 is a Friday.
    private val friday: LocalDate = LocalDate.of(2026, 8, 14)

    @Test
    fun `weekly rules pin the start day`() {
        assertEquals(
            listOf("RRULE:FREQ=WEEKLY;BYDAY=FR"),
            Recurrence.rulesFor(RecurrencePreset.WEEKLY, friday),
        )
    }

    @Test
    fun `monthly rules pin the day of month`() {
        assertEquals(
            listOf("RRULE:FREQ=MONTHLY;BYMONTHDAY=14"),
            Recurrence.rulesFor(RecurrencePreset.MONTHLY, friday),
        )
    }

    @Test
    fun `presets round-trip`() {
        listOf(
            RecurrencePreset.DAILY,
            RecurrencePreset.WEEKLY,
            RecurrencePreset.WEEKDAYS,
            RecurrencePreset.MONTHLY,
            RecurrencePreset.YEARLY,
        ).forEach { preset ->
            val rules = Recurrence.rulesFor(preset, friday)
            assertEquals(preset, Recurrence.presetFor(rules, friday))
        }
    }

    @Test
    fun `no rule means no repeat`() {
        assertEquals(RecurrencePreset.NONE, Recurrence.presetFor(emptyList(), friday))
    }

    @Test
    fun `rules with an interval or an end date are custom`() {
        assertEquals(
            RecurrencePreset.CUSTOM,
            Recurrence.presetFor(listOf("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=FR"), friday),
        )
        assertEquals(
            RecurrencePreset.CUSTOM,
            Recurrence.presetFor(listOf("RRULE:FREQ=DAILY;COUNT=10"), friday),
        )
        assertEquals(
            RecurrencePreset.CUSTOM,
            Recurrence.presetFor(listOf("RRULE:FREQ=DAILY;UNTIL=20261201T235959Z"), friday),
        )
    }

    @Test
    fun `weekday rules are recognised in any order of days`() {
        assertEquals(
            RecurrencePreset.WEEKDAYS,
            Recurrence.presetFor(listOf("RRULE:FREQ=WEEKLY;BYDAY=WE,MO,FR,TU,TH"), friday),
        )
    }

    @Test
    fun `describes an interval with named days`() {
        val text = Recurrence.describe(listOf("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH"), friday)
        assertTrue(text, text.startsWith("Every 2 weeks on "))
        assertTrue(text, text.contains(","))
    }

    @Test
    fun `describes a count limit`() {
        assertEquals(
            "Daily, 10 times",
            Recurrence.describe(listOf("RRULE:FREQ=DAILY;COUNT=10"), friday),
        )
    }

    @Test
    fun `describes an until date`() {
        val text = Recurrence.describe(listOf("RRULE:FREQ=WEEKLY;UNTIL=20261201T235959Z"), friday)
        assertTrue(text, text.contains("until"))
        assertTrue(text, text.contains("2026"))
    }

    @Test
    fun `withUntil replaces any existing end condition`() {
        val updated = Recurrence.withUntil(
            listOf("RRULE:FREQ=DAILY;COUNT=5"),
            LocalDate.of(2026, 12, 1),
        )
        assertEquals(listOf("RRULE:FREQ=DAILY;UNTIL=20261201T235959Z"), updated)
    }

    @Test
    fun `withCount replaces any existing end condition`() {
        val updated = Recurrence.withCount(
            listOf("RRULE:FREQ=DAILY;UNTIL=20261201T235959Z"),
            3,
        )
        assertEquals(listOf("RRULE:FREQ=DAILY;COUNT=3"), updated)
    }

    @Test
    fun `withInterval adds and removes the interval`() {
        assertEquals(
            listOf("RRULE:FREQ=WEEKLY;BYDAY=FR;INTERVAL=3"),
            Recurrence.withInterval(listOf("RRULE:FREQ=WEEKLY;BYDAY=FR"), 3),
        )
        assertEquals(
            listOf("RRULE:FREQ=WEEKLY;BYDAY=FR"),
            Recurrence.withInterval(listOf("RRULE:FREQ=WEEKLY;INTERVAL=3;BYDAY=FR"), 1),
        )
        assertEquals(3, Recurrence.intervalOf(listOf("RRULE:FREQ=WEEKLY;INTERVAL=3")))
        assertEquals(1, Recurrence.intervalOf(listOf("RRULE:FREQ=WEEKLY")))
    }

    @Test
    fun `an unparseable rule falls back to the raw text rather than throwing`() {
        val text = Recurrence.describe(listOf("RRULE:FREQ=HOURLY;INTERVAL=6"), friday)
        assertTrue(text, text.isNotBlank())
    }
}
