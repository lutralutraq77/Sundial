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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.common.EmptyState
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
    val firstIndexAtOrAfterAnchor = remember(days, anchor) {
        days.indexOfFirst { !it.first.isBefore(anchor) }.coerceAtLeast(0)
    }

    LaunchedEffect(firstIndexAtOrAfterAnchor, days.size) {
        if (firstIndexAtOrAfterAnchor in days.indices) {
            listState.scrollToItem(firstIndexAtOrAfterAnchor)
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
    val isToday = date == TimeUtil.today()
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
