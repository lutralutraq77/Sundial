package dev.danny.sundial.ui.calendar

import dev.danny.sundial.core.EventItem
import java.time.DayOfWeek
import java.time.LocalDate

/** Monday-or-Sunday-aware week arithmetic shared by the month, week and day grids. */
object DateGrid {

    fun weekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val offset = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return date.minusDays(offset.toLong())
    }

    /** The first cell of a six-row month grid. */
    fun monthGridStart(month: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
        weekStart(month.withDayOfMonth(1), firstDayOfWeek)

    fun weekdayLabels(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0..6).map { DayOfWeek.of(((firstDayOfWeek.value - 1 + it) % 7) + 1) }

    /**
     * Buckets events by every local day they touch, so a multi-day event shows up in
     * each cell it spans rather than only on its first day.
     */
    fun groupByDay(events: List<EventItem>): Map<LocalDate, List<EventItem>> {
        val buckets = LinkedHashMap<LocalDate, MutableList<EventItem>>()
        events.forEach { event ->
            var day = event.firstDay()
            val last = event.lastDay()
            var guard = 0
            while (!day.isAfter(last) && guard < MAX_SPAN_DAYS) {
                buckets.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
                guard++
            }
        }
        buckets.values.forEach { list ->
            list.sortWith(compareByDescending<EventItem> { it.allDay }.thenBy { it.startMillis })
        }
        return buckets
    }

    private const val MAX_SPAN_DAYS = 400
}
