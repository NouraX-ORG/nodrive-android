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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AnalysisBuilder
import org.nourax.nodrive.agenda.logic.TimelineEntry
import org.nourax.nodrive.agenda.logic.TimelineEntryType
import org.nourax.nodrive.agenda.logic.TimelineGroup
import org.nourax.nodrive.agenda.logic.TimelineInput
import org.nourax.nodrive.agenda.logic.TimelineLayout
import org.nourax.nodrive.agenda.logic.TimelineResult
import org.nourax.nodrive.agenda.logic.TimelineScale
import org.nourax.nodrive.agenda.logic.TimelineZoom
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.ui.AgendaTexts
import java.time.DayOfWeek
import java.time.format.TextStyle

/** The width of the fixed column of names on the left. */
private val NAMES_WIDTH = 132.dp
private val HEADER_HEIGHT = 40.dp

/** The layout of the Timeline for the choices on screen (the chips and the switch apply; team rows are never drawn). */
fun timelineLayout(snapshot: AgendaSnapshot, state: ViewState, firstDay: DayOfWeek): TimelineResult {
    val scale = TimelineScale.of(AgendaFilter.range(state, firstDay), state.timelineZoom)
    return TimelineLayout.build(
        TimelineInput(
            items = snapshot.build.items.filter { AgendaFilter.visible(it, state) },
            scale = scale,
            today = snapshot.today,
            loads = snapshot.analysis.days,
            loadWindow = AnalysisBuilder.window(snapshot.today)
        )
    )
}

/**
 * The Timeline (design 8): my work as bars grouped by project and phase, the dependency arrows, milestones and cards as diamonds,
 * the agent of each item and my busy time shaded behind it. A fixed column of names on the left; the chart scrolls sideways and
 * down with the names (one vertical scroll state). No drag on a phone: a tap opens the detail sheet, which has the date editor.
 */
@Composable
fun TimelineScreen(layout: TimelineResult, state: ViewState, texts: AgendaTexts, onZoom: (TimelineZoom) -> Unit, onOpen: (String) -> Unit) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    // "Today" and the arrows move the range: the chart starts at its left edge again.
    LaunchedEffect(layout.scale.first, layout.scale.dpPerDay) { horizontal.scrollTo(0) }
    Column(Modifier.fillMaxSize().testTag("agenda_timeline")) {
        ZoomRow(state.timelineZoom, onZoom)
        if (!layout.hasWork) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.nodrive_agenda_timeline_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("agenda_empty")
                )
            }
            return@Column
        }
        // The scale (sticky) above the rows.
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
            Spacer(Modifier.width(NAMES_WIDTH))
            Box(Modifier.weight(1f).horizontalScroll(horizontal, enabled = false)) {
                TimelineScaleHeader(layout, texts)
            }
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical)) {
            Row(Modifier.fillMaxWidth()) {
                NamesColumn(layout, texts, onOpen)
                Box(Modifier.weight(1f).horizontalScroll(horizontal).testTag("timeline_chart")) {
                    TimelineChart(layout, texts, onOpen)
                }
            }
            // room under the last row for the "+" button
            Spacer(Modifier.height(80.dp))
        }
        TimelineLegend()
    }
}

@Composable
private fun ZoomRow(zoom: TimelineZoom, onZoom: (TimelineZoom) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        TimelineZoom.entries.forEachIndexed { index, z ->
            SegmentedButton(
                selected = zoom == z,
                onClick = { onZoom(z) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TimelineZoom.entries.size),
                modifier = Modifier.testTag("timeline_zoom_${z.name.lowercase()}")
            ) {
                Text(stringResource(if (z == TimelineZoom.WEEKS) R.string.nodrive_agenda_timeline_zoom_weeks else R.string.nodrive_agenda_timeline_zoom_days))
            }
        }
    }
}

/** The names of the groups and of the items, one line for each row of the chart. */
@Composable
private fun NamesColumn(layout: TimelineResult, texts: AgendaTexts, onOpen: (String) -> Unit) {
    Column(Modifier.width(NAMES_WIDTH).testTag("timeline_names")) {
        for (entry in layout.entries) {
            val rowHeight = TimelineLayout.ROW_HEIGHT.dp
            if (entry.type == TimelineEntryType.GROUP) {
                Row(
                    Modifier.fillMaxWidth().height(rowHeight).background(sourceColor(entry.color).copy(alpha = if (entry.group == TimelineGroup.PHASE) 0.08f else 0.16f))
                        .padding(start = if (entry.group == TimelineGroup.PHASE) 16.dp else 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(sourceColor(entry.color)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        groupTitle(entry),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (entry.group == TimelineGroup.PHASE) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("timeline_group")
                    )
                }
            } else {
                val item = entry.item
                Row(
                    Modifier.fillMaxWidth().height(rowHeight).then(if (item != null) Modifier.clickable { onOpen(item.key) } else Modifier).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item != null) {
                        Text(
                            texts.title(item),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.blocked) BadgeIcon(R.drawable.nodrive_ic_blocked)
                        if (item.late) BadgeIcon(R.drawable.nodrive_ic_late)
                        if (!entry.canMove && entry.type == TimelineEntryType.BAR) BadgeIcon(R.drawable.nodrive_ic_lock)
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeIcon(res: Int) {
    Icon(painterResource(res), contentDescription = null, Modifier.padding(start = 2.dp).size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun groupTitle(entry: TimelineEntry): String = when (entry.group) {
    TimelineGroup.LIST -> stringResource(R.string.nodrive_agenda_timeline_group_list, entry.title)
    TimelineGroup.BOARD -> stringResource(R.string.nodrive_agenda_timeline_group_board, entry.title)
    else -> entry.title
}

/** The months and the weeks (or days) above the chart. */
@Composable
private fun TimelineScaleHeader(layout: TimelineResult, texts: AgendaTexts) {
    val scale = layout.scale
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val firstDayOfWeek = remember(locale) { java.time.temporal.WeekFields.of(locale).firstDayOfWeek }
    Box(Modifier.width(scale.width.dp + END_PADDING).height(HEADER_HEIGHT).testTag("timeline_scale")) {
        var day = scale.first
        for (i in 0 until scale.days) {
            val x = scale.xOfDay(day).dp
            if (i == 0 || day.dayOfMonth == 1) {
                Text(
                    texts.monthTitle(day),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = x + 2.dp, top = 2.dp)
                )
            }
            val daysZoom = scale.dpPerDay >= TimelineZoom.DAYS.dpPerDay
            if (daysZoom || day.dayOfWeek == firstDayOfWeek) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = x + 2.dp, top = 20.dp)
                )
                if (daysZoom) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = x + 20.dp, top = 20.dp)
                    )
                }
            }
            day = day.plusDays(1)
        }
    }
}

@Composable
private fun TimelineLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("timeline_legend"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, R.string.nodrive_agenda_timeline_legend_done)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.nodrive_ic_diamond), contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.nodrive_agenda_timeline_legend_milestone), style = MaterialTheme.typography.labelSmall)
        }
        LegendItem(MaterialTheme.colorScheme.primary.copy(alpha = TimelineLayout.MAX_SHADE.toFloat()), R.string.nodrive_agenda_timeline_legend_busy)
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelSmall)
    }
}

internal val END_PADDING: Dp = 180.dp
