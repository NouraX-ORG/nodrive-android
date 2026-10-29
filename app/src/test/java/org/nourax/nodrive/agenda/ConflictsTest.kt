/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.logic.Analysis
import org.nourax.nodrive.agenda.logic.ConflictBadge
import org.nourax.nodrive.agenda.logic.Conflicts
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.Instant
import java.time.LocalDate

/** Clash, possible clash, deadline on a day off and the Conflicts filter (design 7, plan 2.7, 2.8). */
class ConflictsTest {
    private val pattern = WorkingHours() // Monday to Friday, 09:00-17:00

    // 2026-10-28 is a Wednesday
    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = Instant.parse("2026-10-%02dT%02d:%02d:00Z".format(day, hour, minute))

    private fun event(
        key: String, from: Instant, to: Instant, tentative: Boolean = false, transparent: Boolean = false, info: Boolean = false,
        agent: AgentRef = AgentRef.SELF, workKind: WorkKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT
    ) = AgendaItem(
        key = key, kind = ItemKind.EVENT, home = Home.CALENDAR, title = key, timed = true, start = from, end = to,
        tentative = tentative, transparent = transparent, info = info, agent = agent, workKind = workKind
    )

    private val bot = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot")

    @Test
    fun theDentistAndTheClientCallAtTheSameTimeClash() {
        val badges = Conflicts.clashes(listOf(event("dentist", at(28, 10), at(28, 11)), event("call", at(28, 10), at(28, 11))))
        assertEquals(ConflictBadge.CLASH, badges["dentist"])
        assertEquals(ConflictBadge.CLASH, badges["call"])
    }

    @Test
    fun anOverlapOfSomeMinutesClashesAndTouchingEventsDoNot() {
        val overlap = Conflicts.clashes(listOf(event("a", at(28, 10), at(28, 11)), event("b", at(28, 10, 30), at(28, 11, 30))))
        assertEquals(setOf("a", "b"), overlap.keys)
        val touching = Conflicts.clashes(listOf(event("a", at(28, 10), at(28, 11)), event("b", at(28, 11), at(28, 12))))
        assertTrue(touching.isEmpty())
    }

    @Test
    fun aTentativeOverlapIsAPossibleClash() {
        val badges = Conflicts.clashes(listOf(event("a", at(28, 10), at(28, 11)), event("lunch", at(28, 10), at(28, 11), tentative = true)))
        assertEquals(ConflictBadge.SOFT_CLASH, badges["a"])
        assertEquals(ConflictBadge.SOFT_CLASH, badges["lunch"])
    }

    @Test
    fun aRealClashIsNotLoweredByAPossibleOne() {
        val badges = Conflicts.clashes(
            listOf(event("a", at(28, 10), at(28, 11)), event("b", at(28, 10), at(28, 11)), event("t", at(28, 10, 30), at(28, 10, 45), tentative = true))
        )
        assertEquals(ConflictBadge.CLASH, badges["a"])
        assertEquals(ConflictBadge.CLASH, badges["b"])
        assertEquals(ConflictBadge.SOFT_CLASH, badges["t"])
    }

    @Test
    fun freeEventsInformationAndDeclinedOnesNeverClash() {
        val base = event("a", at(28, 10), at(28, 11))
        assertTrue(Conflicts.clashes(listOf(base, event("free", at(28, 10), at(28, 11), transparent = true))).isEmpty())
        assertTrue(Conflicts.clashes(listOf(base, event("holiday", at(28, 10), at(28, 11), info = true))).isEmpty())
        assertTrue(Conflicts.clashes(listOf(base, event("declined", at(28, 10), at(28, 11)).copy(declined = true))).isEmpty())
    }

    @Test
    fun anItemOfMyAgentNeverClashesWithMyOwn() {
        assertTrue(Conflicts.clashes(listOf(event("mine", at(28, 10), at(28, 11)), event("bots", at(28, 10), at(28, 11), agent = bot))).isEmpty())
        // two items of the same agent are the agent's matter (its own load), not a clash of mine
        assertTrue(Conflicts.clashes(listOf(event("b1", at(28, 10), at(28, 11), agent = bot), event("b2", at(28, 10), at(28, 11), agent = bot))).isEmpty())
    }

    @Test
    fun otherPeoplesRowsNeverClashWithMine() {
        val team = event("theirs", at(28, 10), at(28, 11), workKind = WorkKind.TEAM)
        assertTrue(Conflicts.clashes(listOf(event("mine", at(28, 10), at(28, 11)), team)).isEmpty())
    }

    @Test
    fun aCardWithRealStartAndEndTimesClashesWithAnEvent() {
        val card = AgendaItem(
            key = "deck:card:1", kind = ItemKind.CARD, home = Home.BOARD, title = "Call supplier",
            clashStart = at(28, 10, 30), clashEnd = at(28, 11, 30)
        )
        val badges = Conflicts.clashes(listOf(event("a", at(28, 10), at(28, 11)), card))
        assertEquals(setOf("a", "deck:card:1"), badges.keys)
        // a card with only a date has no time and never clashes
        assertTrue(Conflicts.clashes(listOf(event("a", at(28, 10), at(28, 11)), card.copy(clashStart = null, clashEnd = null))).isEmpty())
    }

    @Test
    fun aDeadlineOnASaturdayIsOnADayOff() {
        // 2026-10-31 is a Saturday
        val task = AgendaItem(key = "gantt:task:1", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", endDate = LocalDate.of(2026, 10, 31), due = LocalDate.of(2026, 10, 31))
        assertEquals(setOf("gantt:task:1"), Conflicts.deadlinesOnDaysOff(listOf(task), pattern, emptySet()))
        // the Friday before is fine
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(task.copy(endDate = LocalDate.of(2026, 10, 30), due = LocalDate.of(2026, 10, 30))), pattern, emptySet()).isEmpty())
    }

    @Test
    fun aDeadlineOnAHolidayOfNoGanttIsOnADayOff() {
        val monday = LocalDate.of(2026, 11, 2)
        val todo = AgendaItem(key = "cal:p/t.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "t", due = monday)
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(todo), pattern, emptySet()).isEmpty())
        assertEquals(setOf("cal:p/t.ics"), Conflicts.deadlinesOnDaysOff(listOf(todo), pattern, setOf(monday)))
    }

    @Test
    fun aDeadlineOnAnAllDayBusyEventIsOnADayOff() {
        val day = LocalDate.of(2026, 11, 3)
        val off = AgendaItem(key = "cal:w/off.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Day off", allDay = true, startDate = day, endDate = day)
        val card = AgendaItem(key = "deck:card:2", kind = ItemKind.CARD, home = Home.BOARD, title = "c", due = day)
        assertEquals(setOf("deck:card:2"), Conflicts.deadlinesOnDaysOff(listOf(off, card), pattern, emptySet()))
        // a free all-day event (a birthday, a holiday feed) is not a day off
        val info = off.copy(info = true)
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(info, card), pattern, emptySet()).isEmpty())
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(off.copy(transparent = true), card), pattern, emptySet()).isEmpty())
    }

    @Test
    fun aDoneItemAndOtherPeoplesItemsHaveNoDayOffBadge() {
        val saturday = LocalDate.of(2026, 10, 31)
        val task = AgendaItem(key = "k", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", endDate = saturday, due = saturday)
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(task.copy(done = true)), pattern, emptySet()).isEmpty())
        assertTrue(Conflicts.deadlinesOnDaysOff(listOf(task.copy(workKind = WorkKind.TEAM)), pattern, emptySet()).isEmpty())
    }

    @Test
    fun theWorkingPatternDecidesWhichDaysAreOff() {
        // a person who works Tuesday to Saturday
        val tuesdayToSaturday = WorkingHours(daysMask = 0b0111110)
        val saturday = LocalDate.of(2026, 10, 31)
        val monday = LocalDate.of(2026, 11, 2)
        val onSaturday = AgendaItem(key = "s", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", endDate = saturday, due = saturday)
        val onMonday = onSaturday.copy(key = "m", endDate = monday, due = monday)
        assertEquals(setOf("m"), Conflicts.deadlinesOnDaysOff(listOf(onSaturday, onMonday), tuesdayToSaturday, emptySet()))
    }

    @Test
    fun badgesCombineAndTheConflictsFilterKeepsTheRowsWithABadge() {
        val a = event("a", at(28, 10), at(28, 11))
        val b = event("b", at(28, 10), at(28, 11))
        val calm = event("calm", at(28, 14), at(28, 15))
        val blocked = AgendaItem(key = "blocked", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "b", blocked = true)
        val late = AgendaItem(key = "late", kind = ItemKind.TODO, home = Home.TASKS, title = "l", late = true)
        val items = listOf(a, b, calm, blocked, late)
        val analysis = Analysis(badges = Conflicts.badges(items, pattern, emptySet()))
        assertTrue(analysis.hasConflict(a))
        assertTrue(analysis.hasConflict(blocked))
        assertTrue(analysis.hasConflict(late))
        assertFalse(analysis.hasConflict(calm))
        assertNull(analysis.badges["calm"])
    }
}
