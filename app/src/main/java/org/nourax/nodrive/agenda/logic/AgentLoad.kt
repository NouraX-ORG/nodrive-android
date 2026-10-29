/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.registry.Agent
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An agent that has more to do on a day than its registry says it can (design 4.3, 7). */
data class AgentOverbooked(val agentId: String, val agentName: String, val date: LocalDate)

/**
 * The load of the agents (design 7): an agent's items count for the AGENT, against its registry availability and capacity, never
 * for the person. Capacity per working day is `capacity_hours_per_week` divided by the days of the weekly pattern; days off and days
 * the pattern does not list have no capacity at all. Load is the agent's own `work` on Gantt tasks, spread evenly over its working
 * days, plus the hours of its timed events.
 */
object AgentLoad {
    private val dayNames = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY
    )
    private const val MAX_SPAN = 400
    private const val EPSILON = 0.001
    private const val MINUTES_PER_HOUR = 60.0

    /** The weekdays of the agent's weekly pattern. */
    fun workingDays(agent: Agent): Set<DayOfWeek> =
        agent.weekly.flatMap { it.days }.mapNotNull { dayNames[it.trim().lowercase().take(3)] }.toSet()

    private fun zoneOf(agent: Agent, fallback: ZoneId): ZoneId = runCatching { ZoneId.of(agent.timezone) }.getOrDefault(fallback)

    fun isWorkingDay(agent: Agent, day: LocalDate): Boolean = day.dayOfWeek in workingDays(agent) && day.toString() !in agent.daysOff

    fun dailyCapacity(agent: Agent, day: LocalDate): Double {
        val days = workingDays(agent)
        if (agent.capacityHoursPerWeek <= 0.0 || days.isEmpty() || !isWorkingDay(agent, day)) return 0.0
        return agent.capacityHoursPerWeek / days.size
    }

    /** For every day: the agents that are overbooked on it. Only agents that have capacity data are checked. */
    fun overbooked(
        items: List<AgendaItem>,
        agents: Collection<Agent>,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate
    ): Map<LocalDate, List<AgentOverbooked>> {
        val out = LinkedHashMap<LocalDate, MutableList<AgentOverbooked>>()
        for (agent in agents) {
            if (agent.capacityHoursPerWeek <= 0.0 || workingDays(agent).isEmpty()) continue
            val mine = items.filter { it.agent.id == agent.id && !it.agent.isSelf && !it.problemOnly && !it.done }
            if (mine.isEmpty()) continue
            val load = HashMap<LocalDate, Double>()
            val agentZone = zoneOf(agent, zone)
            for (item in mine) {
                if (item.kind == ItemKind.EVENT && item.timed) {
                    val start = item.start ?: continue
                    val end = item.end?.takeIf { it.isAfter(start) } ?: continue
                    addEvent(load, start, end, agentZone)
                } else if (item.kind == ItemKind.GANTT_TASK && item.agentWorkHours > 0) {
                    val first = item.startDate ?: item.endDate ?: continue
                    val last = item.endDate ?: first
                    val days = ArrayList<LocalDate>()
                    var d = first
                    var guard = 0
                    while (!d.isAfter(last) && guard++ < MAX_SPAN) {
                        if (isWorkingDay(agent, d)) days += d
                        d = d.plusDays(1)
                    }
                    if (days.isEmpty()) continue
                    for (day in days) load[day] = (load[day] ?: 0.0) + item.agentWorkHours / days.size
                }
            }
            for ((day, hours) in load) {
                if (day.isBefore(from) || day.isAfter(to)) continue
                if (hours > dailyCapacity(agent, day) + EPSILON) out.getOrPut(day) { ArrayList() } += AgentOverbooked(agent.id, agent.name, day)
            }
        }
        return out
    }

    private fun addEvent(load: MutableMap<LocalDate, Double>, start: Instant, end: Instant, zone: ZoneId) {
        var day = start.atZone(zone).toLocalDate()
        val last = end.minusSeconds(1).atZone(zone).toLocalDate()
        var guard = 0
        while (!day.isAfter(last) && guard++ < MAX_SPAN) {
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
            val s = if (start.isAfter(dayStart)) start else dayStart
            val e = if (end.isBefore(dayEnd)) end else dayEnd
            if (s.isBefore(e)) load[day] = (load[day] ?: 0.0) + Duration.between(s, e).toMinutes() / MINUTES_PER_HOUR
            day = day.plusDays(1)
        }
    }
}
