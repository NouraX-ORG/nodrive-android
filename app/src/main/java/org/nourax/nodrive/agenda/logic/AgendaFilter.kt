/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class AgendaView { DAY, WEEK, MONTH }

enum class CategoryFilter { ALL, WORK, PERSONAL }

/** What the person chose at the top of the screen: the view, the day, the switch, the chips (design 6.0, 8). */
data class ViewState(
    val view: AgendaView = AgendaView.DAY,
    val date: LocalDate,
    val category: CategoryFilter = CategoryFilter.ALL,
    val chipMe: Boolean = true,
    val chipAgents: Boolean = true,
    val chipTeam: Boolean = true,
    val showAllTeam: Boolean = false,
    val noDateExpanded: Boolean = false
)

enum class GroupKind { LATE, DUE, DUE_TODAY, IN_PROGRESS, AGENT_PROBLEMS, MY_TEAM, NO_DATE }

enum class QuietKind { NOTHING_PLANNED, NO_TEAM_PROBLEMS }

enum class TimeKind { NONE, ALL_DAY, ALL_DAY_UNTIL, RANGE, FROM, UNTIL, SINGLE, DUE_AT, DUE_ON }

/** When an item is, on this day. The screen turns it into words in the phone's language and time format. */
data class TimeLabel(
    val kind: TimeKind = TimeKind.NONE,
    val time1: LocalTime? = null,
    val time2: LocalTime? = null,
    val date: LocalDate? = null
)

sealed interface AgendaRow {
    data class DayHeader(val date: LocalDate, val today: Boolean) : AgendaRow

    data class Group(val kind: GroupKind, val count: Int, val expanded: Boolean = false) : AgendaRow

    data class Item(val item: AgendaItem, val day: LocalDate, val inTeam: Boolean, val time: TimeLabel) : AgendaRow

    data class Quiet(val kind: QuietKind) : AgendaRow
}

/** The lists of the Day, Week and Month views, from the rows and the choices (design 6.0, 6.2, 6.3, 8). */
object AgendaFilter {
    fun visible(item: AgendaItem, state: ViewState): Boolean {
        if (!item.info) {
            when (item.workKind) {
                WorkKind.SELF -> if (!state.chipMe) return false
                WorkKind.MY_AGENT -> if (!state.chipAgents) return false
                WorkKind.TEAM -> if (!state.chipTeam) return false
            }
        }
        if (state.category == CategoryFilter.WORK && item.category == Category.PERSONAL) return false
        if (state.category == CategoryFilter.PERSONAL && item.category == Category.WORK) return false
        return true
    }

    /** The first and last day of what is on screen. */
    fun range(state: ViewState, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> = when (state.view) {
        AgendaView.DAY -> state.date to state.date
        AgendaView.WEEK -> {
            val back = (state.date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
            val start = state.date.minusDays(back.toLong())
            start to start.plusDays(6)
        }
        AgendaView.MONTH -> {
            val start = state.date.withDayOfMonth(1)
            start to start.plusMonths(1).minusDays(1)
        }
    }

    private fun kindRank(kind: ItemKind) = when (kind) {
        ItemKind.MILESTONE -> 0
        ItemKind.GANTT_TASK -> 1
        ItemKind.TODO -> 2
        ItemKind.CARD -> 3
        ItemKind.EVENT -> 4
    }

    private val byKind = compareBy<AgendaItem>({ kindRank(it.kind) }, { it.title.lowercase() })
    private val byTitle = compareBy<AgendaItem> { it.title.lowercase() }
    private val byStart = Comparator<AgendaItem> { a, b ->
        when {
            a.allDay != b.allDay -> if (a.allDay) -1 else 1
            !a.allDay && a.start != b.start -> compareValues(a.start, b.start)
            else -> a.title.lowercase().compareTo(b.title.lowercase())
        }
    }

    fun timeLabel(item: AgendaItem, day: LocalDate, zone: ZoneId): TimeLabel {
        if (item.kind == ItemKind.EVENT) {
            if (item.allDay) {
                val end = item.endDate
                val start = item.startDate
                if (end != null && start != null && end.isAfter(start) && day == start) return TimeLabel(TimeKind.ALL_DAY_UNTIL, date = end)
                return TimeLabel(TimeKind.ALL_DAY)
            }
            val first = item.firstDay(zone)
            val last = item.lastDay(zone)
            val start = item.start?.atZone(zone)?.toLocalTime()
            val end = item.end?.atZone(zone)?.toLocalTime()
            if (first == last) {
                return if (item.end != null && item.start != null && item.end.isAfter(item.start)) {
                    TimeLabel(TimeKind.RANGE, start, end)
                } else {
                    TimeLabel(TimeKind.SINGLE, start)
                }
            }
            if (day == first) return TimeLabel(TimeKind.FROM, start)
            if (day == last) return TimeLabel(TimeKind.UNTIL, end)
            return TimeLabel(TimeKind.ALL_DAY)
        }
        if (item.kind == ItemKind.TODO && item.end != null && item.due == day) {
            return TimeLabel(TimeKind.DUE_AT, item.end.atZone(zone).toLocalTime())
        }
        if (item.kind == ItemKind.MILESTONE) return TimeLabel()
        if (item.due != null && item.due != day) return TimeLabel(TimeKind.DUE_ON, date = item.due)
        return TimeLabel()
    }

    /** The rows of one day. [header] puts the day's name first (Week and Month views). */
    private fun dayRows(
        out: BuildOutput,
        state: ViewState,
        today: LocalDate,
        zone: ZoneId,
        day: LocalDate,
        dayView: Boolean,
        header: Boolean
    ): List<AgendaRow> {
        val isToday = day == today
        val info = ArrayList<AgendaItem>()
        val timed = ArrayList<AgendaItem>()
        val due = ArrayList<AgendaItem>()
        val working = ArrayList<AgendaItem>()
        val late = ArrayList<AgendaItem>()
        val team = ArrayList<AgendaItem>()
        val attention = ArrayList<AgendaItem>()
        val teamKeys = HashSet<String>()

        for (item in out.items) {
            if (!visible(item, state)) continue
            if (item.problemOnly) {
                if (isToday) attention += item
                continue
            }
            if (item.workKind == WorkKind.TEAM) {
                val problem = item.problems.isNotEmpty()
                var activeToday = false
                if (item.hasDate) {
                    val first = item.firstDay(zone)
                    val last = item.lastDay(zone)
                    activeToday = first != null && last != null && !first.isAfter(day) && !day.isAfter(last)
                }
                val show = if (isToday) problem || (state.showAllTeam && activeToday) else state.showAllTeam && activeToday
                // A problem row without a date (not shared, overbooked) is a problem of today.
                if (show && teamKeys.add(item.key)) team += item
                continue
            }
            if (!item.hasDate) continue
            if (item.kind == ItemKind.EVENT) {
                val first = item.firstDay(zone)
                val last = item.lastDay(zone)
                if (first != null && last != null && !first.isAfter(day) && !day.isAfter(last)) {
                    (if (item.info) info else timed) += item
                }
                continue
            }
            if (item.done) continue
            val dueDay = item.due ?: item.startDate ?: continue
            val spanStart = item.startDate ?: dueDay
            if (isToday && item.late) {
                late += item
            } else if (dueDay == day) {
                due += item
            } else if (dayView && !spanStart.isAfter(day) && !day.isAfter(dueDay) && !item.late) {
                working += item
            }
        }

        info.sortWith(byStart)
        timed.sortWith(byStart)
        due.sortWith(byKind)
        working.sortWith(byKind)
        late.sortByDescending { it.lateDays }
        team.sortWith(byTitle)
        attention.sortWith(byTitle)

        val rows = ArrayList<AgendaRow>()
        fun add(list: List<AgendaItem>, inTeam: Boolean) {
            for (item in list) rows += AgendaRow.Item(item, day, inTeam, timeLabel(item, day, zone))
        }
        add(info, false)
        add(timed, false)
        if (late.isNotEmpty()) {
            rows += AgendaRow.Group(GroupKind.LATE, late.size)
            add(late, false)
        }
        if (due.isNotEmpty()) {
            rows += AgendaRow.Group(if (isToday) GroupKind.DUE_TODAY else GroupKind.DUE, due.size)
            add(due, false)
        }
        if (working.isNotEmpty()) {
            rows += AgendaRow.Group(GroupKind.IN_PROGRESS, working.size)
            add(working, false)
        }
        if (attention.isNotEmpty()) {
            rows += AgendaRow.Group(GroupKind.AGENT_PROBLEMS, attention.size)
            add(attention, false)
        }
        val teamSection = out.anyOversee && state.chipTeam
        if (teamSection && (isToday || team.isNotEmpty())) {
            if (team.isNotEmpty()) {
                rows += AgendaRow.Group(GroupKind.MY_TEAM, team.size)
                add(team, true)
            } else if (dayView) {
                rows += AgendaRow.Group(GroupKind.MY_TEAM, 0)
                rows += AgendaRow.Quiet(QuietKind.NO_TEAM_PROBLEMS)
            }
        }

        // The folded list of items without a date (Day view, today).
        if (dayView && isToday) {
            val noDate = out.items.filter {
                visible(it, state) && it.workKind != WorkKind.TEAM && !it.problemOnly && !it.hasDate && !it.done
            }.sortedWith(byKind)
            if (noDate.isNotEmpty()) {
                rows += AgendaRow.Group(GroupKind.NO_DATE, noDate.size, state.noDateExpanded)
                if (state.noDateExpanded) add(noDate, false)
            }
        }

        if (header) {
            if (rows.isEmpty()) return listOf(AgendaRow.DayHeader(day, isToday), AgendaRow.Quiet(QuietKind.NOTHING_PLANNED))
            return listOf<AgendaRow>(AgendaRow.DayHeader(day, isToday)) + rows
        }
        return rows
    }

    /** All rows of the chosen view. Empty when there is nothing to show at all (the screen shows an empty state). */
    fun rows(out: BuildOutput, state: ViewState, today: LocalDate, zone: ZoneId, firstDayOfWeek: DayOfWeek): List<AgendaRow> {
        val (first, last) = range(state, firstDayOfWeek)
        return when (state.view) {
            AgendaView.DAY -> dayRows(out, state, today, zone, first, dayView = true, header = false)
            AgendaView.WEEK -> generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
                .flatMap { dayRows(out, state, today, zone, it, dayView = false, header = true) }.toList()
            AgendaView.MONTH -> generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.flatMap { day ->
                val rows = dayRows(out, state, today, zone, day, dayView = false, header = false)
                if (rows.isEmpty()) emptyList() else listOf<AgendaRow>(AgendaRow.DayHeader(day, day == today)) + rows
            }.toList()
        }
    }

    /** True when the list has at least one item row. */
    fun hasItems(rows: List<AgendaRow>): Boolean = rows.any { it is AgendaRow.Item }

    /** Days of the month grid (six weeks from the start of the week of the 1st) that have an item of mine. */
    fun busyDays(out: BuildOutput, state: ViewState, zone: ZoneId): Set<LocalDate> {
        val days = HashSet<LocalDate>()
        for (item in out.items) {
            if (item.workKind == WorkKind.TEAM || !item.hasDate || item.info || item.problemOnly || !visible(item, state)) continue
            val first = item.firstDay(zone) ?: continue
            val last = (if (item.kind == ItemKind.EVENT) item.lastDay(zone) else item.due ?: first) ?: first
            var d = first
            var guard = 0
            while (!d.isAfter(last) && guard++ < MAX_SPAN_DAYS) {
                days += d
                d = d.plusDays(1)
            }
        }
        return days
    }

    private const val MAX_SPAN_DAYS = 400
}
