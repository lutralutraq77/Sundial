package dev.danny.sundial.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.danny.sundial.AppContainer
import dev.danny.sundial.auth.BrowserLauncher
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.RecurrenceScope
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.data.Recurrence
import dev.danny.sundial.data.selfResponse
import dev.danny.sundial.ui.common.ColorDot
import dev.danny.sundial.ui.common.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    container: AppContainer,
    calendarId: String,
    eventId: String,
    onBack: () -> Unit,
    onEdit: (EventItem) -> Unit,
    onDelete: (EventItem, RecurrenceScope) -> Unit,
    onRespond: (EventItem, String) -> Unit,
) {
    val context = LocalContext.current
    var event by remember { mutableStateOf<EventItem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(calendarId, eventId, container.repository.changes.value) {
        event = container.repository.event(calendarId, eventId)
        loading = false
    }

    val current = event
    val calendar = remember(current) { current?.let { container.store.calendar(it.calendarId) } }
    val canEdit = calendar?.canWrite == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    current?.htmlLink?.let { link ->
                        IconButton(onClick = { BrowserLauncher.open(context, link) }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                        }
                    }
                    if (canEdit && current != null) {
                        IconButton(onClick = { onEdit(current) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            current == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("This event is no longer in the local cache.")
            }

            else -> EventDetailBody(
                event = current,
                calendarName = calendar?.summary,
                onRespond = onRespond,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

    if (showDeleteDialog && current != null) {
        DeleteDialog(
            isRecurring = current.recurringEventId != null,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { scope ->
                showDeleteDialog = false
                onDelete(current, scope)
                onBack()
            },
        )
    }
}

@Composable
private fun EventDetailBody(
    event: EventItem,
    calendarName: String?,
    onRespond: (EventItem, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(parseHexColor(event.colorHex), size = 14)
            Spacer(Modifier.size(10.dp))
            Text(
                text = event.title,
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = calendarName ?: event.calendarId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        DetailRow(Icons.Default.Schedule, whenText(event))

        if (event.isRecurring) {
            DetailRow(Icons.Default.Repeat, Recurrence.describe(event.recurrence, event.firstDay()))
        }

        event.location?.takeIf { it.isNotBlank() }?.let {
            DetailRow(Icons.Default.Place, it)
        }

        val reminderText = when {
            event.reminders.useDefault -> "Calendar default"
            event.reminders.overrides.isEmpty() -> "None"
            else -> event.reminders.overrides.joinToString(", ") { TimeUtil.formatReminderOffset(it.minutes) }
        }
        DetailRow(Icons.Default.Notifications, reminderText)

        event.description?.takeIf { it.isNotBlank() }?.let {
            DetailRow(Icons.Default.Subject, it)
        }

        if (event.attendees.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            DetailRow(
                Icons.Default.People,
                "${event.attendees.size} guest${if (event.attendees.size == 1) "" else "s"}",
            )
            event.attendees.take(20).forEach { attendee ->
                Text(
                    text = buildString {
                        append(attendee.displayName ?: attendee.email)
                        attendee.responseStatus?.let { status ->
                            append(" · ")
                            append(
                                when (status) {
                                    "accepted" -> "Yes"
                                    "declined" -> "No"
                                    "tentative" -> "Maybe"
                                    else -> "Awaiting"
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, bottom = 2.dp),
                )
            }

            if (event.attendees.any { it.self }) {
                Spacer(Modifier.height(12.dp))
                Text("Going?", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val selected = event.selfResponse()
                    listOf("accepted" to "Yes", "tentative" to "Maybe", "declined" to "No").forEach { (value, label) ->
                        AssistChip(
                            onClick = { onRespond(event, value) },
                            label = { Text(if (selected == value) "✓ $label" else label) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun whenText(event: EventItem): String {
    val firstDay = event.firstDay()
    val lastDay = event.lastDay()
    return when {
        event.allDay && firstDay == lastDay -> "${TimeUtil.formatFullDate(firstDay)} · All day"
        event.allDay -> "${TimeUtil.formatFullDate(firstDay)} – ${TimeUtil.formatFullDate(lastDay)} · All day"
        firstDay == lastDay ->
            "${TimeUtil.formatFullDate(firstDay)}\n" +
                TimeUtil.formatRange(event.startMillis, event.endMillis, false)
        else ->
            "${TimeUtil.formatFullDate(firstDay)} ${TimeUtil.formatTime(event.startMillis)}\n" +
                "to ${TimeUtil.formatFullDate(lastDay)} ${TimeUtil.formatTime(event.endMillis)}"
    }
}

@Composable
private fun DeleteDialog(
    isRecurring: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (RecurrenceScope) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRecurring) "Delete recurring event" else "Delete event") },
        text = {
            Text(
                if (isRecurring) {
                    "Delete only this occurrence, or the whole series?"
                } else {
                    "This cannot be undone."
                },
            )
        },
        confirmButton = {
            if (isRecurring) {
                Column {
                    TextButton(onClick = { onConfirm(RecurrenceScope.SINGLE) }) { Text("This event") }
                    TextButton(onClick = { onConfirm(RecurrenceScope.ALL) }) { Text("All events") }
                }
            } else {
                TextButton(onClick = { onConfirm(RecurrenceScope.SINGLE) }) { Text("Delete") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
