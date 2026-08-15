package dev.danny.sundial.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.danny.sundial.AppContainer
import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.ics.ImportPreview
import dev.danny.sundial.ics.ImportResult
import dev.danny.sundial.ui.common.ColorDot
import dev.danny.sundial.ui.common.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    container: AppContainer,
    initialUri: Uri?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var targetCalendarId by remember { mutableStateOf<String?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    suspend fun load(uri: Uri) {
        busy = true
        parseError = null
        result = null
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
                }
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            parseError = "That file could not be read."
        } else if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
            parseError = "That file is not an iCalendar (.ics) file."
        } else {
            preview = withContext(Dispatchers.Default) { container.importer.preview(text) }
        }
        busy = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { scope.launch { load(it) } } }

    LaunchedEffect(Unit) {
        calendars = container.repository.writableCalendars()
        targetCalendarId = calendars.firstOrNull { it.isPrimary }?.id ?: calendars.firstOrNull()?.id
        initialUri?.let { load(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import .ics") },
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            OutlinedButton(
                onClick = {
                    picker.launch(
                        arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "*/*"),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Choose a .ics file")
            }

            parseError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (busy && preview == null) {
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            val current = preview
            val finished = result

            if (finished != null) {
                Spacer(Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Import finished", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("${finished.imported} event(s) added or updated")
                        if (finished.skipped > 0) Text("${finished.skipped} skipped")
                        if (finished.failed > 0) {
                            Text(
                                text = "${finished.failed} failed",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        finished.messages.forEach { message ->
                            Text(
                                text = "• $message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else if (current != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = current.calendarName ?: "Calendar file",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${current.importable.size} event(s) ready to import",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current.overrideCount > 0) {
                    Text(
                        text = "${current.overrideCount} modified occurrence(s) of recurring events " +
                            "will be skipped — Google's import API cannot attach them to a series.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                current.warnings.take(5).forEach { warning ->
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val sample = current.importable.take(5)
                if (sample.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            sample.forEach { event ->
                                Text(
                                    text = event.summary ?: "(No title)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = if (event.allDay) {
                                        "${event.startDate} · all day"
                                    } else {
                                        TimeUtil.formatFullDate(TimeUtil.toLocalDate(event.startMillis ?: 0L)) +
                                            " · " + TimeUtil.formatTime(event.startMillis ?: 0L)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            if (current.importable.size > sample.size) {
                                Text(
                                    text = "…and ${current.importable.size - sample.size} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Text(
                    text = "Import into",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                if (calendars.isEmpty()) {
                    Text(
                        text = "No writable calendar found. Sync first.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                calendars.forEach { calendar ->
                    CalendarChoiceRow(calendar, calendar.id == targetCalendarId) {
                        targetCalendarId = calendar.id
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (busy) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.second == 0) 0f else progress.first.toFloat() / progress.second
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Importing ${progress.first} of ${progress.second}…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Button(
                        onClick = {
                            val target = targetCalendarId ?: return@Button
                            busy = true
                            progress = 0 to current.importable.size
                            scope.launch {
                                result = container.importer.import(
                                    events = current.importable,
                                    calendarId = target,
                                    onProgress = { done, total -> progress = done to total },
                                )
                                busy = false
                            }
                        },
                        enabled = targetCalendarId != null && current.importable.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Import ${current.importable.size} event(s)")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarChoiceRow(calendar: CalendarInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        ColorDot(parseHexColor(calendar.colorHex))
        Spacer(Modifier.size(10.dp))
        Text(calendar.summary, style = MaterialTheme.typography.bodyMedium)
    }
}
