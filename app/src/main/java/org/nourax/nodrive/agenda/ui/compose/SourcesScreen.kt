/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package org.nourax.nodrive.agenda.ui.compose

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.SourceEntry
import org.nourax.nodrive.agenda.model.SourceType
import org.nourax.nodrive.agenda.registry.RegistryStatus
import org.nourax.nodrive.agenda.ui.AgendaTexts
import org.nourax.nodrive.agenda.ui.AgendaViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

/** The Sources screen: every calendar, project and board with its setting, the working hours and the agents (design 6.1). */
@Composable
fun SourcesScreen(vm: AgendaViewModel, snapshot: AgendaSnapshot?, texts: AgendaTexts) {
    Column(Modifier.fillMaxSize().testTag("agenda_sources_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }, modifier = Modifier.testTag("agenda_sources_back")) { Text("‹ " + stringResource(R.string.nodrive_agenda_back)) }
            Text(stringResource(R.string.nodrive_agenda_sources_title), style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        if (snapshot == null) return@Column
        val build = snapshot.build
        LazyColumn(Modifier.fillMaxSize().testTag("agenda_sources_list")) {
            item { SectionTitle(R.string.nodrive_agenda_section_calendars) }
            val calendars = build.sources.filter { it.type == SourceType.CALENDAR }
            items(calendars, key = { "cal-${it.key}" }) { entry ->
                SourceRow(entry, texts, CalendarMode.entries.map { it.id to texts.calendarMode(it) }) { id ->
                    vm.setCalendarMode(entry.key, CalendarMode.of(id, CalendarMode.PERSONAL))
                }
            }

            item { SectionTitle(R.string.nodrive_agenda_section_projects) }
            if (build.ganttUnavailable) {
                item { Note(stringResource(R.string.nodrive_agenda_gantt_unavailable)) }
            } else {
                items(build.sources.filter { it.type == SourceType.PROJECT }, key = { "project-${it.key}" }) { entry ->
                    SourceRow(entry, texts, WorkMode.entries.map { it.id to texts.workMode(it) }) { id ->
                        vm.setProjectMode(entry.key.toInt(), WorkMode.of(id, WorkMode.HIDDEN))
                    }
                }
            }

            item { SectionTitle(R.string.nodrive_agenda_section_boards) }
            if (build.boardUnavailable) {
                item { Note(stringResource(R.string.nodrive_agenda_board_unavailable)) }
            } else {
                items(build.sources.filter { it.type == SourceType.BOARD }, key = { "board-${it.key}" }) { entry ->
                    SourceRow(entry, texts, WorkMode.entries.map { it.id to texts.workMode(it) }) { id ->
                        vm.setBoardMode(entry.key.toInt(), WorkMode.of(id, WorkMode.HIDDEN))
                    }
                }
            }

            item { SectionTitle(R.string.nodrive_agenda_section_hours) }
            item { WorkingHoursEditor(build.workingHours) { vm.setWorkingHours(it) } }

            item { SectionTitle(R.string.nodrive_agenda_section_agents) }
            item {
                when (snapshot.registryStatus) {
                    RegistryStatus.MISSING -> Note(stringResource(R.string.nodrive_agenda_registry_missing))
                    RegistryStatus.INVALID -> Note(stringResource(R.string.nodrive_agenda_registry_invalid))
                    RegistryStatus.OK -> if (build.myAgents.isEmpty()) Note(stringResource(R.string.nodrive_agenda_no_agents))
                }
                if (snapshot.registrySkipped > 0) {
                    Note(texts.skippedAgents(snapshot.registrySkipped))
                }
            }
            items(build.myAgents, key = { "agent-${it.id}" }) { agent ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.nodrive_ic_robot),
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.nodrive_agenda_agent_line, agent.name, texts.agentStatus(agent.status)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (agent.isPaused || agent.isRetired) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One calendar, project or board: a colour dot, the name, a note and the setting as a drop-down. */
@Composable
private fun SourceRow(entry: SourceEntry, texts: AgendaTexts, choices: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val note = if (entry.autoHidden) texts.autoHidden(entry.autoHiddenReason) else texts.note(entry)
    val label = choices.firstOrNull { it.first == entry.mode }?.second.orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("agenda_source_${entry.type.name.lowercase()}_${entry.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(sourceColor(entry.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = if (entry.autoHidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.autoHidden) {
            Text(stringResource(R.string.nodrive_agenda_hidden_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            Box {
                TextButton(onClick = { open = true }) { Text(label + " ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((id, text) in choices) {
                        DropdownMenuItem(text = { Text(text) }, onClick = {
                            open = false
                            if (id != entry.mode) onChoose(id)
                        })
                    }
                }
            }
        }
    }
}

/** The days and hours used for the free time when NoGantt has no schedule for the person. */
@Composable
private fun WorkingHoursEditor(hours: WorkingHours, onChange: (WorkingHours) -> Unit) {
    val context = LocalContext.current
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.nodrive_agenda_hours_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in DayOfWeek.entries) {
                val bit = 1 shl (day.value - 1)
                FilterChip(
                    selected = hours.daysMask and bit != 0,
                    onClick = { onChange(hours.copy(daysMask = hours.daysMask xor bit)) },
                    label = { Text(day.getDisplayName(TextStyle.NARROW, locale)) },
                    modifier = Modifier.weight(1f).testTag("agenda_hours_day_${day.value}")
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                TimePickerDialog(context, { _, h, m -> onChange(hours.copy(from = LocalTime.of(h, m))) }, hours.from.hour, hours.from.minute, true).show()
            }) { Text(stringResource(R.string.nodrive_agenda_hours_from, hours.from.toString())) }
            TextButton(onClick = {
                TimePickerDialog(context, { _, h, m -> onChange(hours.copy(to = LocalTime.of(h, m))) }, hours.to.hour, hours.to.minute, true).show()
            }) { Text(stringResource(R.string.nodrive_agenda_hours_to, hours.to.toString())) }
        }
    }
}
