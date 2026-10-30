/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.edit.EditBlock
import org.nourax.nodrive.agenda.edit.EditRules
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** The two zooms of the Timeline (plan 3.3): how many dp a day is wide. */
enum class TimelineZoom(val dpPerDay: Double) { WEEKS(12.0), DAYS(40.0) }

/** Days <-> dp. [first] is the left edge (x = 0) of the first day; every day is [dpPerDay] wide. */
data class TimelineScale(val first: LocalDate, val days: Int, val dpPerDay: Double) {
    val last: LocalDate get() = first.plusDays((days - 1).toLong())
    val width: Double get() = days * dpPerDay

    /** The left edge of a day. */
    fun xOfDay(day: LocalDate): Double = ChronoUnit.DAYS.between(first, day) * dpPerDay

    /** The right edge of a day (the left edge of the next one). */
    fun xAfterDay(day: LocalDate): Double = xOfDay(day.plusDays(1))

    /** The day under an x: the left edge belongs to the day, the right edge to the next one. */
    fun dayAtX(x: Double): LocalDate = first.plusDays(floor(x / dpPerDay).toLong())

    companion object {
        fun of(range: Pair<LocalDate, LocalDate>, zoom: TimelineZoom): TimelineScale =
            TimelineScale(range.first, (ChronoUnit.DAYS.between(range.first, range.second) + 1).toInt(), zoom.dpPerDay)
    }
}

/** A point, in dp from the top left of the chart. */
data class Pt(val x: Double, val y: Double)

/** A rectangle, in dp from the top left of the chart. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0

    companion object {
        val NONE = Box(0.0, 0.0, 0.0, 0.0)
    }
}

enum class TimelineEntryType { GROUP, BAR, DIAMOND }

/** What a group line is: a project, a phase of it, a to-do list (NoTasks) or a board (NoBoard). The screen turns it into words. */
enum class TimelineGroup { PROJECT, PHASE, LIST, BOARD }

/**
 * One line of the chart: a group header, a bar (a NoGantt task, a to-do with a start and a due date) or a diamond (a milestone, a
 * dated card). [item] is the row it was made from (null for a group).
 */
data class TimelineEntry(
    val type: TimelineEntryType,
    val row: Int,
    val key: String,
    val group: TimelineGroup? = null,
    /** A group's name, or an item's title. */
    val title: String = "",
    val color: String = "",
    val item: AgendaItem? = null,
    /** Bars: the first and last day (the last is included). A diamond has start == end. Dates of the data, not clipped. */
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val percent: Int = -1,
    val clippedLeft: Boolean = false,
    val clippedRight: Boolean = false,
    /** A bar or a card that the date editor may change. */
    val canMove: Boolean = false,
    /** Why not (NONE when it can); a milestone says nothing. */
    val block: EditBlock = EditBlock.NONE,
    /** A NoGantt task with only an end date: drawn as a one-day bar, but there is no start to move. */
    val noDates: Boolean = false,
    val rect: Box = Box.NONE
)

/** A dependency arrow, read only. [points] run from the predecessor's anchor to this row's anchor. */
data class TimelineArrow(
    /** Empty for a stub (the predecessor is not on the screen). */
    val fromKey: String,
    val toKey: String,
    val points: List<Pt>,
    val stub: Boolean,
    val type: String,
    val lagDays: Double,
    val fromTitle: String,
    val toTitle: String,
    val predecessorOwner: String
)

/** The share of my working hours that my own busy events take on one day, and a day off. */
data class TimelineShade(val day: LocalDate, val x: Double, val width: Double, val alpha: Double, val dayOff: Boolean)

data class TimelineInput(
    /** What is shown: the chips and the All / Work / Personal switch are already applied. */
    val items: List<AgendaItem>,
    val scale: TimelineScale,
    val today: LocalDate,
    /** The workload of the days, from the analysis; the shading uses its busy hours. */
    val loads: Map<LocalDate, DayLoad> = emptyMap(),
    /** The days the loads are worked out for (days outside it are not known to be days off). */
    val loadWindow: Pair<LocalDate, LocalDate>? = null
)

data class TimelineResult(
    val scale: TimelineScale,
    val entries: List<TimelineEntry>,
    val arrows: List<TimelineArrow>,
    val shading: List<TimelineShade>,
    val rowCount: Int,
    val height: Double,
    /** The left edge of today; -1 when today is outside the range. */
    val todayX: Double
) {
    val hasWork: Boolean get() = entries.any { it.type != TimelineEntryType.GROUP }
}

/**
 * The Timeline's maths (design 8, plan 3.2): date <-> x, the groups (project > phase, NoTasks list, NoBoard board), the rows, the
 * bar and diamond rectangles, the dependency arrows and the shading of my busy time. No Android types: runs in JVM tests, and the
 * same input always gives the same layout.
 */
object TimelineLayout {
    const val DAYS_BEFORE = 7L
    const val WEEKS_AFTER = 8L
    const val ROW_HEIGHT = 36.0
    const val BAR_HEIGHT = 22.0
    const val DIAMOND = 18.0

    /** The length of the short line for a predecessor that is not on the screen. */
    const val STUB = 14.0
    const val ELBOW = 8.0

    /** The darkest shade of busy time (all working hours taken). */
    const val MAX_SHADE = 0.35

    /** A bar, a diamond or nothing, for one row (null = the Timeline does not draw it). */
    private class Candidate(val item: AgendaItem, val diamond: Boolean, val start: LocalDate, val end: LocalDate)

    private fun wanted(item: AgendaItem): Candidate? {
        if (item.info || item.problemOnly || item.done || item.workKind == WorkKind.TEAM) return null
        return when (item.kind) {
            ItemKind.GANTT_TASK -> {
                if (item.projectId <= 0) return null
                val first = item.startDate
                val last = item.endDate ?: item.due
                if (first == null && last == null) return null
                val s = first ?: last!!
                val e = if (last == null || last.isBefore(s)) s else last
                Candidate(item, false, s, e)
            }
            // Only a to-do with a start AND a due date is a bar, and a repeating one is not (it is changed in NoTasks).
            ItemKind.TODO -> {
                val s = item.startDate
                val d = item.due
                if (item.repeating || s == null || d == null || d.isBefore(s)) null else Candidate(item, false, s, d)
            }
            ItemKind.MILESTONE -> item.startDate?.let { Candidate(item, true, it, it) }
            ItemKind.CARD -> item.due?.let { Candidate(item, true, it, it) }
            ItemKind.EVENT -> null
        }
    }

    private class GroupBuilder(val key: String, val kind: TimelineGroup, val title: String, val color: String, val order: Int) {
        val loose = ArrayList<Candidate>()
        val phases = LinkedHashMap<Int, ArrayList<Candidate>>()
        val phaseTitles = HashMap<Int, String>()
    }

    private val before = Comparator<Candidate> { a, b ->
        when {
            a.start != b.start -> a.start.compareTo(b.start)
            a.end != b.end -> a.end.compareTo(b.end)
            else -> {
                val t = a.item.title.lowercase().compareTo(b.item.title.lowercase())
                if (t != 0) t else a.item.key.compareTo(b.item.key)
            }
        }
    }

    /** The orthogonal route of an arrow; the first and the last point are exactly [from] and [to]. */
    fun route(from: Pt, fromFinish: Boolean, to: Pt, toStart: Boolean): List<Pt> {
        val sf = if (fromFinish) 1.0 else -1.0 // leaves the predecessor to the right (finish) or to the left (start)
        val st = if (toStart) -1.0 else 1.0 // arrives from the left (start) or from the right (finish)
        val a = Pt(from.x + sf * ELBOW, from.y)
        val b = Pt(to.x + st * ELBOW, to.y)
        val points = ArrayList<Pt>()
        points += from
        if (sf > 0 && st < 0 && b.x >= a.x) {
            // The usual finish -> start: one vertical line in the channel next to the predecessor, then straight in.
            points += a
            points += Pt(a.x, to.y)
        } else {
            val midY = (from.y + to.y) / 2.0
            points += a
            points += Pt(a.x, midY)
            points += Pt(b.x, midY)
            points += b
        }
        points += to
        return points
    }

    fun build(input: TimelineInput): TimelineResult {
        val scale = input.scale
        val last = scale.last
        val groups = LinkedHashMap<String, GroupBuilder>()
        for (item in input.items) {
            val c = wanted(item) ?: continue
            if (c.end.isBefore(scale.first) || c.start.isAfter(last)) continue // outside the range
            val key: String
            val fresh: () -> GroupBuilder
            if (item.home == Home.GANTT) {
                key = "gantt:p${item.projectId}"
                fresh = { GroupBuilder(key, TimelineGroup.PROJECT, item.projectName.ifEmpty { item.sourceName }, item.sourceColor, 0) }
            } else if (item.kind == ItemKind.TODO) {
                key = "todo:${item.calendarUri}"
                fresh = { GroupBuilder(key, TimelineGroup.LIST, item.sourceName, item.sourceColor, 1) }
            } else {
                key = "board:${item.boardId}"
                fresh = { GroupBuilder(key, TimelineGroup.BOARD, item.sourceName, item.sourceColor, 2) }
            }
            val group = groups.getOrPut(key, fresh)
            if (item.home == Home.GANTT && item.kind == ItemKind.GANTT_TASK && item.phaseId > 0 && item.phaseName.isNotEmpty()) {
                group.phases.getOrPut(item.phaseId) { ArrayList() } += c
                group.phaseTitles[item.phaseId] = item.phaseName
            } else {
                group.loose += c
            }
        }
        val ordered = groups.values.sortedWith(compareBy<GroupBuilder>({ it.order }, { it.title.lowercase() }, { it.key }))

        val entries = ArrayList<TimelineEntry>()
        val indexOfKey = HashMap<String, Int>()
        var row = 0

        fun addItem(c: Candidate) {
            val item = c.item
            val y = row * ROW_HEIGHT
            val caps = EditRules.of(item)
            val noStart = item.kind == ItemKind.GANTT_TASK && item.startDate == null
            val canMove = !noStart && caps.canEdit && item.kind != ItemKind.MILESTONE
            val rect: Box
            val shownStart = if (c.start.isBefore(scale.first)) scale.first else c.start
            val shownEnd = if (c.end.isAfter(last)) last else c.end
            if (c.diamond) {
                val cx = scale.xOfDay(c.start) + scale.dpPerDay / 2.0
                rect = Box(cx - DIAMOND / 2.0, y + (ROW_HEIGHT - DIAMOND) / 2.0, cx + DIAMOND / 2.0, y + (ROW_HEIGHT + DIAMOND) / 2.0)
            } else {
                val x0 = scale.xOfDay(shownStart)
                val x1 = scale.xAfterDay(shownEnd)
                rect = Box(x0, y + (ROW_HEIGHT - BAR_HEIGHT) / 2.0, x0 + maxOf(x1 - x0, scale.dpPerDay), y + (ROW_HEIGHT + BAR_HEIGHT) / 2.0)
            }
            indexOfKey[item.key] = entries.size
            entries += TimelineEntry(
                type = if (c.diamond) TimelineEntryType.DIAMOND else TimelineEntryType.BAR,
                row = row++,
                key = item.key,
                title = item.title,
                color = item.sourceColor,
                item = item,
                start = c.start,
                end = c.end,
                percent = item.percentDone,
                clippedLeft = !c.diamond && c.start.isBefore(scale.first),
                clippedRight = !c.diamond && c.end.isAfter(last),
                canMove = canMove,
                block = if (canMove) EditBlock.NONE else caps.block,
                noDates = noStart,
                rect = rect
            )
        }

        fun addGroup(key: String, kind: TimelineGroup, title: String, color: String) {
            entries += TimelineEntry(TimelineEntryType.GROUP, row++, key, group = kind, title = title, color = color)
        }

        for (group in ordered) {
            addGroup(group.key, group.kind, group.title, group.color)
            group.loose.sortWith(before)
            group.loose.forEach(::addItem)
            val phaseIds = group.phases.keys.sortedWith(
                compareBy<Int>({ id -> group.phases.getValue(id).minOf { it.start } }, { group.phaseTitles[it].orEmpty().lowercase() }, { it })
            )
            for (id in phaseIds) {
                addGroup("${group.key}:s$id", TimelineGroup.PHASE, group.phaseTitles[id].orEmpty(), group.color)
                val list = group.phases.getValue(id)
                list.sortWith(before)
                list.forEach(::addItem)
            }
        }

        // ---- arrows (read only) ---------------------------------------------------------------------------------
        val arrows = ArrayList<TimelineArrow>()
        for (entry in entries) {
            val item = entry.item ?: continue
            for (dep in item.dependencies) {
                val toStart = dep.type == "EA" || dep.type == "AA"
                val fromFinish = dep.type == "EA" || dep.type == "EE"
                val to = Pt(if (toStart) entry.rect.left else entry.rect.right, entry.rect.centerY)
                val found = indexOfKey[dep.predecessorKey]
                if (found != null) {
                    val pred = entries[found]
                    val from = Pt(if (fromFinish) pred.rect.right else pred.rect.left, pred.rect.centerY)
                    arrows += TimelineArrow(
                        pred.key, entry.key, route(from, fromFinish, to, toStart), false, dep.type, dep.lagDays,
                        pred.title, entry.title, dep.predecessorOwner
                    )
                } else {
                    // A predecessor that is not on the screen (somebody else's task, or outside the range): a short stub.
                    val sx = if (toStart) to.x - STUB else to.x + STUB
                    arrows += TimelineArrow(
                        "", entry.key, listOf(Pt(sx, to.y), to), true, dep.type, dep.lagDays,
                        dep.predecessorTitle, entry.title, dep.predecessorOwner
                    )
                }
            }
        }

        // ---- my busy time ---------------------------------------------------------------------------------------
        val shading = ArrayList<TimelineShade>(scale.days)
        val window = input.loadWindow
        for (i in 0 until scale.days) {
            val day = scale.first.plusDays(i.toLong())
            val load = input.loads[day]
            val known = window == null || (!day.isBefore(window.first) && !day.isAfter(window.second))
            val dayOff = known && input.loads.isNotEmpty() && (load == null || !load.working)
            var alpha = 0.0
            if (load != null && load.working) {
                val total = load.busyHours + load.freeHours
                if (total > 0.0) alpha = MAX_SHADE * (load.busyHours / total).coerceIn(0.0, 1.0)
            }
            shading += TimelineShade(day, i * scale.dpPerDay, scale.dpPerDay, alpha, dayOff)
        }
        val todayX = if (input.today.isBefore(scale.first) || input.today.isAfter(last)) -1.0 else scale.xOfDay(input.today)
        return TimelineResult(scale, entries, arrows, shading, row, row * ROW_HEIGHT, todayX)
    }
}
