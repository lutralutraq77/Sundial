package dev.danny.sundial.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ui.common.parseHexColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    onViewChange: (CalendarView) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAnchorChange: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onSync: () -> Unit,
    onToggleCalendar: (String, Boolean) -> Unit,
    onOpenEvent: (EventItem) -> Unit,
    onCreateEvent: (LocalDateTime) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    val eventsByDay = remember(state.events) { DateGrid.groupByDay(state.events) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CalendarDrawer(
                state = state,
                onViewChange = {
                    onViewChange(it)
                    scope.launch { drawerState.close() }
                },
                onToggleCalendar = onToggleCalendar,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onOpenImport = {
                    scope.launch { drawerState.close() }
                    onOpenImport()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = titleFor(state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onToday) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Today")
                        }
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        if (state.syncing) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = onSync) {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { onCreateEvent(defaultCreationTime(state.selected)) }) {
                    Icon(Icons.Default.Add, contentDescription = "New event")
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state.view) {
                    CalendarView.MONTH -> MonthView(
                        anchor = state.anchor,
                        selected = state.selected,
                        firstDayOfWeek = state.firstDayOfWeek,
                        eventsByDay = eventsByDay,
                        onSelectDate = onSelectDate,
                        onMonthChange = onAnchorChange,
                        onOpenEvent = onOpenEvent,
                    )
                    CalendarView.SCHEDULE -> ScheduleView(
                        anchor = state.anchor,
                        eventsByDay = eventsByDay,
                        onOpenEvent = onOpenEvent,
                    )
                    CalendarView.DAY -> TimeGridView(
                        anchor = state.anchor,
                        dayCount = 1,
                        firstDayOfWeek = state.firstDayOfWeek,
                        eventsByDay = eventsByDay,
                        onOpenEvent = onOpenEvent,
                        onRangeChange = onAnchorChange,
                        onCreateAt = onCreateEvent,
                    )
                    CalendarView.THREE_DAY -> TimeGridView(
                        anchor = state.anchor,
                        dayCount = 3,
                        firstDayOfWeek = state.firstDayOfWeek,
                        eventsByDay = eventsByDay,
                        onOpenEvent = onOpenEvent,
                        onRangeChange = onAnchorChange,
                        onCreateAt = onCreateEvent,
                    )
                    CalendarView.WEEK -> TimeGridView(
                        anchor = state.anchor,
                        dayCount = 7,
                        firstDayOfWeek = state.firstDayOfWeek,
                        eventsByDay = eventsByDay,
                        onOpenEvent = onOpenEvent,
                        onRangeChange = onAnchorChange,
                        onCreateAt = onCreateEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDrawer(
    state: CalendarUiState,
    onViewChange: (CalendarView) -> Unit,
    onToggleCalendar: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text("Sundial", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.accountEmail ?: "Not signed in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            CalendarView.entries.forEach { view ->
                NavigationDrawerItem(
                    label = { Text(view.label) },
                    selected = state.view == view,
                    onClick = { onViewChange(view) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Calendars",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
            )

            if (state.calendars.isEmpty()) {
                Text(
                    text = "None yet — sync to load your calendars.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }

            state.calendars.forEach { calendar ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleCalendar(calendar.id, !calendar.visible) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (calendar.visible) {
                                    parseHexColor(calendar.colorHex)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (calendar.visible) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = dev.danny.sundial.ui.common.contrastOn(parseHexColor(calendar.colorHex)),
                            )
                        }
                    }
                    Text(
                        text = calendar.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            NavigationDrawerItem(
                label = { Text("Import .ics file") },
                icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                selected = false,
                onClick = onOpenImport,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )

            if (state.lastSyncMillis > 0) {
                Text(
                    text = "Last synced ${TimeUtil.formatTime(state.lastSyncMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 24.dp),
                )
            }
        }
    }
}

private fun titleFor(state: CalendarUiState): String = when (state.view) {
    CalendarView.DAY -> TimeUtil.formatFullDate(state.anchor)
    CalendarView.THREE_DAY, CalendarView.WEEK -> {
        val start = if (state.view == CalendarView.WEEK) {
            DateGrid.weekStart(state.anchor, state.firstDayOfWeek)
        } else {
            state.anchor
        }
        val end = start.plusDays(if (state.view == CalendarView.WEEK) 6L else 2L)
        if (start.month == end.month) {
            TimeUtil.formatMonthTitle(start)
        } else {
            "${TimeUtil.formatShortDate(start)} – ${TimeUtil.formatShortDate(end)}"
        }
    }
    else -> TimeUtil.formatMonthTitle(state.anchor)
}

private fun defaultCreationTime(selected: LocalDate): LocalDateTime {
    val today = TimeUtil.today()
    return if (selected == today) {
        LocalDateTime.of(today, LocalTime.now())
    } else {
        selected.atTime(9, 0)
    }
}
