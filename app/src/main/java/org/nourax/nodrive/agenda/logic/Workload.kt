/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's load of the person: planned work against free time (design 7). */
data class DayLoad(
    val date: LocalDate,
    /** A working day of the person's pattern, not a holiday and not a day off. */
    val working: Boolean,
    val plannedHours: Double,
    val freeHours: Double,
    /** Active Gantt tasks of the person that have no `work` (they add no hours). */
    val noEstimate: Int,
    /** The hours of the working window that my own busy events take (the Timeline shades it). */
    val busyHours: Double = 0.0
) {
    /** Planned more than free: the day is overbooked. */
    val over: Boolean get() = plannedHours > freeHours + LOAD_EPSILON
}

private const val LOAD_EPSILON = 0.001

/** What makes a day a day off or a busy time of the person (design 7). Only items the person does by hand count. */
object Busy {
    /** An event of mine that takes my time: by hand, opaque, not just for information. Tentative ones count (they err on the safe side). */
    fun takesMyTime(item: AgendaItem): Boolean =
        item.kind == ItemKind.EVENT && item.workKind == WorkKind.SELF && item.agent.isSelf && !item.info && !item.transparent &&
            !item.declined && !item.problemOnly

    /** The days of an all-day busy event of mine. */
    fun daysOff(items: List<AgendaItem>): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        for (item in items) {
            if (!takesMyTime(item) || !item.allDay) continue
            val first = item.startDate ?: continue
            val last = item.endDate ?: first
            var d = first
            var guard = 0
            while (!d.isAfter(last) && guard++ < MAX_SPAN) {
                out += d
                d = d.plusDays(1)
            }
        }
        return out
    }

    private const val MAX_SPAN = 400
}

/** Planned against free per working day (design 7). */
object Workload {
    fun isWorkingDay(day: LocalDate, pattern: WorkingHours, holidays: Set<LocalDate>, daysOff: Set<LocalDate>): Boolean =
        (pattern.daysMask shr (day.dayOfWeek.value - 1)) and 1 == 1 && day !in holidays && day !in daysOff

    /**
     * The load of every day from [from] to [to]:
     *  - planned: the person's OWN share of work (`myWorkHours`) of every active Gantt task, spread evenly over the working days of the
     *    task (days of the pattern, not holidays, not days off). A task without `work` adds nothing ("no estimate");
     *  - free: the working hours minus the busy events of mine inside them. Events of my agents never reduce it.
     */
    fun compute(
        items: List<AgendaItem>,
        pattern: WorkingHours,
        holidays: Set<LocalDate>,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate
    ): Map<LocalDate, DayLoad> {
        val daysOff = Busy.daysOff(items)
        val busyByDay = HashMap<LocalDate, MutableList<Pair<Instant, Instant>>>()
        for (item in items) {
            if (!Busy.takesMyTime(item) || !item.timed) continue
            val start = item.start ?: continue
            val end = item.end?.takeIf { it.isAfter(start) } ?: continue
            var day = start.atZone(zone).toLocalDate()
            val last = end.minusSeconds(1).atZone(zone).toLocalDate()
            var guard = 0
            while (!day.isAfter(last) && guard++ < MAX_SPAN) {
                busyByDay.getOrPut(day) { ArrayList() } += start to end
                day = day.plusDays(1)
            }
        }
        val planned = HashMap<LocalDate, Double>()
        val noEstimate = HashMap<LocalDate, Int>()
        for (item in items) {
            if (item.kind != ItemKind.GANTT_TASK || item.problemOnly || item.done || item.workKind == WorkKind.TEAM) continue
            val first = item.startDate ?: item.endDate ?: continue
            val last = item.endDate ?: first
            val days = ArrayList<LocalDate>()
            var d = first
            var guard = 0
            while (!d.isAfter(last) && guard++ < MAX_SPAN) {
                if (isWorkingDay(d, pattern, holidays, daysOff)) days += d
                d = d.plusDays(1)
            }
            if (days.isEmpty()) continue
            if (item.myWorkHours < 0) {
                for (day in days) noEstimate[day] = (noEstimate[day] ?: 0) + 1
                continue
            }
            val perDay = item.myWorkHours / days.size
            for (day in days) planned[day] = (planned[day] ?: 0.0) + perDay
        }
        val workingMinutes = Duration.between(pattern.from, pattern.to).toMinutes().coerceAtLeast(0)
        val out = LinkedHashMap<LocalDate, DayLoad>()
        var day = from
        var guard = 0
        while (!day.isAfter(to) && guard++ < MAX_DAYS) {
            val working = isWorkingDay(day, pattern, holidays, daysOff)
            var busyToday = 0L
            val free = if (!working) {
                0.0
            } else {
                val windowStart = day.atTime(pattern.from).atZone(zone).toInstant()
                val windowEnd = day.atTime(pattern.to).atZone(zone).toInstant()
                busyToday = busyMinutes(busyByDay[day].orEmpty(), windowStart, windowEnd)
                (workingMinutes - busyToday).coerceAtLeast(0) / MINUTES_PER_HOUR
            }
            val plannedToday = planned[day] ?: 0.0
            if (working || plannedToday > 0.0) {
                out[day] = DayLoad(day, working, plannedToday, free, noEstimate[day] ?: 0, busyToday.coerceAtMost(workingMinutes) / MINUTES_PER_HOUR)
            }
            day = day.plusDays(1)
        }
        return out
    }

    /** The minutes of the window that are covered by at least one busy interval. */
    private fun busyMinutes(intervals: List<Pair<Instant, Instant>>, windowStart: Instant, windowEnd: Instant): Long {
        val clipped = intervals.mapNotNull { (a, b) ->
            val s = if (a.isAfter(windowStart)) a else windowStart
            val e = if (b.isBefore(windowEnd)) b else windowEnd
            if (s.isBefore(e)) s to e else null
        }.sortedBy { it.first }
        var total = 0L
        var curStart: Instant? = null
        var curEnd: Instant? = null
        for ((s, e) in clipped) {
            val ce = curEnd
            if (ce == null || s.isAfter(ce)) {
                val cs = curStart
                if (cs != null && ce != null) total += Duration.between(cs, ce).toMinutes()
                curStart = s
                curEnd = e
            } else if (e.isAfter(ce)) {
                curEnd = e
            }
        }
        val cs = curStart
        val ce = curEnd
        if (cs != null && ce != null) total += Duration.between(cs, ce).toMinutes()
        return total
    }

    private const val MAX_SPAN = 400
    private const val MAX_DAYS = 800
    private const val MINUTES_PER_HOUR = 60.0
}
