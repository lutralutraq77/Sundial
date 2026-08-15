package dev.danny.sundial.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.common.contrastOn
import dev.danny.sundial.ui.common.parseHexColor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor

private val HOUR_HEIGHT = 56.dp
private val GUTTER_WIDTH = 52.dp
private val BASE_DATE: LocalDate = LocalDate.of(1970, 1, 1)
private const val TOTAL_DAYS = 47_000 // 1970 .. ~2098

data class PositionedEvent(
    val event: EventItem,
    val column: Int,
    val columnCount: Int,
    val startMinute: Int,
    val endMinute: Int,
)

/**
 * The day / 3-day / week time grid. Events are laid out by minute offset, and
 * overlapping events split the column width the way Google Calendar does.
 */
@Composable
fun TimeGridView(
    anchor: LocalDate,
    dayCount: Int,
    firstDayOfWeek: DayOfWeek,
    eventsByDay: Map<LocalDate, List<EventItem>>,
    onOpenEvent: (EventItem) -> Unit,
    onRangeChange: (LocalDate) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseStart = remember(dayCount, firstDayOfWeek) {
        if (dayCount == 7) DateGrid.weekStart(BASE_DATE, firstDayOfWeek) else BASE_DATE
    }
    val pageCount = remember(dayCount) { TOTAL_DAYS / dayCount }

    fun pageOf(date: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(baseStart, date)
        return floor(days.toDouble() / dayCount).toInt().coerceIn(0, pageCount - 1)
    }

    fun startOfPage(page: Int): LocalDate = baseStart.plusDays(page.toLong() * dayCount)

    val targetPage = pageOf(anchor)
    val pagerState = rememberPagerState(initialPage = targetPage) { pageCount }
    val scrollState = rememberScrollState()

    LaunchedEffect(targetPage, dayCount) {
        if (pagerState.currentPage != targetPage) pagerState.scrollToPage(targetPage)
    }
    LaunchedEffect(pagerState, dayCount) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val start = startOfPage(page)
            if (pageOf(anchor) != page) onRangeChange(start)
        }
    }

    // Open on the working day rather than at midnight.
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        scrollState.scrollTo(with(density) { (HOUR_HEIGHT * 7).toPx() }.toInt())
    }

    HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
        val days = remember(page, dayCount, baseStart) {
            (0 until dayCount).map { startOfPage(page).plusDays(it.toLong()) }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            DayHeaderRow(days)
            AllDayRow(days, eventsByDay, onOpenEvent)
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                HourGutter()
                days.forEach { day ->
                    DayColumn(
                        day = day,
                        events = eventsByDay[day].orEmpty(),
                        onOpenEvent = onOpenEvent,
                        onCreateAt = onCreateAt,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeaderRow(days: List<LocalDate>) {
    val today = TimeUtil.today()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.width(GUTTER_WIDTH))
        days.forEach { day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (day == today) MaterialTheme.colorScheme.primary else Color.Transparent,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (day == today) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    eventsByDay: Map<LocalDate, List<EventItem>>,
    onOpenEvent: (EventItem) -> Unit,
) {
    val hasAllDay = days.any { day -> eventsByDay[day].orEmpty().any { it.allDay } }
    if (!hasAllDay) return

    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Box(modifier = Modifier.width(GUTTER_WIDTH)) {
            Text(
                text = "All day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp),
            )
        }
        days.forEach { day ->
            Column(modifier = Modifier.weight(1f).padding(horizontal = 1.dp)) {
                eventsByDay[day].orEmpty().filter { it.allDay }.take(3).forEach { event ->
                    val color = parseHexColor(event.colorHex)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                            .clickable { onOpenEvent(event) },
                    ) {
                        Text(
                            text = event.title,
                            fontSize = 10.sp,
                            color = contrastOn(color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourGutter() {
    Box(
        modifier = Modifier
            .width(GUTTER_WIDTH)
            .height(HOUR_HEIGHT * 24),
    ) {
        (1..23).forEach { hour ->
            Text(
                text = TimeUtil.formatTime(LocalDate.now().atTime(LocalTime.of(hour, 0))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .offset(y = HOUR_HEIGHT * hour - 7.dp)
                    .width(GUTTER_WIDTH - 6.dp),
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    events: List<EventItem>,
    onOpenEvent: (EventItem) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = remember(events, day) { positionEvents(events, day) }
    val isToday = day == TimeUtil.today()

    BoxWithConstraints(
        modifier = modifier
            .height(HOUR_HEIGHT * 24)
            .clickable {
                // Tapping empty space creates an event at the top of the visible hour.
                onCreateAt(day.atTime(9, 0))
            },
    ) {
        val columnWidth = maxWidth

        (0..23).forEach { hour ->
            HorizontalDivider(
                modifier = Modifier.offset(y = HOUR_HEIGHT * hour),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        )

        positioned.forEach { item ->
            val width = columnWidth / item.columnCount
            val top = HOUR_HEIGHT * (item.startMinute / 60f)
            val height = HOUR_HEIGHT * ((item.endMinute - item.startMinute) / 60f)
            EventBlock(
                event = item.event,
                onClick = { onOpenEvent(item.event) },
                modifier = Modifier
                    .offset(x = width * item.column, y = top)
                    .width(width)
                    .height(maxOf(height, 18.dp)),
            )
        }

        if (isToday) {
            val minutes = LocalTime.now().hour * 60 + LocalTime.now().minute
            Box(
                modifier = Modifier
                    .offset(y = HOUR_HEIGHT * (minutes / 60f))
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

@Composable
private fun EventBlock(
    event: EventItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = parseHexColor(event.colorHex)
    Box(
        modifier = modifier
            .padding(end = 2.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                text = event.title,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = contrastOn(color),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = TimeUtil.formatTime(event.startMillis),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = contrastOn(color).copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Clips events to the day, then splits overlapping runs into side-by-side columns.
 */
fun positionEvents(events: List<EventItem>, day: LocalDate): List<PositionedEvent> {
    val dayStart = TimeUtil.startOfDay(day)
    val dayEnd = TimeUtil.endOfDay(day)

    data class Span(val event: EventItem, val start: Int, val end: Int)

    val spans = events
        .filterNot { it.allDay }
        .mapNotNull { event ->
            val start = maxOf(event.startMillis, dayStart)
            val end = minOf(event.endMillis, dayEnd)
            if (end <= start) return@mapNotNull null
            val startMinute = ((start - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            val endMinute = ((end - dayStart) / 60_000L).toInt().coerceIn(startMinute + 15, 1440)
            Span(event, startMinute, endMinute)
        }
        .sortedWith(compareBy({ it.start }, { -it.end }))

    if (spans.isEmpty()) return emptyList()

    val result = mutableListOf<PositionedEvent>()
    var cluster = mutableListOf<Span>()
    var clusterEnd = Int.MIN_VALUE

    fun flush() {
        if (cluster.isEmpty()) return
        val columnEnds = mutableListOf<Int>()
        val placements = cluster.map { span ->
            var column = columnEnds.indexOfFirst { it <= span.start }
            if (column < 0) {
                columnEnds += span.end
                column = columnEnds.lastIndex
            } else {
                columnEnds[column] = span.end
            }
            span to column
        }
        val columnCount = columnEnds.size
        placements.forEach { (span, column) ->
            result += PositionedEvent(span.event, column, columnCount, span.start, span.end)
        }
        cluster = mutableListOf()
        clusterEnd = Int.MIN_VALUE
    }

    spans.forEach { span ->
        if (cluster.isNotEmpty() && span.start >= clusterEnd) flush()
        cluster += span
        clusterEnd = maxOf(clusterEnd, span.end)
    }
    flush()

    return result
}
