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
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AgendaRow
import org.nourax.nodrive.agenda.logic.Analysis
import org.nourax.nodrive.agenda.logic.DayLoad
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.logic.Workload
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Planned against free per working day (design 7, plan 2.7, 2.8). */
class WorkloadTest {
    private val pattern = WorkingHours() // Monday to Friday, 09:00-17:00 (8 hours)
    private val monday = LocalDate.of(2026, 10, 26)
    private val friday = LocalDate.of(2026, 10, 30)

    private fun task(key: String, from: LocalDate, to: LocalDate, hours: Double, workKind: WorkKind = WorkKind.SELF, agent: AgentRef = AgentRef.SELF) = AgendaItem(
        key = key, kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = key, startDate = from, endDate = to, due = to,
        myWorkHours = hours, workKind = workKind, agent = agent
    )

    private fun event(key: String, day: LocalDate, from: Int, to: Int, extra: (AgendaItem) -> AgendaItem = { it }) = extra(
        AgendaItem(
            key = key, kind = ItemKind.EVENT, home = Home.CALENDAR, title = key, timed = true,
            start = Instant.parse("${day}T%02d:00:00Z".format(from)), end = Instant.parse("${day}T%02d:00:00Z".format(to))
        )
    )

    private fun load(items: List<AgendaItem>, holidays: Set<LocalDate> = emptySet(), from: LocalDate = monday, to: LocalDate = friday.plusDays(3)) =
        Workload.compute(items, pattern, holidays, UTC, from, to)

    @Test
    fun aFiveDayTaskWithTenHoursIsTwoHoursADay() {
        val days = load(listOf(task("t", monday, friday, 10.0)))
        for (offset in 0L..4L) assertEquals(2.0, days.getValue(monday.plusDays(offset)).plannedHours, 0.0001)
        // the weekend has no working hours and no planned work
        assertNull(days[friday.plusDays(1)])
    }

    @Test
    fun theSpreadSkipsTheDaysOffOfThePattern() {
        // Friday to Monday: two working days, so 6 h become 3 h each; the weekend gets none
        val days = load(listOf(task("t", friday, friday.plusDays(3), 6.0)))
        assertEquals(3.0, days.getValue(friday).plannedHours, 0.0001)
        assertEquals(3.0, days.getValue(friday.plusDays(3)).plannedHours, 0.0001)
        assertNull(days[friday.plusDays(1)])
    }

    @Test
    fun aHolidayAndAnAllDayBusyEventAreNotWorkingDays() {
        val tuesday = monday.plusDays(1)
        val wednesday = monday.plusDays(2)
        val off = AgendaItem(key = "off", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "off", allDay = true, startDate = wednesday, endDate = wednesday)
        val days = load(listOf(task("t", monday, friday, 6.0), off), holidays = setOf(tuesday))
        // five days minus the holiday and the day off = three working days, 2 h each
        assertEquals(2.0, days.getValue(monday).plannedHours, 0.0001)
        assertNull("a holiday has no load row", days[tuesday])
        assertNull(days[wednesday])
        assertEquals(2.0, days.getValue(friday).plannedHours, 0.0001)
    }

    @Test
    fun sixHoursPlannedOnADayWithFourFreeIsOverbooked() {
        val day = monday
        val items = listOf(task("t", day, day, 6.0), event("meeting", day, 9, 13))
        val dayLoad = load(items).getValue(day)
        assertEquals(6.0, dayLoad.plannedHours, 0.0001)
        assertEquals(4.0, dayLoad.freeHours, 0.0001)
        assertTrue(dayLoad.over)
    }

    @Test
    fun freeTimeCountsOnlyBusyEventsInsideTheWorkingHoursAndOnlyOnce() {
        val day = monday
        val items = listOf(
            event("early", day, 7, 10), // 1 h of it is inside 09:00-17:00
            event("same", day, 9, 10), // overlaps the first: counted once
            event("late", day, 16, 19) // 1 h inside
        )
        val dayLoad = load(items).getValue(day)
        assertEquals(6.0, dayLoad.freeHours, 0.0001)
    }

    @Test
    fun freeEventsInformationAndMyAgentsEventsDoNotReduceFreeTime() {
        val day = monday
        val bot = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot")
        val items = listOf(
            event("free", day, 9, 12) { it.copy(transparent = true) },
            event("holidays", day, 9, 12) { it.copy(info = true) },
            event("bots", day, 9, 12) { it.copy(agent = bot, workKind = WorkKind.MY_AGENT) },
            event("theirs", day, 9, 12) { it.copy(workKind = WorkKind.TEAM) }
        )
        assertEquals(8.0, load(items).getValue(day).freeHours, 0.0001)
    }

    @Test
    fun aTentativeEventCountsAsBusySoTheWarningErrsOnTheSafeSide() {
        val dayLoad = load(listOf(event("maybe", monday, 9, 11) { it.copy(tentative = true) })).getValue(monday)
        assertEquals(6.0, dayLoad.freeHours, 0.0001)
    }

    @Test
    fun aTaskWithoutWorkAddsNoHoursAndIsCountedAsNoEstimate() {
        val days = load(listOf(task("t", monday, friday, -1.0)))
        assertEquals(0.0, days.getValue(monday).plannedHours, 0.0001)
        assertEquals(1, days.getValue(monday).noEstimate)
        assertFalse(days.getValue(monday).over)
    }

    @Test
    fun onlyMyOwnShareCountsForAnAgentTask() {
        // an agent does the job; the person's own planning person has 1 h (the review). The agent's hours never count for the person.
        val bot = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot")
        val reviewed = task("t", monday, monday, 1.0, workKind = WorkKind.MY_AGENT, agent = bot).copy(agentWorkHours = 6.0)
        assertEquals(1.0, load(listOf(reviewed)).getValue(monday).plannedHours, 0.0001)
    }

    @Test
    fun otherPeoplesTasksDoneTasksAndRowsThatOnlyReportAProblemAddNothing() {
        val items = listOf(
            task("theirs", monday, monday, 8.0, workKind = WorkKind.TEAM),
            task("done", monday, monday, 8.0).copy(done = true),
            task("problem", monday, monday, 8.0).copy(problemOnly = true)
        )
        assertEquals(0.0, load(items).getValue(monday).plannedHours, 0.0001)
    }

    @Test
    fun severalTasksOnOneDayAddUp() {
        val items = listOf(task("a", monday, monday, 3.0), task("b", monday, friday, 5.0))
        assertEquals(4.0, load(items).getValue(monday).plannedHours, 0.0001)
    }

    @Test
    fun theWorkingHoursOfThePersonDecideHowMuchIsFree() {
        val short = WorkingHours(31, java.time.LocalTime.of(10, 0), java.time.LocalTime.of(14, 0))
        val dayLoad = Workload.compute(emptyList(), short, emptySet(), UTC, monday, monday).getValue(monday)
        assertEquals(4.0, dayLoad.freeHours, 0.0001)
        assertTrue(dayLoad.working)
    }

    @Test
    fun theWorkloadBarAndTheConflictsFilterAreRowsOfTheDay() {
        val items = listOf(task("t", monday, monday, 6.0), event("meeting", monday, 9, 13))
        val analysis = Analysis(days = load(items))
        val rows = AgendaFilter.rows(
            org.nourax.nodrive.agenda.logic.BuildOutput(
                items, emptyList(), emptyList(), false, false, false, false, false, false, "", false, emptyList(), "", pattern
            ),
            ViewState(date = monday), monday, UTC, DayOfWeek.MONDAY, analysis
        )
        val bar = rows.filterIsInstance<AgendaRow.Workload>().single()
        assertEquals(DayLoad(monday, true, 6.0, 4.0, 0), bar.load)
        assertFalse(bar.thin)
        // the bar is the first row of the day
        assertTrue(rows.first() is AgendaRow.Workload)
        // a day without planned work has no bar
        val other = AgendaFilter.rows(
            org.nourax.nodrive.agenda.logic.BuildOutput(
                items, emptyList(), emptyList(), false, false, false, false, false, false, "", false, emptyList(), "", pattern
            ),
            ViewState(date = monday.plusDays(1)), monday.plusDays(1), UTC, DayOfWeek.MONDAY, analysis
        )
        assertTrue(other.none { it is AgendaRow.Workload })
    }
}
