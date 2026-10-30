/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package org.nourax.nodrive.agenda.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditRules
import org.nourax.nodrive.agenda.edit.TimelineEdit
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.ui.AgendaTexts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date editor of the detail sheet (design 8: no drag on a phone). It shows the dates, moves them by a day with two buttons, or sets
 * new ones with a date range picker. The change is a command like any other (TimelineEdit), so the rules of design 9 apply: a locked
 * row only shows its dates and why it can not be changed.
 */
@Composable
fun TimelineDateEditor(item: AgendaItem, snapshot: AgendaSnapshot, texts: AgendaTexts, onSubmit: (List<EditCommand>) -> Unit) {
    val span = TimelineEdit.spanOf(item)
    val caps = EditRules.of(item)
    var picking by remember(item.key) { mutableStateOf(false) }
    var notSaved by remember(item.key) { mutableStateOf(false) }
    val agents = snapshot.build.myAgents
    val zone = snapshot.zone
    Column(Modifier.fillMaxWidth().testTag("timeline_date_editor"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (span == null) {
            // A row without dates of its own (a milestone, an event) has nothing to edit here: no heading, no empty block.
            if (item.kind == ItemKind.GANTT_TASK) {
                DatesHeading()
                Text(stringResource(R.string.nodrive_agenda_timeline_no_dates), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("timeline_no_dates"))
            }
            return@Column
        }
        DatesHeading()
        Text(texts.dateRange(span.start, span.end), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("timeline_dates"))
        if (!caps.canEdit) return@Column
        fun move(days: Int) {
            TimelineEdit.moveBy(item, days, zone, agents)?.let { onSubmit(listOf(it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { move(-1) }, modifier = Modifier.weight(1f).testTag("timeline_earlier")) {
                Text(stringResource(R.string.nodrive_agenda_timeline_earlier))
            }
            OutlinedButton(onClick = { move(1) }, modifier = Modifier.weight(1f).testTag("timeline_later")) {
                Text(stringResource(R.string.nodrive_agenda_timeline_later))
            }
        }
        TextButton(
            onClick = {
                picking = true
                notSaved = false
            },
            modifier = Modifier.fillMaxWidth().testTag("timeline_change_dates")
        ) {
            Text(stringResource(R.string.nodrive_agenda_timeline_change_dates))
        }
        if (notSaved) {
            Text(
                stringResource(R.string.nodrive_agenda_timeline_dates_not_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("timeline_dates_not_saved")
            )
        }
    }
    if (picking && span != null) {
        DatesPicker(
            span = span,
            onDismiss = { picking = false },
            onPick = { start, end ->
                picking = false
                val command = TimelineEdit.command(item, start, end, zone, agents)
                if (command != null) {
                    notSaved = false
                    onSubmit(listOf(command))
                } else {
                    // Same dates as now: nothing to say. Other dates that can not be written (a timed to-do): tell the person.
                    val newStart = if (span.single) end else start
                    notSaved = !(newStart == span.start && end == span.end)
                }
            }
        )
    }
}

@Composable
private fun DatesHeading() {
    Text(stringResource(R.string.nodrive_agenda_timeline_dates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun toMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun toDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** A date range picker (a single date picker for a card, which has only a due date). */
@Composable
private fun DatesPicker(span: TimelineEdit.Span, onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    if (span.single) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toMillis(span.end))
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { state.selectedDateMillis?.let { onPick(toDay(it), toDay(it)) } ?: onDismiss() },
                    modifier = Modifier.testTag("timeline_pick_ok")
                ) { Text(stringResource(R.string.nodrive_agenda_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nodrive_agenda_cancel)) } }
        ) { DatePicker(state = state) }
        return
    }
    val state = rememberDateRangePickerState(initialSelectedStartDateMillis = toMillis(span.start), initialSelectedEndDateMillis = toMillis(span.end))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis
                    // Only a start day tapped: the task keeps its length (a move), it does not shrink to one day.
                    val end = state.selectedEndDateMillis ?: start?.let { it + (toMillis(span.end) - toMillis(span.start)) }
                    if (start != null && end != null) onPick(toDay(start), toDay(end)) else onDismiss()
                },
                modifier = Modifier.testTag("timeline_pick_ok")
            ) { Text(stringResource(R.string.nodrive_agenda_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nodrive_agenda_cancel)) } }
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(DATE_PICKER_HEIGHT.dp).testTag("timeline_range_picker"))
    }
}

private const val DATE_PICKER_HEIGHT = 480
