/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package org.nourax.nodrive.agenda.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.edit.Capabilities
import org.nourax.nodrive.agenda.edit.EditBlock
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditRules
import org.nourax.nodrive.agenda.logic.ConflictBadge
import org.nourax.nodrive.agenda.logic.TimeLabel
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.OwnCalendar
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.ui.AgendaTexts
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The detail sheet (design 8, 9): the facts of the row, and what the person may do with it: change it, tick it done, delete an event
 * or a to-do (after a question), or open it in its own app. Why something can not be changed is said in plain words.
 */
@Composable
fun DetailSheet(
    item: AgendaItem,
    snapshot: AgendaSnapshot,
    texts: AgendaTexts,
    editing: Boolean,
    onEdit: () -> Unit,
    onStopEdit: () -> Unit,
    onSubmit: (List<EditCommand>) -> Unit,
    onDismiss: () -> Unit,
    /** Opened from the Timeline: the sheet has the date editor (design 8). */
    timeline: Boolean = false,
    onOpen: (AgendaItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zone = snapshot.zone
    val day = item.firstDay(zone) ?: snapshot.today
    val time = AgendaFilter.timeLabel(item, day, zone)
    val caps = EditRules.of(item)
    var askDelete by remember(item.key) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).testTag("agenda_detail"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(texts.title(item), style = MaterialTheme.typography.titleLarge)
            if (editing && caps.canEdit) {
                ItemEditor(item, snapshot, texts, onSave = { onSubmit(it) }, onCancel = onStopEdit)
                Spacer(Modifier.height(24.dp))
            } else {
                DetailFacts(item, snapshot, texts, caps, time, day)
                if (timeline) TimelineDateEditor(item, snapshot, texts, onSubmit)
                Spacer(Modifier.height(8.dp))
                if (caps.canEdit) {
                    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag("agenda_edit")) {
                        Icon(painterResource(R.drawable.nodrive_ic_edit), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.nodrive_agenda_edit_edit))
                    }
                }
                if (caps.canTickDone) {
                    OutlinedButton(
                        onClick = { onSubmit(listOf(doneCommand(item, snapshot))) },
                        modifier = Modifier.fillMaxWidth().testTag("agenda_mark_done")
                    ) { Text(stringResource(R.string.nodrive_agenda_edit_mark_done)) }
                }
                OutlinedButton(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth().testTag("agenda_open_in")) {
                    Text(stringResource(openLabelRes(item.home)))
                }
                if (caps.canDelete) {
                    TextButton(onClick = { askDelete = true }, modifier = Modifier.fillMaxWidth().testTag("agenda_delete")) {
                        Text(stringResource(R.string.nodrive_agenda_edit_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            text = {
                Text(
                    stringResource(if (item.kind == ItemKind.EVENT) R.string.nodrive_agenda_edit_delete_event else R.string.nodrive_agenda_edit_delete_todo),
                    modifier = Modifier.testTag("agenda_delete_question")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askDelete = false
                        onSubmit(listOf(EditCommands.removeObject(item)))
                    },
                    modifier = Modifier.testTag("agenda_delete_confirm")
                ) { Text(stringResource(R.string.nodrive_agenda_edit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.nodrive_agenda_cancel)) } }
        )
    }
}

@Composable
private fun DetailFacts(item: AgendaItem, snapshot: AgendaSnapshot, texts: AgendaTexts, caps: Capabilities, time: TimeLabel, day: LocalDate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(kindIcon(item.kind)), contentDescription = null, Modifier.size(18.dp), tint = sourceColor(item.sourceColor))
        Spacer(Modifier.width(8.dp))
        Text(texts.kind(item.kind) + if (item.sourceName.isNotEmpty()) " · " + item.sourceName else "", style = MaterialTheme.typography.bodyMedium)
    }
    val whenText = texts.time(time)
    if (whenText.isNotEmpty()) {
        val dateText = if (item.kind == ItemKind.EVENT || item.kind == ItemKind.TODO) texts.longDate(day) + " · " else ""
        Text(dateText + whenText, style = MaterialTheme.typography.bodyMedium)
    }
    if (item.timeApproximated) Text(stringResource(R.string.nodrive_agenda_time_approximated), style = MaterialTheme.typography.bodySmall)
    if (!item.info && !item.agent.none && !(item.workKind == WorkKind.TEAM && item.title.isEmpty())) {
        Text(stringResource(R.string.nodrive_agenda_detail_agent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AgentLabelLarge(item, texts)
        if (item.invitation) Text(stringResource(R.string.nodrive_agenda_edit_invited_self), style = MaterialTheme.typography.bodySmall)
    }
    if (item.workKind == WorkKind.TEAM && item.personName.isNotEmpty()) {
        Text(stringResource(R.string.nodrive_agenda_detail_person, item.personName), style = MaterialTheme.typography.bodyMedium)
    }
    if (item.percentDone in 0..100 && item.kind != ItemKind.EVENT) {
        Text(stringResource(R.string.nodrive_agenda_detail_percent, item.percentDone), style = MaterialTheme.typography.bodyMedium)
    }
    if (item.kind == ItemKind.GANTT_TASK && item.workKind != WorkKind.TEAM && !item.problemOnly && item.myWorkHours < 0) {
        Text(stringResource(R.string.nodrive_agenda_edit_no_estimate), style = MaterialTheme.typography.bodySmall)
    }
    if (item.repeating) Text(stringResource(R.string.nodrive_agenda_detail_repeats), style = MaterialTheme.typography.bodySmall)
    if (item.tentative) Text(stringResource(R.string.nodrive_agenda_detail_tentative), style = MaterialTheme.typography.bodySmall)
    if (item.invitation) Text(stringResource(R.string.nodrive_agenda_detail_invitation), style = MaterialTheme.typography.bodySmall)
    if (item.pending) {
        Text(stringResource(R.string.nodrive_agenda_edit_waiting_row), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("agenda_detail_pending"))
    }
    for (badge in snapshot.analysis.badgesOf(item.key)) {
        Text(badgeText(badge), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    for (problem in item.problems) {
        Text(texts.problem(problem, item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (item.readOnly && item.readOnlyReason != ReadOnlyReason.NONE) {
        val reason = if (item.readOnlyReason == ReadOnlyReason.VIEWER_PROJECT) {
            stringResource(R.string.nodrive_agenda_edit_viewer)
        } else {
            texts.readOnlyReason(item.readOnlyReason)
        }
        Text(reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("agenda_detail_locked"))
    }
    if (item.versionReadOnly) Text(stringResource(R.string.nodrive_agenda_detail_read_only_guard), style = MaterialTheme.typography.bodySmall)
    val blockText = texts.editBlock(caps.block, "", "")
    if (blockText.isNotEmpty() && caps.block != EditBlock.READ_ONLY && caps.block != EditBlock.VERSION_GUARD) {
        Text(blockText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("agenda_detail_blocked"))
    }
    // The tasks this one follows (design 8: the Timeline's arrows, in words).
    for (dep in item.dependencies) {
        Text(texts.dependency(dep), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("agenda_detail_dependency"))
    }
    if (item.description.isNotEmpty()) Text(item.description, style = MaterialTheme.typography.bodyMedium)
}

// "Mark done" for a to-do, a Gantt task or a card.
private fun doneCommand(item: AgendaItem, snapshot: AgendaSnapshot): EditCommand = when (item.home) {
    Home.TASKS -> EditCommands.todoDone(item, true, Instant.ofEpochMilli(System.currentTimeMillis()))
    Home.GANTT -> EditCommands.taskDone(item)
    else -> EditCommands.cardDone(item)
}

@Composable
fun badgeText(badge: ConflictBadge): String = stringResource(
    when (badge) {
        ConflictBadge.CLASH -> R.string.nodrive_agenda_edit_clash
        ConflictBadge.SOFT_CLASH -> R.string.nodrive_agenda_edit_possible_clash
        ConflictBadge.DEADLINE_ON_DAY_OFF -> R.string.nodrive_agenda_edit_day_off
    }
)

@Composable
private fun AgentLabelLarge(item: AgendaItem, texts: AgendaTexts) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (item.agent.isSelf) R.drawable.nodrive_ic_person else R.drawable.nodrive_ic_robot),
            contentDescription = null,
            Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(texts.agent(item.agent), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/** A small month grid for jumping: a dot under the days with items (design 8, builder's call). */
@Composable
fun MonthGrid(
    date: LocalDate,
    today: LocalDate,
    busy: Set<LocalDate>,
    firstDayOfWeek: DayOfWeek,
    onPick: (LocalDate) -> Unit
) {
    val first = date.withDayOfMonth(1)
    val back = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val start = first.minusDays(back.toLong())
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("agenda_month_grid")) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until DAYS_IN_WEEK) {
                Text(
                    firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        for (week in 0 until GRID_WEEKS) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until DAYS_IN_WEEK) {
                    val day = start.plusDays((week * DAYS_IN_WEEK + i).toLong())
                    val inMonth = day.month == first.month
                    Box(
                        Modifier.weight(1f).height(40.dp).clickable { onPick(day) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(if (day == today) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            Box(
                                Modifier.size(4.dp).clip(CircleShape).background(if (day in busy) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DAYS_IN_WEEK = 7
private const val GRID_WEEKS = 6

/** "Which of your calendars are personal?" (design 6.1): shown once, after the first calendar read. */
@Composable
fun PersonalCalendarsDialog(calendars: List<OwnCalendar>, onSave: (Set<String>) -> Unit, onSkip: () -> Unit) {
    val checked = remember(calendars) { mutableStateMapOf<String, Boolean>().apply { calendars.forEach { put(it.uri, true) } } }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.nodrive_agenda_personal_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("agenda_personal_question")) {
                Text(stringResource(R.string.nodrive_agenda_personal_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                for (calendar in calendars) {
                    Row(
                        Modifier.fillMaxWidth().clickable { checked[calendar.uri] = !(checked[calendar.uri] ?: true) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked[calendar.uri] ?: true, onCheckedChange = { checked[calendar.uri] = it })
                        Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(checked.filterValues { it }.keys) }, modifier = Modifier.testTag("agenda_personal_save")) {
                Text(stringResource(R.string.nodrive_agenda_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip, modifier = Modifier.testTag("agenda_personal_skip")) { Text(stringResource(R.string.nodrive_agenda_skip)) }
        }
    )
}
