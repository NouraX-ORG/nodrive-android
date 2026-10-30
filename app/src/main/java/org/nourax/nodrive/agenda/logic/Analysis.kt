/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.edit.GanttAgentChange
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.LocalDate
import java.time.ZoneId

/** Everything NoAgenda works out on the phone from the rows: the conflict kinds, the workload bar, the agents' load (design 7). */
data class Analysis(
    /** The badges of the rows, by key. */
    val badges: Map<String, Set<ConflictBadge>> = emptyMap(),
    /** Planned against free, by day (working days of the person's pattern, and any day with planned work). */
    val days: Map<LocalDate, DayLoad> = emptyMap(),
    /** The agents that have too much to do, by day. */
    val agentOver: Map<LocalDate, List<AgentOverbooked>> = emptyMap(),
    /** For each Gantt row of mine: Self and the agents that can be chosen. */
    val ganttChoices: Map<String, List<GanttAgentChange.Choice>> = emptyMap()
) {
    fun badgesOf(key: String): Set<ConflictBadge> = badges[key].orEmpty()

    /** A row that the Conflicts filter keeps. */
    fun hasConflict(item: AgendaItem): Boolean = badgesOf(item.key).isNotEmpty() || item.blocked || item.late

    companion object {
        val EMPTY = Analysis()
    }
}

object AnalysisBuilder {
    /** The window the load is worked out for: the sync window of the readers. */
    fun window(today: LocalDate): Pair<LocalDate, LocalDate> {
        val firstOfMonth = today.withDayOfMonth(1)
        return firstOfMonth.minusMonths(1) to firstOfMonth.plusMonths(13).minusDays(1)
    }

    fun build(
        output: BuildOutput,
        registry: AgentRegistry,
        gantt: GanttSnapshot,
        me: String,
        myName: String,
        hoursPerPt: Double,
        today: LocalDate,
        zone: ZoneId
    ): Analysis {
        val items = output.items
        val (from, to) = window(today)
        val badges = Conflicts.badges(items, output.workingHours, output.holidays)
        val days = Workload.compute(items, output.workingHours, output.holidays, zone, from, to)
        // Only the agents whose name I may see (my own, and those of my organisations: the core's mayNameAgent, design 4.1) are
        // checked: the My team row names the agent, and the name of another organisation's agent is never shown.
        val organisations = registry.organisationsOf(me)
        val agents = registry.agents.filter { !it.isRetired && (it.person.equals(me, ignoreCase = true) || it.organisation in organisations) }
        val over = AgentLoad.overbooked(items, agents, zone, from, to)
        return Analysis(badges, days, over, ganttChoices(items, registry, gantt, me, myName, hoursPerPt))
    }

    private fun ganttChoices(
        items: List<AgendaItem>,
        registry: AgentRegistry,
        gantt: GanttSnapshot,
        me: String,
        myName: String,
        hoursPerPt: Double
    ): Map<String, List<GanttAgentChange.Choice>> {
        if (!gantt.loaded || gantt.unavailable) return emptyMap()
        val ctx = GanttAgentChange.Context(me, myName, registry, hoursPerPt)
        val resources: List<JsonObject> = gantt.resources.objects()
        val out = HashMap<String, List<GanttAgentChange.Choice>>()
        for (item in items) {
            if (item.kind != ItemKind.GANTT_TASK || item.problemOnly || item.workKind == WorkKind.TEAM || item.taskId <= 0) continue
            val data = gantt.projectData[item.projectId] ?: continue
            val task = data.tasks.objects().firstOrNull { intOf(it.get("id")) == item.taskId } ?: continue
            val project = gantt.projects.objects().firstOrNull { intOf(it.get("id")) == item.projectId }
            val tasks = data.tasks.objects()
            out[item.key] = GanttAgentChange.choices(
                task, project, resources, ctx, GanttAgentChange.idsInProject(tasks), GanttAgentChange.idsNextToMe(tasks, resources, ctx)
            )
        }
        return out
    }
}
