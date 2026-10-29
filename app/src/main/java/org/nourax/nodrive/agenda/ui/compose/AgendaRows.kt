/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.logic.AgendaRow
import org.nourax.nodrive.agenda.logic.GroupKind
import org.nourax.nodrive.agenda.logic.QuietKind
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.ui.AgendaTexts

/** The colour of a source on screen ("#RRGGBB", already mapped to Nourax cobalt where needed). */
fun sourceColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex.ifEmpty { "#2563EB" }))
} catch (_: IllegalArgumentException) {
    Color(0xFF2563EB)
}

fun kindIcon(kind: ItemKind): Int = when (kind) {
    ItemKind.EVENT -> R.drawable.nodrive_ic_event
    ItemKind.TODO -> R.drawable.nodrive_ic_task
    ItemKind.GANTT_TASK -> R.drawable.nodrive_ic_task
    ItemKind.MILESTONE -> R.drawable.nodrive_ic_diamond
    ItemKind.CARD -> R.drawable.nodrive_ic_card
}

@Composable
fun DayHeaderRow(row: AgendaRow.DayHeader, texts: AgendaTexts) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag("agenda_day_header"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            texts.longDate(row.date),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (row.today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (row.today) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nodrive_agenda_today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun GroupHeaderRow(row: AgendaRow.Group, texts: AgendaTexts, showAllTeam: Boolean, onToggleNoDate: () -> Unit, onToggleShowAll: () -> Unit) {
    val base = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("agenda_group_${row.kind.name.lowercase()}")
    when (row.kind) {
        GroupKind.NO_DATE -> Row(base.clickable(onClick = onToggleNoDate), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (row.expanded) "▾ " else "▸ ") + texts.group(row.kind) + " (${row.count})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        GroupKind.MY_TEAM -> Row(base, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.nodrive_ic_team), contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(texts.group(row.kind), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onToggleShowAll, modifier = Modifier.testTag("agenda_show_all_team")) {
                Text(stringResource(R.string.nodrive_agenda_show_all_team) + if (showAllTeam) " ✓" else "")
            }
        }
        else -> Row(base, verticalAlignment = Alignment.CenterVertically) {
            Text(
                texts.group(row.kind) + if (row.count > 0) " (${row.count})" else "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun QuietRow(row: AgendaRow.Quiet) {
    val text = when (row.kind) {
        QuietKind.NOTHING_PLANNED -> stringResource(R.string.nodrive_agenda_nothing_day)
        QuietKind.NO_TEAM_PROBLEMS -> stringResource(R.string.nodrive_agenda_no_team_problems)
    }
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The agent of a row: the robot and its name, or the person icon and "Self". */
@Composable
fun AgentLabel(item: AgendaItem, texts: AgendaTexts) {
    val name = texts.agent(item.agent)
    val description = stringResource(R.string.nodrive_agenda_agent_cd, name)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = description }) {
        Icon(
            painterResource(if (item.agent.isSelf) R.drawable.nodrive_ic_person else R.drawable.nodrive_ic_robot),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.agent.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One row of the day: the kind, the title, when, where from, who does it, and the flags (design 8). */
@Composable
fun AgendaItemRow(row: AgendaRow.Item, texts: AgendaTexts, onClick: () -> Unit) {
    val item = row.item
    val dim = item.workKind == WorkKind.MY_AGENT && !item.problemOnly
    val titleText = texts.title(item)
    val whenText = texts.time(row.time)
    val problemTexts = item.problems
        .filterNot { (item.problemOnly || item.title.isEmpty()) && it === item.problems.firstOrNull() }
        .map { texts.problem(it, item) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("agenda_row_${item.key}"),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painterResource(if (item.problemOnly) R.drawable.nodrive_ic_warning else kindIcon(item.kind)),
            contentDescription = texts.kind(item.kind),
            modifier = Modifier.size(24.dp),
            tint = if (item.problemOnly) MaterialTheme.colorScheme.error else sourceColor(item.sourceColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                titleText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (whenText.isNotEmpty() || item.sourceName.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (whenText.isNotEmpty()) {
                        Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.sourceName.isNotEmpty()) {
                        Text(
                            item.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = sourceColor(item.sourceColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.workKind == WorkKind.TEAM && item.personName.isNotEmpty()) {
                    Text(item.personName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A team row that is only a problem sentence (overbooked, not shared) has no agent of its own.
                if (!item.info && !(item.workKind == WorkKind.TEAM && item.title.isEmpty())) AgentLabel(item, texts)
                Badges(item, row.inTeam, texts)
            }
            for (text in problemTexts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.nodrive_ic_warning), contentDescription = stringResource(R.string.nodrive_agenda_problem_cd), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Badges(item: AgendaItem, inTeam: Boolean, texts: AgendaTexts) {
    val error = MaterialTheme.colorScheme.error
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    if (item.late && !inTeam) {
        val late = item.problems.firstOrNull { it.kind == ProblemKind.LATE }
        val text = late?.let { texts.problem(it, item) } ?: lateText(item, texts)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.nodrive_ic_late), stringResource(R.string.nodrive_agenda_late_cd), Modifier.size(14.dp), tint = error)
            Spacer(Modifier.width(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = error)
        }
    }
    if (item.blocked && !inTeam) {
        Icon(painterResource(R.drawable.nodrive_ic_blocked), stringResource(R.string.nodrive_agenda_blocked_cd), Modifier.size(14.dp), tint = error)
    }
    if (item.boardBadge) {
        Icon(painterResource(R.drawable.nodrive_ic_card), stringResource(R.string.nodrive_agenda_board_cd), Modifier.size(14.dp), tint = quiet)
    }
    if (item.repeating) {
        Icon(painterResource(R.drawable.nodrive_ic_repeat), stringResource(R.string.nodrive_agenda_repeats_cd), Modifier.size(14.dp), tint = quiet)
    }
    if (item.readOnly || item.versionReadOnly) {
        Icon(painterResource(R.drawable.nodrive_ic_lock), stringResource(R.string.nodrive_agenda_read_only_cd), Modifier.size(14.dp), tint = quiet)
    }
}

private fun lateText(item: AgendaItem, texts: AgendaTexts): String =
    texts.problem(org.nourax.nodrive.agenda.model.ProblemFlag(ProblemKind.LATE, org.nourax.nodrive.agenda.model.Audience.PERSON, listOf(item.lateDays.toString())), item)
