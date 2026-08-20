package dev.danny.sundial.ui.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.danny.sundial.AppContainer
import dev.danny.sundial.core.EventReminders
import dev.danny.sundial.core.RecurrenceScope
import dev.danny.sundial.core.Reminder
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.data.EventDraft
import dev.danny.sundial.data.Recurrence
import dev.danny.sundial.data.RecurrencePreset
import dev.danny.sundial.ui.common.ColorDot
import dev.danny.sundial.ui.common.parseHexColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60, 2 * 24 * 60, 7 * 24 * 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(
    container: AppContainer,
    calendarId: String?,
    eventId: String?,
    startMillis: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel: EventEditViewModel = viewModel(
        factory = EventEditViewModel.factory(container, calendarId, eventId, startMillis),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showScopeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New event" else "Edit event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !state.saving && state.draft != null,
                        onClick = {
                            if (state.isRecurringInstance) {
                                showScopeDialog = true
                            } else {
                                viewModel.save(RecurrenceScope.SINGLE)
                            }
                        },
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val draft = state.draft
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            draft == null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "Nothing to edit.",
                    modifier = Modifier.padding(24.dp),
                )
            }

            else -> EditForm(
                draft = draft,
                state = state,
                onChange = viewModel::update,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            )
        }
    }

    if (showScopeDialog) {
        AlertDialog(
            onDismissRequest = { showScopeDialog = false },
            title = { Text("Save recurring event") },
            text = { Text("Apply the change to this occurrence only, or to the whole series?") },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        showScopeDialog = false
                        viewModel.save(RecurrenceScope.SINGLE)
                    }) { Text("This event") }
                    TextButton(onClick = {
                        showScopeDialog = false
                        viewModel.save(RecurrenceScope.ALL)
                    }) { Text("All events") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showScopeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(
    draft: EventDraft,
    state: EventEditViewModel.UiState,
    onChange: ((EventDraft) -> EventDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = draft.summary,
            onValueChange = { value -> onChange { it.copy(summary = value) } },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("All day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = draft.allDay,
                onCheckedChange = { allDay -> onChange { it.copy(allDay = allDay) } },
            )
        }

        DateTimeRow(
            label = "Starts",
            date = draft.start.toLocalDate(),
            time = draft.start.toLocalTime(),
            showTime = !draft.allDay,
            onDateChange = { date ->
                onChange { current ->
                    val newStart = current.start.with(date)
                    val shift = java.time.Duration.between(current.start, newStart)
                    current.copy(start = newStart, end = current.end.plus(shift))
                }
            },
            onTimeChange = { time ->
                onChange { current ->
                    val newStart = current.start.with(time)
                    val shift = java.time.Duration.between(current.start, newStart)
                    current.copy(start = newStart, end = current.end.plus(shift))
                }
            },
        )

        DateTimeRow(
            label = "Ends",
            date = draft.end.toLocalDate(),
            time = draft.end.toLocalTime(),
            showTime = !draft.allDay,
            onDateChange = { date -> onChange { it.copy(end = it.end.with(date)) } },
            onTimeChange = { time -> onChange { it.copy(end = it.end.with(time)) } },
        )

        // The pickers show wall times in the EVENT's zone (that is what a save
        // re-anchors), while every other screen shows the device zone — without this
        // label a cross-zone event's editor looks simply wrong.
        val draftZone = TimeUtil.safeZone(draft.zoneId)
        if (!draft.allDay && draftZone != java.time.ZoneId.systemDefault()) {
            Text(
                text = "Times shown in ${draftZone.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ---- calendar ----
        PickerRow(
            label = "Calendar",
            value = state.calendars.firstOrNull { it.id == draft.calendarId }?.summary ?: draft.calendarId,
            leading = {
                ColorDot(
                    parseHexColor(state.calendars.firstOrNull { it.id == draft.calendarId }?.colorHex),
                )
            },
            enabled = state.isNew,
            options = state.calendars.map { it.id to it.summary },
            onSelect = { id -> onChange { it.copy(calendarId = id) } },
        )

        // ---- repeat ----
        val preset = remember(draft.recurrence, draft.start) {
            Recurrence.presetFor(draft.recurrence, draft.start.toLocalDate())
        }
        PickerRow(
            label = "Repeat",
            value = if (preset == RecurrencePreset.CUSTOM) {
                Recurrence.describe(draft.recurrence, draft.start.toLocalDate())
            } else {
                preset.label
            },
            enabled = !state.isRecurringInstance,
            options = RecurrencePreset.entries
                .filter { it != RecurrencePreset.CUSTOM }
                .map { it.name to it.label },
            onSelect = { name ->
                val chosen = RecurrencePreset.valueOf(name)
                onChange { it.copy(recurrence = Recurrence.rulesFor(chosen, it.start.toLocalDate())) }
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        OutlinedTextField(
            value = draft.location,
            onValueChange = { value -> onChange { it.copy(location = value) } },
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.description,
            onValueChange = { value -> onChange { it.copy(description = value) } },
            label = { Text("Description") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        RemindersEditor(
            reminders = draft.reminders,
            onChange = { reminders -> onChange { it.copy(reminders = reminders) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = !draft.busy,
                onCheckedChange = { free -> onChange { it.copy(busy = !free) } },
            )
            Text("Show me as free", style = MaterialTheme.typography.bodyMedium)
        }

        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: LocalTime,
    showTime: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = TimeUtil.formatFullDate(date),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .clickable { showDatePicker = true }
                .padding(vertical = 10.dp),
        )
        if (showTime) {
            Text(
                text = TimeUtil.formatTime(date.atTime(time)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clickable { showTimePicker = true }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    TimePicker(state = pickerState)
                }
            },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
            )
            if (leading != null) {
                leading()
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RemindersEditor(
    reminders: EventReminders,
    onChange: (EventReminders) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reminders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = true }) { Text("Add") }
            Box {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    REMINDER_CHOICES.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(TimeUtil.formatReminderOffset(minutes)) },
                            onClick = {
                                expanded = false
                                val existing = reminders.overrides.map { it.minutes }.toSet()
                                if (minutes !in existing) {
                                    onChange(
                                        EventReminders(
                                            useDefault = false,
                                            overrides = (reminders.overrides + Reminder("popup", minutes))
                                                .sortedBy { it.minutes },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = reminders.useDefault,
                onCheckedChange = { useDefault ->
                    onChange(reminders.copy(useDefault = useDefault))
                },
            )
            Text("Use the calendar's default reminders", style = MaterialTheme.typography.bodyMedium)
        }

        if (!reminders.useDefault) {
            if (reminders.overrides.isEmpty()) {
                Text(
                    text = "No reminders — you will not be notified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reminders.overrides.forEach { reminder ->
                    InputChip(
                        selected = false,
                        onClick = {
                            onChange(reminders.copy(overrides = reminders.overrides - reminder))
                        },
                        label = { Text(TimeUtil.formatReminderOffset(reminder.minutes)) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
