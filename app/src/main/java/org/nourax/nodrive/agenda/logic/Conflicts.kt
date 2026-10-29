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
import java.time.Instant
import java.time.LocalDate

/** The badges of the conflict kinds that are computed on the phone (design 7). "Blocked" and "late" come from the home with the row. */
enum class ConflictBadge { CLASH, SOFT_CLASH, DEADLINE_ON_DAY_OFF }

/** Clash and deadline on a day off, computed from data the person can already see (design 6.4, 7). */
object Conflicts {
    private class Timed(val item: AgendaItem, val start: Instant, val end: Instant)

    private fun timedOf(item: AgendaItem): Timed? {
        // Only what the person does by hand. Events of an agent, of the team, for information, free or declined never clash.
        if (item.workKind != WorkKind.SELF || !item.agent.isSelf || item.info || item.problemOnly || item.declined || item.done) return null
        if (item.kind == ItemKind.EVENT) {
            if (!Busy.takesMyTime(item) || !item.timed) return null
            val start = item.start ?: return null
            val end = item.end ?: return null
            return if (end.isAfter(start)) Timed(item, start, end) else null
        }
        val start = item.clashStart ?: return null
        val end = item.clashEnd ?: return null
        return if (end.isAfter(start)) Timed(item, start, end) else null
    }

    /** Two timed items of mine that overlap: a clash; when one of them is tentative, a possible clash. */
    fun clashes(items: List<AgendaItem>): Map<String, ConflictBadge> {
        val timed = items.mapNotNull { timedOf(it) }.sortedBy { it.start }
        val out = HashMap<String, ConflictBadge>()
        val active = ArrayList<Timed>()
        for (current in timed) {
            active.removeAll { !it.end.isAfter(current.start) }
            for (other in active) {
                val badge = if (current.item.tentative || other.item.tentative) ConflictBadge.SOFT_CLASH else ConflictBadge.CLASH
                for (key in listOf(current.item.key, other.item.key)) {
                    // A real clash is not lowered to a possible one.
                    if (out[key] != ConflictBadge.CLASH) out[key] = badge
                }
            }
            active += current
        }
        return out
    }

    /** True when the day is not a working day of the pattern, is a holiday, or has an all-day busy event of mine. */
    fun isDayOff(day: LocalDate, pattern: WorkingHours, holidays: Set<LocalDate>, daysOff: Set<LocalDate>): Boolean =
        !Workload.isWorkingDay(day, pattern, holidays, daysOff)

    /** Items of mine (by hand) with a due or end date on a day off. */
    fun deadlinesOnDaysOff(items: List<AgendaItem>, pattern: WorkingHours, holidays: Set<LocalDate>): Set<String> {
        val daysOff = Busy.daysOff(items)
        val out = HashSet<String>()
        for (item in items) {
            if (item.kind == ItemKind.EVENT || item.workKind != WorkKind.SELF || item.problemOnly || item.done || item.info) continue
            val day = item.due ?: item.endDate ?: continue
            if (isDayOff(day, pattern, holidays, daysOff)) out += item.key
        }
        return out
    }

    /** Every badge of every item, by the item's key. */
    fun badges(items: List<AgendaItem>, pattern: WorkingHours, holidays: Set<LocalDate>): Map<String, Set<ConflictBadge>> {
        val out = HashMap<String, MutableSet<ConflictBadge>>()
        for ((key, badge) in clashes(items)) out.getOrPut(key) { HashSet() } += badge
        for (key in deadlinesOnDaysOff(items, pattern, holidays)) out.getOrPut(key) { HashSet() } += ConflictBadge.DEADLINE_ON_DAY_OFF
        return out
    }
}
