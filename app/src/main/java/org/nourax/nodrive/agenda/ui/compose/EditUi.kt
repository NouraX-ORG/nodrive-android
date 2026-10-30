/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package org.nourax.nodrive.agenda.ui.compose

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.edit.AgentNote
import org.nourax.nodrive.agenda.edit.AgentOption
import org.nourax.nodrive.agenda.edit.AgentOptions
import org.nourax.nodrive.agenda.edit.CardForm
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.TaskForm
import org.nourax.nodrive.agenda.edit.TodoForm
import org.nourax.nodrive.agenda.logic.WritableCalendar
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.ui.AgendaTexts
import org.nourax.nodrive.agenda.ui.NewKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** A day, chosen with the phone's date picker. [optional]: it can be taken away again. */
@Composable
fun DateField(label: String, value: LocalDate?, optional: Boolean, texts: AgendaTexts, tag: String, onChange: (LocalDate?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        if (value != null) {
            // one day back or forward without the picker (a quick nudge of a date)
            val before = stringResource(R.string.nodrive_agenda_edit_day_before)
            val after = stringResource(R.string.nodrive_agenda_edit_day_after)
            TextButton(onClick = { onChange(value.minusDays(1)) }, modifier = Modifier.testTag("${tag}_minus").semantics { contentDescription = before }) { Text("\u2212") }
            TextButton(onClick = { onChange(value.plusDays(1)) }, modifier = Modifier.testTag("${tag}_plus").semantics { contentDescription = after }) { Text("+") }
        }
        TextButton(onClick = { show = true }, modifier = Modifier.testTag(tag)) {
            Text(value?.let { texts.shortDate(it) } ?: stringResource(R.string.nodrive_agenda_edit_no_date))
        }
        if (optional && value != null) {
            TextButton(onClick = { onChange(null) }, modifier = Modifier.testTag("${tag}_clear")) { Text(stringResource(R.string.nodrive_agenda_edit_clear)) }
        }
    }
    if (show) {
        val initial = (value ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        show = false
                    },
                    modifier = Modifier.testTag("${tag}_ok")
                ) { Text(stringResource(R.string.nodrive_agenda_ok)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.nodrive_agenda_cancel)) } }
        ) { DatePicker(state = state) }
    }
}

/** A time of day, chosen with the phone's time picker (12 or 24 hours as the phone is set). */
@Composable
fun TimeField(label: String, value: LocalTime, texts: AgendaTexts, tag: String, onChange: (LocalTime) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { show = true }, modifier = Modifier.testTag(tag)) { Text(texts.time(value)) }
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current)
        )
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalTime.of(state.hour, state.minute))
                    show = false
                }, modifier = Modifier.testTag("${tag}_ok")) { Text(stringResource(R.string.nodrive_agenda_ok)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.nodrive_agenda_cancel)) } },
            text = { TimePicker(state = state) }
        )
    }
}

/** "Who does it?" The person comes first, then Self or one of their own agents (design 4.3). */
@Composable
fun AgentChoices(options: List<AgentOption>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("edit_agent")) {
        Text(stringResource(R.string.nodrive_agenda_edit_pick_agent), style = MaterialTheme.typography.labelLarge)
        for (option in options) {
            val name = if (option.id == EditKeys.SELF) stringResource(R.string.nodrive_agenda_self) else option.name
            val note = when (option.note) {
                AgentNote.NONE -> ""
                AgentNote.SELF -> stringResource(R.string.nodrive_agenda_edit_agent_self_note)
                AgentNote.PAUSED -> stringResource(R.string.nodrive_agenda_edit_agent_paused)
                AgentNote.NOT_IN_PROJECT -> stringResource(R.string.nodrive_agenda_edit_agent_ask_manager, option.name)
            }
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = selected == option.id,
                    enabled = option.enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(option.id) }
                ).testTag("edit_agent_${option.id}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == option.id, onClick = null, enabled = option.enabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (option.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SaveRow(valid: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag("edit_cancel")) { Text(stringResource(R.string.nodrive_agenda_cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = valid, modifier = Modifier.testTag("edit_save")) { Text(stringResource(R.string.nodrive_agenda_save)) }
    }
}

/** The fields of one item in the detail sheet: an event, a to-do, a Gantt task or a card. */
@Composable
fun ItemEditor(item: AgendaItem, snapshot: AgendaSnapshot, texts: AgendaTexts, onSave: (List<EditCommand>) -> Unit, onCancel: () -> Unit) {
    val agents = snapshot.build.myAgents
    val options = remember(item.key, snapshot.analysis, agents) { AgentOptions.forItem(item, agents, snapshot.analysis.ganttChoices[item.key]) }
    Column(Modifier.fillMaxWidth().testTag("agenda_editor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item.home) {
            Home.CALENDAR -> EventEditor(item, snapshot, texts, agents, options, onSave, onCancel)
            Home.TASKS -> TodoEditor(item, snapshot, texts, agents, options, onSave, onCancel)
            Home.GANTT -> TaskEditor(item, snapshot, texts, agents, options, onSave, onCancel)
            Home.BOARD -> CardEditor(item, texts, agents, options, onSave, onCancel)
        }
    }
}

@Composable
private fun EventEditor(
    item: AgendaItem,
    snapshot: AgendaSnapshot,
    texts: AgendaTexts,
    agents: List<org.nourax.nodrive.agenda.registry.Agent>,
    options: List<AgentOption>,
    onSave: (List<EditCommand>) -> Unit,
    onCancel: () -> Unit
) {
    val zone = snapshot.zone
    val initial = remember(item.key) { EventForm.of(item, zone) }
    var form by remember(item.key) { mutableStateOf(initial) }
    EventFields(form, texts, options) { form = it }
    SaveRow(valid = eventValid(form), onCancel = onCancel) {
        onSave(listOfNotNull(EditCommands.event(item, form, zone, agents)))
    }
}

private fun eventValid(form: EventForm): Boolean {
    if (form.title.isBlank()) return false
    return if (form.allDay) !form.endDate.isBefore(form.startDate) else !form.endDate.atTime(form.endTime).isBefore(form.startDate.atTime(form.startTime))
}

@Composable
private fun EventFields(form: EventForm, texts: AgendaTexts, options: List<AgentOption>, onChange: (EventForm) -> Unit) {
    OutlinedTextField(
        value = form.title,
        onValueChange = { onChange(form.copy(title = it)) },
        label = { Text(stringResource(R.string.nodrive_agenda_edit_title)) },
        singleLine = true,
        isError = form.title.isBlank(),
        modifier = Modifier.fillMaxWidth().testTag("edit_title")
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = form.allDay, onCheckedChange = { onChange(form.copy(allDay = it)) }, modifier = Modifier.testTag("edit_all_day"))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.nodrive_agenda_edit_all_day), style = MaterialTheme.typography.bodyLarge)
    }
    DateField(stringResource(R.string.nodrive_agenda_edit_start), form.startDate, false, texts, "edit_start_date") {
        it?.let { date ->
            // The end follows the start when it would fall before it.
            val keepLength = if (form.endDate.isBefore(date)) date else form.endDate
            onChange(form.copy(startDate = date, endDate = keepLength))
        }
    }
    if (!form.allDay) {
        TimeField(stringResource(R.string.nodrive_agenda_edit_time), form.startTime, texts, "edit_start_time") { onChange(form.copy(startTime = it)) }
    }
    DateField(stringResource(R.string.nodrive_agenda_edit_end), form.endDate, false, texts, "edit_end_date") { it?.let { date -> onChange(form.copy(endDate = date)) } }
    if (!form.allDay) {
        TimeField(stringResource(R.string.nodrive_agenda_edit_time), form.endTime, texts, "edit_end_time") { onChange(form.copy(endTime = it)) }
    }
    if (!eventValid(form)) {
        Text(
            stringResource(if (form.title.isBlank()) R.string.nodrive_agenda_edit_need_title else R.string.nodrive_agenda_edit_end_before_start),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    OutlinedTextField(
        value = form.description,
        onValueChange = { onChange(form.copy(description = it)) },
        label = { Text(stringResource(R.string.nodrive_agenda_edit_description)) },
        modifier = Modifier.fillMaxWidth().testTag("edit_description")
    )
    AgentChoices(options, form.agentId) { onChange(form.copy(agentId = it)) }
}

@Composable
private fun TodoEditor(
    item: AgendaItem,
    snapshot: AgendaSnapshot,
    texts: AgendaTexts,
    agents: List<org.nourax.nodrive.agenda.registry.Agent>,
    options: List<AgentOption>,
    onSave: (List<EditCommand>) -> Unit,
    onCancel: () -> Unit
) {
    val zone = snapshot.zone
    var form by remember(item.key) { mutableStateOf(TodoForm.of(item, zone)) }
    TodoFields(form, texts, options) { form = it }
    SaveRow(valid = form.title.isNotBlank(), onCancel = onCancel) {
        onSave(listOfNotNull(EditCommands.todo(item, form, zone, agents)))
    }
}

@Composable
private fun TodoFields(form: TodoForm, texts: AgendaTexts, options: List<AgentOption>, onChange: (TodoForm) -> Unit) {
    OutlinedTextField(
        value = form.title,
        onValueChange = { onChange(form.copy(title = it)) },
        label = { Text(stringResource(R.string.nodrive_agenda_edit_title)) },
        singleLine = true,
        isError = form.title.isBlank(),
        modifier = Modifier.fillMaxWidth().testTag("edit_title")
    )
    DateField(stringResource(R.string.nodrive_agenda_edit_start), form.start, true, texts, "edit_start_date") { onChange(form.copy(start = it)) }
    DateField(stringResource(R.string.nodrive_agenda_edit_due), form.due, true, texts, "edit_due_date") { onChange(form.copy(due = it, dueTime = if (it == null) null else form.dueTime)) }
    form.dueTime?.let { time ->
        TimeField(stringResource(R.string.nodrive_agenda_edit_time), time, texts, "edit_due_time") { onChange(form.copy(dueTime = it)) }
    }
    OutlinedTextField(
        value = form.description,
        onValueChange = { onChange(form.copy(description = it)) },
        label = { Text(stringResource(R.string.nodrive_agenda_edit_description)) },
        modifier = Modifier.fillMaxWidth().testTag("edit_description")
    )
    AgentChoices(options, form.agentId) { onChange(form.copy(agentId = it)) }
}

@Composable
private fun TaskEditor(
    item: AgendaItem,
    snapshot: AgendaSnapshot,
    texts: AgendaTexts,
    agents: List<org.nourax.nodrive.agenda.registry.Agent>,
    options: List<AgentOption>,
    onSave: (List<EditCommand>) -> Unit,
    onCancel: () -> Unit
) {
    val caps = org.nourax.nodrive.agenda.edit.EditRules.of(item)
    var form by remember(item.key) { mutableStateOf(TaskForm.of(item)) }
    val start = form.start
    val end = form.end
    val valid = start != null && end != null && !end.isBefore(start)
    DateField(stringResource(R.string.nodrive_agenda_edit_start), form.start, false, texts, "edit_start_date") { it?.let { d -> form = form.copy(start = d, end = if (form.end?.isBefore(d) == true) d else form.end) } }
    DateField(stringResource(R.string.nodrive_agenda_edit_end), form.end, false, texts, "edit_end_date") { it?.let { d -> form = form.copy(end = d) } }
    if (!valid) Text(stringResource(R.string.nodrive_agenda_edit_end_before_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (caps.percentFromBoard) {
        Text(stringResource(R.string.nodrive_agenda_edit_percent_from_board), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("edit_percent_board"))
    } else {
        OutlinedTextField(
            value = form.percent.toString(),
            onValueChange = { text -> text.filter { it.isDigit() }.take(PERCENT_DIGITS).toIntOrNull()?.let { form = form.copy(percent = it.coerceIn(0, MAX_PERCENT)) } },
            label = { Text(stringResource(R.string.nodrive_agenda_edit_percent)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("edit_percent")
        )
    }
    Text(stringResource(R.string.nodrive_agenda_edit_status), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (status in listOf("not_started", "in_progress", "blocked", "done")) {
            FilterChip(
                selected = form.status == status,
                onClick = { form = form.copy(status = status) },
                label = { Text(texts.statusName(status)) },
                modifier = Modifier.testTag("edit_status_$status")
            )
        }
    }
    AgentChoices(options, form.agentId) { form = form.copy(agentId = it) }
    SaveRow(valid = valid, onCancel = onCancel) { onSave(EditCommands.task(item, form, agents)) }
}

@Composable
private fun CardEditor(
    item: AgendaItem,
    texts: AgendaTexts,
    agents: List<org.nourax.nodrive.agenda.registry.Agent>,
    options: List<AgentOption>,
    onSave: (List<EditCommand>) -> Unit,
    onCancel: () -> Unit
) {
    var form by remember(item.key) { mutableStateOf(CardForm.of(item)) }
    DateField(stringResource(R.string.nodrive_agenda_edit_due), form.due, true, texts, "edit_due_date") { form = form.copy(due = it) }
    AgentChoices(options, form.agentId) { form = form.copy(agentId = it) }
    SaveRow(valid = true, onCancel = onCancel) { onSave(EditCommands.card(item, form, agents)) }
}

/** "+" makes a new event or to-do: the calendar, the title, the dates, and the agent (required; Self is preselected). */
@Composable
fun NewItemSheet(kind: NewKind, snapshot: AgendaSnapshot, texts: AgendaTexts, day: LocalDate, onSave: (List<EditCommand>) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val calendars = snapshot.build.writableCalendars.filter { if (kind == NewKind.EVENT) it.events else it.todos }
    val agents = snapshot.build.myAgents
    val options = remember(agents) { AgentOptions.forNew(agents) }
    val zone = snapshot.zone
    var calendar by remember(calendars) { mutableStateOf(calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull()) }
    var event by remember(day) {
        mutableStateOf(
            EventForm("", false, day, LocalTime.of(EventForm.DEFAULT_HOUR, 0), day, LocalTime.of(EventForm.DEFAULT_HOUR + 1, 0), "", EditKeys.SELF)
        )
    }
    var todo by remember(day) { mutableStateOf(TodoForm("", null, null, null, "", EditKeys.SELF)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).testTag("agenda_new_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(if (kind == NewKind.EVENT) R.string.nodrive_agenda_edit_new_event else R.string.nodrive_agenda_edit_new_todo),
                style = MaterialTheme.typography.titleLarge
            )
            if (calendars.isEmpty()) {
                Text(
                    stringResource(if (kind == NewKind.EVENT) R.string.nodrive_agenda_edit_no_calendar else R.string.nodrive_agenda_edit_no_list),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("edit_no_calendar")
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.nodrive_agenda_cancel)) }
            } else {
                CalendarChoice(kind, calendars, calendar) { calendar = it }
                if (kind == NewKind.EVENT) EventFields(event, texts, options) { event = it } else TodoFields(todo, texts, options) { todo = it }
                val valid = if (kind == NewKind.EVENT) eventValid(event) else todo.title.isNotBlank()
                SaveRow(valid = valid && calendar != null, onCancel = onDismiss) {
                    val target = calendar ?: return@SaveRow
                    val command = if (kind == NewKind.EVENT) EditCommands.newEvent(target, event, zone, agents) else EditCommands.newTodo(target, todo, zone, agents)
                    onSave(listOf(command))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalendarChoice(kind: NewKind, calendars: List<WritableCalendar>, selected: WritableCalendar?, onSelect: (WritableCalendar) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (kind == NewKind.EVENT) R.string.nodrive_agenda_edit_calendar else R.string.nodrive_agenda_edit_list),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge
        )
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("edit_calendar")) { Text(selected?.name.orEmpty()) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (calendar in calendars) {
                    DropdownMenuItem(
                        text = { Text(calendar.name) },
                        onClick = {
                            onSelect(calendar)
                            open = false
                        },
                        modifier = Modifier.testTag("edit_calendar_${calendar.uri}")
                    )
                }
            }
        }
    }
}

private const val PERCENT_DIGITS = 3
private const val MAX_PERCENT = 100
