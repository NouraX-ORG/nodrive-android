/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owncloud.android.R
import org.nourax.nodrive.agenda.logic.Pt
import org.nourax.nodrive.agenda.logic.TimelineArrow
import org.nourax.nodrive.agenda.logic.TimelineEntry
import org.nourax.nodrive.agenda.logic.TimelineEntryType
import org.nourax.nodrive.agenda.logic.TimelineGroup
import org.nourax.nodrive.agenda.logic.TimelineLayout
import org.nourax.nodrive.agenda.logic.TimelineResult
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.ui.AgendaTexts
import java.time.DayOfWeek
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val ARROW_HEAD = 5f
private const val HATCH_STEP = 8f
private const val LABEL_GAP = 12

/**
 * The chart behind the names: the shading of my busy time and of days off, the week lines, today, the group bands and the dependency
 * arrows are drawn on one canvas; the bars and the diamonds are separate nodes above it, so each one can be focused and tapped.
 */
@Composable
fun TimelineChart(layout: TimelineResult, texts: AgendaTexts, onOpen: (String) -> Unit) {
    val scale = layout.scale
    val primary = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.width(scale.width.dp + END_PADDING).height(layout.height.dp)) {
        Canvas(Modifier.fillMaxSize().testTag("timeline_canvas")) {
            drawBackground(layout, primary, line)
            for (arrow in layout.arrows) drawArrow(arrow, ink)
        }
        for (entry in layout.entries) {
            when (entry.type) {
                TimelineEntryType.BAR -> TimelineBar(entry, texts, onOpen)
                TimelineEntryType.DIAMOND -> TimelineDiamond(entry, texts, onOpen)
                TimelineEntryType.GROUP -> Unit
            }
        }
    }
}

private fun DrawScope.px(value: Double): Float = value.toFloat() * density

private fun DrawScope.drawBackground(layout: TimelineResult, primary: Color, line: Color) {
    val height = px(layout.height)
    // the bands of the groups
    for (entry in layout.entries) {
        if (entry.type != TimelineEntryType.GROUP) continue
        val tint = sourceColor(entry.color).copy(alpha = if (entry.group == TimelineGroup.PHASE) 0.05f else 0.10f)
        drawRect(tint, Offset(0f, px(entry.row * TimelineLayout.ROW_HEIGHT)), Size(size.width, px(TimelineLayout.ROW_HEIGHT)))
    }
    // my busy time (alpha 0 - 0.35 of the primary colour) and the days off (hatched)
    for (shade in layout.shading) {
        if (shade.alpha > 0.0) {
            drawRect(primary.copy(alpha = shade.alpha.toFloat()), Offset(px(shade.x), 0f), Size(px(shade.width), height))
        }
        if (shade.dayOff) {
            val left = px(shade.x)
            val width = px(shade.width)
            clipRect(left, 0f, left + width, height) {
                var y = -width
                while (y < height) {
                    drawLine(line, Offset(left, y + width), Offset(left + width, y), strokeWidth = 1f)
                    y += HATCH_STEP * density
                }
            }
        }
    }
    // a thin line at the start of each week
    for (shade in layout.shading) {
        if (shade.day.dayOfWeek == DayOfWeek.MONDAY) drawLine(line.copy(alpha = 0.6f), Offset(px(shade.x), 0f), Offset(px(shade.x), height), strokeWidth = 1f)
    }
    if (layout.todayX >= 0) {
        val x = px(layout.todayX + layout.scale.dpPerDay / 2.0)
        drawLine(primary, Offset(x, 0f), Offset(x, height), strokeWidth = 2f * density)
    }
}

private fun DrawScope.drawArrow(arrow: TimelineArrow, color: Color) {
    if (arrow.points.size < 2) return
    val path = Path()
    arrow.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), px(p.y)) else path.lineTo(px(p.x), px(p.y)) }
    val effect = if (arrow.stub) PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density)) else null
    drawPath(path, color, style = Stroke(width = 1.5f * density, pathEffect = effect))
    // the head, pointing along the last segment
    val last: Pt = arrow.points.last()
    val before: Pt = arrow.points[arrow.points.size - 2]
    val angle = atan2(px(last.y - before.y), px(last.x - before.x))
    val tipX = px(last.x)
    val tipY = px(last.y)
    val head = Path()
    head.moveTo(tipX, tipY)
    head.lineTo(tipX - ARROW_HEAD * density * cos(angle - 0.5f), tipY - ARROW_HEAD * density * sin(angle - 0.5f))
    head.lineTo(tipX - ARROW_HEAD * density * cos(angle + 0.5f), tipY - ARROW_HEAD * density * sin(angle + 0.5f))
    head.close()
    drawPath(head, color)
}

/** One bar: the project's colour, the done part filled in, and the agent after it. A tap opens the detail sheet. */
@Composable
private fun TimelineBar(entry: TimelineEntry, texts: AgendaTexts, onOpen: (String) -> Unit) {
    val item = entry.item ?: return
    val color = sourceColor(entry.color)
    val shape = RoundedCornerShape(4.dp)
    val percent = entry.percent
    val description = describe(texts, entry, item)
    Row(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
            .offset(entry.rect.left.dp, entry.rect.top.dp)
            .height(entry.rect.height.dp)
            .alpha(if (item.pending) 0.7f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .clickable { onOpen(item.key) }
            .testTag("timeline_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(entry.rect.width.dp).fillMaxHeight().clip(shape).background(color.copy(alpha = 0.30f)).border(1.dp, color, shape)) {
            if (percent in 1..100) Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(color))
        }
        Spacer(Modifier.width(LABEL_GAP.dp))
        AgentTag(item, texts)
    }
}

/** A milestone or a dated card. */
@Composable
private fun TimelineDiamond(entry: TimelineEntry, texts: AgendaTexts, onOpen: (String) -> Unit) {
    val item = entry.item ?: return
    val color = sourceColor(entry.color)
    val description = texts.diamondDescription(item, entry.start ?: return)
    Row(
        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
            .offset(entry.rect.left.dp, entry.rect.top.dp)
            .height(entry.rect.height.dp)
            .alpha(if (item.pending) 0.7f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .clickable { onOpen(item.key) }
            .testTag("timeline_diamond"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.nodrive_ic_diamond), contentDescription = null, Modifier.size(entry.rect.width.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        if (item.kind == org.nourax.nodrive.agenda.model.ItemKind.MILESTONE) {
            Text(texts.title(item), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        } else {
            AgentTag(item, texts)
        }
    }
}

@Composable
private fun describe(texts: AgendaTexts, entry: TimelineEntry, item: AgendaItem): String {
    val base = texts.barDescription(item, entry.start ?: return "", entry.end ?: return "")
    return if (item.pending) base + ", " + stringResource(R.string.nodrive_agenda_timeline_waiting) else base
}

/** "robot Writer-Bot" or "person Self", and "Waiting to send" when a change of mine has not reached the server. */
@Composable
private fun AgentTag(item: AgendaItem, texts: AgendaTexts) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!item.agent.none) {
            Icon(
                painterResource(if (item.agent.isSelf) R.drawable.nodrive_ic_person else R.drawable.nodrive_ic_robot),
                contentDescription = null,
                Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(3.dp))
            // the robot icon is drawn already: a leading robot sign in the name (an unknown agent's label) is dropped
            Text(texts.agent(item.agent).removePrefix(AgentRegistry.robot).trim(), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        if (item.pending) {
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.nodrive_ic_clock), contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(2.dp))
            Text(
                stringResource(R.string.nodrive_agenda_timeline_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
