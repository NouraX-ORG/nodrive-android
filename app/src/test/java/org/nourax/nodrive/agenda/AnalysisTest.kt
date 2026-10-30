/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AgendaRow
import org.nourax.nodrive.agenda.logic.AgentOverbooked
import org.nourax.nodrive.agenda.logic.Analysis
import org.nourax.nodrive.agenda.logic.AnalysisBuilder
import org.nourax.nodrive.agenda.logic.BuildOutput
import org.nourax.nodrive.agenda.logic.ConflictBadge
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.DayOfWeek
import java.time.Instant

/** The analysis on the fixtures, and how the Day view shows it (workload bar, badges, Conflicts filter, overbooked agents). */
class AnalysisTest {
    private fun at(hour: Int) = Instant.parse("2026-10-28T%02d:00:00Z".format(hour))

    private fun event(key: String, from: Int, to: Int) = AgendaItem(
        key = key, kind = ItemKind.EVENT, home = Home.CALENDAR, title = key, timed = true, start = at(from), end = at(to)
    )

    private fun output(items: List<AgendaItem>, agents: List<Agent> = emptyList(), oversee: Boolean = false) = BuildOutput(
        items, emptyList(), emptyList(), false, oversee, false, false, false, false, "", false, agents, "", WorkingHours()
    )

    @Test
    fun theAnalysisOfAlisFixtureHasLoadAndTheChoicesOfHisGanttRows() {
        val input = loadAliAnswered()
        val out = AgendaBuilder.build(input)
        val analysis = AnalysisBuilder.build(out, input.registry, input.gantt, "ali", "Ali", 8.0, TODAY, UTC)
        assertTrue(analysis.days.isNotEmpty())
        val ganttRows = out.items.filter { it.kind == ItemKind.GANTT_TASK && !it.problemOnly && it.workKind != org.nourax.nodrive.agenda.model.WorkKind.TEAM }
        assertTrue(ganttRows.isNotEmpty())
        assertTrue(analysis.ganttChoices.isNotEmpty())
        assertTrue(ganttRows.map { it.key }.containsAll(analysis.ganttChoices.keys))
    }

    @Test
    fun anAgentOfAnotherOrganisationIsNeverCheckedOrNamed() {
        val registry = AgentRegistry.parse(
            (
                """{"schema":"nourax-agent-registry","version":1,"agents":[
                {"id":"AG-0001","name":"Writer-Bot","person":"ali","status":"active","organisation":"o",
                 "availability":{"timezone":"UTC","weekly":[{"days":["wed"],"from":"09:00","to":"17:00"}],"capacity_hours_per_week":8,"days_off":[]}},
                {"id":"AG-0002","name":"Design-Bot","person":"sara","status":"active","organisation":"o",
                 "availability":{"timezone":"UTC","weekly":[{"days":["wed"],"from":"09:00","to":"17:00"}],"capacity_hours_per_week":8,"days_off":[]}},
                {"id":"AG-0009","name":"Spy-Bot","person":"omar","status":"active","organisation":"other",
                 "availability":{"timezone":"UTC","weekly":[{"days":["wed"],"from":"09:00","to":"17:00"}],"capacity_hours_per_week":8,"days_off":[]}}]}"""
                ).toByteArray()
        )
        fun task(id: String) = AgendaItem(
            key = "t-$id", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", startDate = TODAY, endDate = TODAY, due = TODAY,
            agent = AgentRef(isSelf = false, id = id, name = "n"), workKind = WorkKind.MY_AGENT, agentWorkHours = 20.0
        )
        val items = listOf(task("AG-0001"), task("AG-0002"), task("AG-0009"))
        val ali = AnalysisBuilder.build(output(items), registry, GanttSnapshot(), "ali", "Ali", 8.0, TODAY, UTC)
        // my own agent and an agent of my own organisation are checked; the agent of the other organisation is not
        assertEquals(listOf("AG-0001", "AG-0002"), ali.agentOver[TODAY].orEmpty().map { it.agentId })
        // a team leader with no agents of their own has no organisation at all: no agent is named to them
        val leader = AnalysisBuilder.build(output(items), registry, GanttSnapshot(), "mara", "Mara", 8.0, TODAY, UTC)
        assertTrue(leader.agentOver.isEmpty())
    }

    @Test
    fun theConflictsFilterKeepsOnlyTheRowsWithABadge() {
        val a = event("a", 10, 11)
        val b = event("b", 10, 11)
        val calm = event("calm", 14, 15)
        val out = output(listOf(a, b, calm))
        val analysis = Analysis(badges = mapOf("a" to setOf(ConflictBadge.CLASH), "b" to setOf(ConflictBadge.CLASH)))
        val all = AgendaFilter.rows(out, ViewState(date = TODAY), TODAY, UTC, DayOfWeek.MONDAY, analysis)
        assertEquals(listOf("a", "b", "calm"), all.filterIsInstance<AgendaRow.Item>().map { it.item.key })
        assertEquals(setOf(ConflictBadge.CLASH), all.filterIsInstance<AgendaRow.Item>().first().badges)
        val only = AgendaFilter.rows(out, ViewState(date = TODAY, conflictsOnly = true), TODAY, UTC, DayOfWeek.MONDAY, analysis)
        assertEquals(listOf("a", "b"), only.filterIsInstance<AgendaRow.Item>().map { it.item.key })
    }

    @Test
    fun myAgentThatIsOverbookedShowsInMyDayAndTheAgentOfSomeoneIKManageShowsInMyTeam() {
        val mine = Agent("AG-0001", "Writer-Bot", "ali", "active", "o")
        val theirs = Agent("AG-0002", "Design-Bot", "sara", "active", "o")
        val analysis = Analysis(
            agentOver = mapOf(TODAY to listOf(AgentOverbooked("AG-0001", "Writer-Bot", TODAY), AgentOverbooked("AG-0002", "Design-Bot", TODAY)))
        )
        val out = output(emptyList(), agents = listOf(mine), oversee = true)
        val rows = AgendaFilter.rows(out, ViewState(date = TODAY), TODAY, UTC, DayOfWeek.MONDAY, analysis)
        val over = rows.filterIsInstance<AgendaRow.AgentOver>()
        assertEquals(listOf("Writer-Bot" to false, "Design-Bot" to true), over.map { it.agentName to it.team })
        // with "My agents" off, the agent of mine is not shown
        val quiet = AgendaFilter.rows(out, ViewState(date = TODAY, chipAgents = false), TODAY, UTC, DayOfWeek.MONDAY, analysis)
        assertEquals(listOf("Design-Bot"), quiet.filterIsInstance<AgendaRow.AgentOver>().map { it.agentName })
    }
}
