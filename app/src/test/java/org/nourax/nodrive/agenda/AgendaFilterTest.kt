/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AgendaRow
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.BuildOutput
import org.nourax.nodrive.agenda.logic.CategoryFilter
import org.nourax.nodrive.agenda.logic.GroupKind
import org.nourax.nodrive.agenda.logic.QuietKind
import org.nourax.nodrive.agenda.logic.TimeKind
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.DayOfWeek
import java.time.LocalDate

/** Day, Week and Month lists, the chips, the All/Work/Personal switch, My team and "No date" (design 6.0, 6.3, 8). */
class AgendaFilterTest {
    private val monday = DayOfWeek.MONDAY

    private fun rows(out: BuildOutput, state: ViewState) = AgendaFilter.rows(out, state, TODAY, UTC, monday)

    private fun ali() = AgendaBuilder.build(loadAliAnswered())

    private fun mara() = AgendaBuilder.build(loadScenario("mara"))

    private fun items(rows: List<AgendaRow>) = rows.filterIsInstance<AgendaRow.Item>()

    private fun groups(rows: List<AgendaRow>) = rows.filterIsInstance<AgendaRow.Group>().map { it.kind }

    @Test
    fun theDayListHasTimedItemsInTimeOrder() {
        val list = items(rows(ali(), ViewState(date = TODAY)))
        val dentist = list.indexOfFirst { it.item.title == "Dentist" }
        val call = list.indexOfFirst { it.item.title == "Client call" }
        val meeting = list.indexOfFirst { it.item.title == "Planning meeting" }
        assertTrue(dentist >= 0 && call >= 0 && meeting >= 0)
        // the same start: by title
        assertTrue(call < dentist)
        assertTrue(dentist < meeting)
        val label = list[dentist].time
        assertEquals(TimeKind.RANGE, label.kind)
        assertEquals(10, label.time1!!.hour)
        assertEquals(11, label.time2!!.hour)
    }

    @Test
    fun lateAndDueTodayHaveTheirOwnSections() {
        val rows = rows(ali(), ViewState(date = TODAY))
        val kinds = groups(rows)
        assertTrue(GroupKind.LATE in kinds)
        assertTrue(GroupKind.DUE_TODAY in kinds)
        // late items are only listed today
        assertFalse(GroupKind.LATE in groups(rows(ali(), ViewState(date = TODAY.plusDays(1)))))
        val late = rows.dropWhile { !(it is AgendaRow.Group && it.kind == GroupKind.LATE) }.drop(1).takeWhile { it is AgendaRow.Item }
        assertTrue(late.isNotEmpty())
        // the longest late first
        val days = late.map { (it as AgendaRow.Item).item.lateDays }
        assertEquals(days.sortedDescending(), days)
    }

    @Test
    fun noDateIsFoldedAndOpensOnRequest() {
        val out = ali()
        val folded = rows(out, ViewState(date = TODAY))
        val noDate = folded.filterIsInstance<AgendaRow.Group>().first { it.kind == GroupKind.NO_DATE }
        assertTrue(noDate.count > 0)
        assertFalse(noDate.expanded)
        assertTrue(folded.dropWhile { !(it is AgendaRow.Group && it.kind == GroupKind.NO_DATE) }.drop(1).isEmpty())
        val open = rows(out, ViewState(date = TODAY, noDateExpanded = true))
        val opened = open.dropWhile { !(it is AgendaRow.Group && it.kind == GroupKind.NO_DATE) }.drop(1)
        assertEquals(noDate.count, opened.size)
        assertTrue(opened.all { it is AgendaRow.Item && !it.item.hasDate })
        // it only shows on today
        assertFalse(GroupKind.NO_DATE in groups(rows(out, ViewState(date = TODAY.plusDays(1)))))
    }

    @Test
    fun theChipsFilterTheThreeKindsOfWork() {
        val out = ali()
        val all = items(rows(out, ViewState(date = TODAY)))
        assertTrue(all.any { it.item.workKind == WorkKind.SELF })
        assertTrue(all.any { it.item.workKind == WorkKind.MY_AGENT })
        val noMe = items(rows(out, ViewState(date = TODAY, chipMe = false)))
        assertTrue(noMe.none { it.item.workKind == WorkKind.SELF && !it.item.info })
        assertTrue(noMe.any { it.item.workKind == WorkKind.MY_AGENT })
        val noAgents = items(rows(out, ViewState(date = TODAY, chipAgents = false)))
        assertTrue(noAgents.none { it.item.workKind == WorkKind.MY_AGENT })
        // information rows (holidays) are not work: they stay whatever the chips say
        val nothing = items(rows(out, ViewState(date = TODAY, chipMe = false, chipAgents = false, chipTeam = false)))
        assertTrue(nothing.all { it.item.info })
    }

    @Test
    fun theSwitchSeparatesWorkFromPersonal() {
        val out = ali()
        val work = items(rows(out, ViewState(date = TODAY, category = CategoryFilter.WORK)))
        assertTrue(work.any { it.item.title == "Client call" })
        assertTrue(work.none { it.item.title == "Dentist" })
        val personal = items(rows(out, ViewState(date = TODAY, category = CategoryFilter.PERSONAL)))
        assertTrue(personal.any { it.item.title == "Dentist" })
        assertTrue(personal.none { it.item.title == "Client call" })
    }

    @Test
    fun myTeamShowsProblemsOnlyAndTheSwitchShowsEverything() {
        val out = mara()
        assertTrue(out.anyOversee)
        val today = rows(out, ViewState(date = TODAY))
        val team = today.dropWhile { !(it is AgendaRow.Group && it.kind == GroupKind.MY_TEAM) }.drop(1).takeWhile { it is AgendaRow.Item }
        assertTrue(team.isNotEmpty())
        assertTrue(team.all { (it as AgendaRow.Item).inTeam && it.item.problems.isNotEmpty() })
        // no problem at all: today's section says so (Day view)
        val quiet = rows(out.copy(items = emptyList()), ViewState(date = TODAY))
        assertTrue(quiet.contains(AgendaRow.Quiet(QuietKind.NO_TEAM_PROBLEMS)))
        // another day has no team section unless there is something in it
        assertFalse(GroupKind.MY_TEAM in groups(rows(out, ViewState(date = TODAY.plusDays(100)))))
        // the switch lists every team task that is active that day
        val every = rows(out, ViewState(date = TODAY, showAllTeam = true))
        val all = every.dropWhile { !(it is AgendaRow.Group && it.kind == GroupKind.MY_TEAM) }.drop(1).takeWhile { it is AgendaRow.Item }
        assertTrue(all.size >= team.size)
        // switched off with the chip
        assertFalse(GroupKind.MY_TEAM in groups(rows(out, ViewState(date = TODAY, chipTeam = false))))
    }

    @Test
    fun noTeamSectionWithoutProjectsOrBoardsIRun() {
        val out = ali()
        assertFalse(out.anyOversee)
        assertFalse(GroupKind.MY_TEAM in groups(rows(out, ViewState(date = TODAY))))
    }

    @Test
    fun theRowsThatOnlyReportAProblemAreListedAsProblemsWithMyAgents() {
        val rows = rows(ali(), ViewState(date = TODAY))
        assertTrue(GroupKind.AGENT_PROBLEMS in groups(rows))
        val list = items(rows).filter { it.item.problemOnly }
        assertTrue(list.isNotEmpty())
        // never on another day
        assertTrue(items(rows(ali(), ViewState(date = TODAY.plusDays(1)))).none { it.item.problemOnly })
    }

    @Test
    fun theWeekAndTheMonthAreListsGroupedByDay() {
        val out = ali()
        val week = rows(out, ViewState(view = AgendaView.WEEK, date = TODAY))
        val headers = week.filterIsInstance<AgendaRow.DayHeader>()
        assertEquals(7, headers.size)
        assertEquals(LocalDate.of(2026, 10, 26), headers.first().date)
        assertEquals(LocalDate.of(2026, 11, 1), headers.last().date)
        assertTrue(headers.single { it.today }.date == TODAY)
        val month = rows(out, ViewState(view = AgendaView.MONTH, date = TODAY))
        val monthHeaders = month.filterIsInstance<AgendaRow.DayHeader>().map { it.date }
        // only days with something in them
        assertTrue(monthHeaders.isNotEmpty() && monthHeaders.size < 31)
        assertTrue(monthHeaders.all { it.month == TODAY.month })
        assertEquals(monthHeaders.sorted(), monthHeaders)
    }

    @Test
    fun anEmptyWeekDoesNotRepeatNothingPlannedEveryDay() {
        val out = ali()
        val far = ViewState(view = AgendaView.WEEK, date = LocalDate.of(2031, 1, 8))
        val list = rows(out, far)
        assertFalse(AgendaFilter.hasItems(list))
    }

    @Test
    fun rangesFollowTheWeekStartOfTheLocale() {
        val state = ViewState(view = AgendaView.WEEK, date = TODAY)
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 11, 1), AgendaFilter.range(state, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 10, 31), AgendaFilter.range(state, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 10, 24) to LocalDate.of(2026, 10, 30), AgendaFilter.range(state, DayOfWeek.SATURDAY))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), AgendaFilter.range(state.copy(view = AgendaView.MONTH), monday))
        assertEquals(TODAY to TODAY, AgendaFilter.range(state.copy(view = AgendaView.DAY), monday))
    }

    @Test
    fun anAllDayEventsLabelsAreClear() {
        val out = ali()
        val dayOff = out.items.byKey("cal:work/dayoff.ics")!!
        assertEquals(TimeKind.ALL_DAY, AgendaFilter.timeLabel(dayOff, LocalDate.of(2026, 10, 30), UTC).kind)
        // a to-do with a due date on another day says when
        val todo = out.items.byKey("cal:personal/td1.ics")!!
        assertEquals(TimeKind.DUE_ON, AgendaFilter.timeLabel(todo, TODAY, UTC).kind)
    }

    @Test
    fun theMonthGridMarksTheDaysWithItems() {
        val out = ali()
        val busy = AgendaFilter.busyDays(out, ViewState(view = AgendaView.MONTH, date = TODAY), UTC)
        assertTrue(TODAY in busy)
        assertFalse(LocalDate.of(2026, 10, 3) in busy)
    }
}
