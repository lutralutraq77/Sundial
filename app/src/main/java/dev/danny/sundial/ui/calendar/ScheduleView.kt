package dev.danny.sundial.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.common.EmptyState
import dev.danny.sundial.ui.common.rememberNow
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * A continuous list of days that have something on them — Google Calendar's "Schedule"
 * view. Empty days are omitted rather than padding the list out.
 */
@Composable
fun ScheduleView(
    anchor: LocalDate,
    eventsByDay: Map<LocalDate, List<EventItem>>,
    onOpenEvent: (EventItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(eventsByDay) {
        eventsByDay.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    if (days.isEmpty()) {
        EmptyState(
            icon = Icons.Default.EventAvailable,
            title = "Nothing scheduled",
            detail = "Events in this period will appear here once they sync.",
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()

    // Reposition for explicit navigation (today, date picks) but never for a
    // sync/reload changing the day count under a user who has scrolled elsewhere.
    // An anchor change also starts a reload whose window lands a frame or two
    // later, so a single blind scroll would position against stale data and never
    // correct: keep trying until the loaded window actually covers the anchor,
    // with the second attempt (the post-reload data) as the backstop.
    var positionedFor by remember { mutableStateOf<LocalDate?>(null) }
    var attemptsForAnchor by remember(anchor) { mutableStateOf(0) }
    LaunchedEffect(anchor, days) {
        if (positionedFor == anchor) return@LaunchedEffect
        val index = days.indexOfFirst { !it.first.isBefore(anchor) }
            // All loaded days before the anchor: the nearest day is the LAST one.
            .let { if (it >= 0) it else days.lastIndex }
        if (index in days.indices) listState.scrollToItem(index)
        val covers = !days.first().first.isAfter(anchor) && !days.last().first.isBefore(anchor)
        if (covers || attemptsForAnchor >= 1) {
            positionedFor = anchor
        } else {
            attemptsForAnchor++
            // The anchor's own reload lands within a frame or two (restarting this
            // effect for the real second attempt). If it never does — identical
            // data, or an anchor no loaded window can cover, e.g. only future
            // events — stop waiting: an armed reposition must not survive to yank
            // the list when a sync changes the day count hours later.
            delay(2_000)
            positionedFor = anchor
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        days.forEach { (date, events) ->
            item(key = "header-$date") {
                ScheduleDayHeader(date)
            }
            items(
                count = events.size,
                key = { index -> "$date-${events[index].calendarId}-${events[index].id}-$index" },
            ) { index ->
                val event = events[index]
                AgendaRow(event = event, onClick = { onOpenEvent(event) })
            }
            item(key = "divider-$date") {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun ScheduleDayHeader(date: LocalDate) {
    val isToday = date == rememberNow().toLocalDate()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isToday) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = TimeUtil.relativeDayLabel(date) ?: TimeUtil.formatDayHeader(date),
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (TimeUtil.relativeDayLabel(date) != null) {
                Text(
                    text = TimeUtil.formatDayHeader(date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
