package dev.danny.sundial.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.danny.sundial.AppContainer
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ics.IcsExporter
import dev.danny.sundial.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

private val SYNC_CHOICES = listOf(
    0 to "Manual only",
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Hourly",
    180 to "Every 3 hours",
    360 to "Every 6 hours",
)

private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

private val WEEK_START_CHOICES = listOf(
    0 to "System default",
    DayOfWeek.MONDAY.value to "Monday",
    DayOfWeek.SATURDAY.value to "Saturday",
    DayOfWeek.SUNDAY.value to "Sunday",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = container.prefs

    var syncInterval by remember { mutableIntStateOf(prefs.syncIntervalMinutes) }
    var firstDay by remember { mutableIntStateOf(prefs.firstDayOfWeekValue) }
    var defaultReminder by remember { mutableIntStateOf(prefs.defaultReminderMinutes) }
    var notifications by remember { mutableStateOf(prefs.notificationsEnabled) }
    var showDeclined by remember { mutableStateOf(prefs.showDeclined) }
    var notifyGuests by remember { mutableStateOf(prefs.notifyGuests) }
    var windowBack by remember { mutableIntStateOf(prefs.windowDaysBack) }
    var windowForward by remember { mutableIntStateOf(prefs.windowDaysForward) }
    var eventCount by remember { mutableIntStateOf(0) }
    var showSignOut by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    val authState by container.auth.state.collectAsState()

    LaunchedEffect(Unit) { eventCount = container.repository.eventCount() }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val payload = exportText
        if (uri != null && payload != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(payload.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                exportText = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Account")
            SettingsInfo(
                title = authState.email ?: "Signed in",
                subtitle = if (prefs.lastSyncMillis > 0) {
                    "Last synced ${TimeUtil.formatTime(prefs.lastSyncMillis)} · $eventCount cached events"
                } else {
                    "Not synced yet"
                },
            )
            prefs.lastSyncError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            SettingsAction("Sign out", "Revokes the token and clears cached events") {
                showSignOut = true
            }

            HorizontalDivider()
            SettingsSection("Sync")
            SettingsChoice(
                title = "Background sync",
                value = SYNC_CHOICES.firstOrNull { it.first == syncInterval }?.second ?: "Every 30 minutes",
                options = SYNC_CHOICES.map { it.first.toString() to it.second },
                onSelect = { value ->
                    syncInterval = value.toInt()
                    prefs.syncIntervalMinutes = syncInterval
                    SyncScheduler.ensurePeriodic(context, syncInterval)
                },
            )
            SettingsInfo(
                title = "No push notifications",
                subtitle = "Without Play Services, Google cannot push changes to this device. " +
                    "Sundial polls on this interval instead.",
            )
            SettingsChoice(
                title = "History kept",
                value = "$windowBack days",
                options = listOf(30, 90, 180, 365, 730).map { it.toString() to "$it days" },
                onSelect = { value ->
                    windowBack = value.toInt()
                    prefs.windowDaysBack = windowBack
                },
            )
            SettingsChoice(
                title = "Future kept",
                value = "$windowForward days",
                options = listOf(180, 365, 540, 730, 1095).map { it.toString() to "$it days" },
                onSelect = { value ->
                    windowForward = value.toInt()
                    prefs.windowDaysForward = windowForward
                },
            )

            HorizontalDivider()
            SettingsSection("Events")
            SettingsChoice(
                title = "Week starts on",
                value = WEEK_START_CHOICES.firstOrNull { it.first == firstDay }?.second ?: "System default",
                options = WEEK_START_CHOICES.map { it.first.toString() to it.second },
                onSelect = { value ->
                    firstDay = value.toInt()
                    prefs.firstDayOfWeekValue = firstDay
                },
            )
            SettingsChoice(
                title = "Default reminder",
                value = TimeUtil.formatReminderOffset(defaultReminder),
                options = REMINDER_CHOICES.map { it.toString() to TimeUtil.formatReminderOffset(it) },
                onSelect = { value ->
                    defaultReminder = value.toInt()
                    prefs.defaultReminderMinutes = defaultReminder
                },
            )
            SettingsToggle(
                title = "Event reminders",
                subtitle = "Local notifications scheduled from synced events",
                checked = notifications,
                onChange = {
                    notifications = it
                    prefs.notificationsEnabled = it
                    scope.launch {
                        withContext(Dispatchers.IO) { container.reminderScheduler.rescheduleAll() }
                    }
                },
            )
            SettingsToggle(
                title = "Show declined events",
                checked = showDeclined,
                onChange = {
                    showDeclined = it
                    prefs.showDeclined = it
                    container.repository.notifyChanged()
                },
            )
            SettingsToggle(
                title = "Email guests about changes",
                subtitle = "Applies when an event has a guest list",
                checked = notifyGuests,
                onChange = {
                    notifyGuests = it
                    prefs.notifyGuests = it
                },
            )

            HorizontalDivider()
            SettingsSection("Data")
            SettingsAction(
                title = "Export cached events as .ics",
                subtitle = "Everything currently in the local cache",
            ) {
                scope.launch {
                    val events = withContext(Dispatchers.IO) {
                        container.store.eventsBetween(
                            TimeUtil.startOfDay(TimeUtil.today().minusDays(windowBack.toLong())),
                            TimeUtil.startOfDay(TimeUtil.today().plusDays(windowForward.toLong())),
                            visibleOnly = false,
                        )
                    }
                    exportText = withContext(Dispatchers.Default) {
                        IcsExporter.export(events, "Sundial export")
                    }
                    exporter.launch(IcsExporter.suggestedFileName("Sundial"))
                }
            }

            HorizontalDivider()
            SettingsSection("About")
            SettingsInfo(
                title = "Sundial 1.0",
                subtitle = "Talks to the Google Calendar REST API over HTTPS with an OAuth token. " +
                    "No Google Play Services, no Google libraries, no analytics.",
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Sundial's access token will be revoked and the local cache cleared. " +
                        "Your Google Calendar data is not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOut = false
                    scope.launch {
                        container.auth.signOut()
                        withContext(Dispatchers.IO) { container.clearLocalData() }
                        SyncScheduler.cancelAll(context)
                        onSignedOut()
                    }
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsInfo(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsChoice(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}
