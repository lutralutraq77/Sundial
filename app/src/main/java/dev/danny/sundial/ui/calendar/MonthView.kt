package dev.danny.sundial.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.common.contrastOn
import dev.danny.sundial.ui.common.parseHexColor
import dev.danny.sundial.ui.common.rememberNow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val EPOCH_MONTH: YearMonth = YearMonth.of(1900, 1)
private const val MONTH_PAGE_COUNT = 2400 // 1900-01 .. 2099-12

private fun pageOf(date: LocalDate): Int =
    ChronoUnit.MONTHS.between(EPOCH_MONTH, YearMonth.from(date)).toInt().coerceIn(0, MONTH_PAGE_COUNT - 1)

private fun monthOf(page: Int): YearMonth = EPOCH_MONTH.plusMonths(page.toLong())

@Composable
fun MonthView(
    anchor: LocalDate,
    selected: LocalDate,
    firstDayOfWeek: DayOfWeek,
    eventsByDay: Map<LocalDate, List<EventItem>>,
    onSelectDate: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
    onOpenEvent: (EventItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetPage = pageOf(anchor)
    val pagerState = rememberPagerState(initialPage = targetPage) { MONTH_PAGE_COUNT }

    LaunchedEffect(targetPage) {
        if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
    }
    // rememberUpdatedState: this collect lambda outlives every recomposition (its only
    // key is the stable pagerState), so a bare `anchor` read stays frozen at the first
    // composition's value — swiping back to a previously visited month then compared
    // equal against the stale anchor and never fired onMonthChange.
    val currentAnchor by rememberUpdatedState(anchor)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val month = monthOf(page)
            if (YearMonth.from(currentAnchor) != month) {
                val today = TimeUtil.today()
                onMonthChange(if (YearMonth.from(today) == month) today else month.atDay(1))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        WeekdayHeader(firstDayOfWeek)
        HorizontalDivider()

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.3f),
        ) { page ->
            MonthGrid(
                month = monthOf(page),
                selected = selected,
                firstDayOfWeek = firstDayOfWeek,
                eventsByDay = eventsByDay,
                onSelectDate = onSelectDate,
            )
        }

        HorizontalDivider()
        SelectedDayAgenda(
            date = selected,
            events = eventsByDay[selected].orEmpty(),
            onOpenEvent = onOpenEvent,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        DateGrid.weekdayLabels(firstDayOfWeek).forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    firstDayOfWeek: DayOfWeek,
    eventsByDay: Map<LocalDate, List<EventItem>>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val gridStart = remember(month, firstDayOfWeek) {
        DateGrid.monthGridStart(month.atDay(1), firstDayOfWeek)
    }
    val today = rememberNow().toLocalDate()

    Column(modifier = Modifier.fillMaxSize()) {
        repeat(6) { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                repeat(7) { weekday ->
                    val date = gridStart.plusDays((week * 7 + weekday).toLong())
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        events = eventsByDay[date].orEmpty(),
                        onClick = { onSelectDate(date) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<EventItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayNumberColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        inMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = dayNumberColor,
                )
            }

            events.take(3).forEach { event ->
                EventChip(event)
            }
            if (events.size > 3) {
                Text(
                    text = "+${events.size - 3}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun EventChip(event: EventItem) {
    val color = parseHexColor(event.colorHex)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    ) {
        Text(
            text = event.title,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = contrastOn(color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SelectedDayAgenda(
    date: LocalDate,
    events: List<EventItem>,
    onOpenEvent: (EventItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TimeUtil.relativeDayLabel(date) ?: TimeUtil.formatDayHeader(date),
                style = MaterialTheme.typography.titleSmall,
            )
            if (TimeUtil.relativeDayLabel(date) != null) {
                Text(
                    text = " · ${TimeUtil.formatDayHeader(date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "Nothing scheduled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(events, key = { "${it.calendarId}|${it.id}" }) { event ->
                    AgendaRow(event = event, onClick = { onOpenEvent(event) })
                }
            }
        }
    }
}

@Composable
fun AgendaRow(
    event: EventItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(parseHexColor(event.colorHex)),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val timeText = if (event.allDay) {
                "All day"
            } else {
                TimeUtil.formatRange(event.startMillis, event.endMillis, false)
            }
            val prefix = if (showDate) "${TimeUtil.formatShortDate(event.firstDay())} · " else ""
            Text(
                text = prefix + timeText + (event.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DayHeightSpacer() {
    Box(modifier = Modifier.height(8.dp))
}
