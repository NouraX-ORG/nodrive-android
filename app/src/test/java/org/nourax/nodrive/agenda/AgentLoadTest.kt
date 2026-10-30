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
import org.nourax.nodrive.agenda.logic.AgentLoad
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentWeekly
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An agent's items count for the agent, against its registry availability and capacity, never for the person (design 7). */
class AgentLoadTest {
    private val monday = LocalDate.of(2026, 10, 26)
    private val friday = LocalDate.of(2026, 10, 30)
    private val saturday = LocalDate.of(2026, 10, 31)

    private fun agent(
        capacity: Double = 40.0,
        days: List<String> = listOf("mon", "tue", "wed", "thu", "fri"),
        daysOff: List<String> = emptyList(),
        timezone: String = "UTC",
        id: String = "AG-0001"
    ) = Agent(
        id = id, name = "Writer-Bot", person = "ali", status = "active", organisation = "o",
        timezone = timezone, weekly = listOf(AgentWeekly(days, "09:00", "17:00")), capacityHoursPerWeek = capacity, daysOff = daysOff
    )

    private fun ref(a: Agent) = AgentRef(isSelf = false, id = a.id, name = a.name)

    private fun task(a: Agent, from: LocalDate, to: LocalDate, hours: Double) = AgendaItem(
        key = "t-$from-$hours", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", startDate = from, endDate = to, due = to,
        agent = ref(a), workKind = WorkKind.MY_AGENT, agentWorkHours = hours
    )

    private fun event(a: Agent, day: LocalDate, from: Int, to: Int) = AgendaItem(
        key = "e-$day-$from", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "e", timed = true,
        start = Instant.parse("${day}T%02d:00:00Z".format(from)), end = Instant.parse("${day}T%02d:00:00Z".format(to)),
        agent = ref(a), workKind = WorkKind.MY_AGENT
    )

    private fun over(items: List<AgendaItem>, a: Agent, zone: ZoneId = UTC) = AgentLoad.overbooked(items, listOf(a), zone, monday, friday.plusDays(7))

    @Test
    fun theDailyCapacityIsTheWeeklyCapacityOverTheDaysOfThePattern() {
        val a = agent(capacity = 40.0)
        assertEquals(8.0, AgentLoad.dailyCapacity(a, monday), 0.0001)
        assertEquals(0.0, AgentLoad.dailyCapacity(a, saturday), 0.0001)
        assertEquals(10.0, AgentLoad.dailyCapacity(agent(capacity = 20.0, days = listOf("tue", "thu")), monday.plusDays(1)), 0.0001)
    }

    @Test
    fun twelveHoursOnADayOfAnAgentWithEightIsOverbooked() {
        val a = agent()
        val result = over(listOf(task(a, monday, monday, 12.0)), a)
        assertEquals(listOf(monday), result.keys.toList())
        assertEquals("Writer-Bot", result.getValue(monday).single().agentName)
    }

    @Test
    fun workIsSpreadOverTheAgentsWorkingDaysSoAWeekOfFortyHoursIsFine() {
        val a = agent()
        assertTrue(over(listOf(task(a, monday, friday, 40.0)), a).isEmpty())
        // 50 hours in the same week is eight over a day each
        assertEquals(5, over(listOf(task(a, monday, friday, 50.0)), a).size)
    }

    @Test
    fun theWeekendIsNotAWorkingDayOfTheAgentSoNothingLandsThere() {
        val a = agent()
        // Friday to Sunday: the work is spread over Friday only
        val result = over(listOf(task(a, friday, friday.plusDays(2), 12.0)), a)
        assertEquals(listOf(friday), result.keys.toList())
    }

    @Test
    fun daysOffOfTheAgentAreLeftOutOfTheSpread() {
        val a = agent(daysOff = listOf(monday.toString()))
        // Monday is a day off: 16 h over Tuesday..Friday are 4 h a day, fine; the same 16 h on Monday alone have no day to go to
        assertTrue(over(listOf(task(a, monday, friday, 16.0)), a).isEmpty())
    }

    @Test
    fun anEventOnADayTheAgentDoesNotWorkIsOverbooked() {
        val a = agent()
        val result = over(listOf(event(a, saturday, 10, 11)), a)
        assertEquals(listOf(saturday), result.keys.toList())
    }

    @Test
    fun theHoursOfTheAgentsEventsAddToItsWork() {
        val a = agent()
        // 6 h of work on Monday plus a 3 h event: 9 h against 8 h
        val items = listOf(task(a, monday, monday, 6.0), event(a, monday, 10, 13))
        assertEquals(listOf(monday), over(items, a).keys.toList())
        // with a 2 h event it fits
        assertTrue(over(listOf(task(a, monday, monday, 6.0), event(a, monday, 10, 12)), a).isEmpty())
    }

    @Test
    fun theAgentsTimeZoneDecidesWhichDayAnEventBelongsTo() {
        val a = agent(timezone = "Asia/Tokyo", days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"), capacity = 7.0)
        // 23:00-24:00 UTC on Monday is 08:00-09:00 on Tuesday in Tokyo: 1 h on Tuesday against 1 h a day (7 h over 7 days) is fine,
        // but 2 hours do not fit
        val tuesday = monday.plusDays(1)
        val one = AgendaItem(
            key = "e1", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "e", timed = true,
            start = Instant.parse("${monday}T23:00:00Z"), end = Instant.parse("${tuesday}T00:00:00Z"), agent = ref(a), workKind = WorkKind.MY_AGENT
        )
        assertTrue(over(listOf(one), a).isEmpty())
        val two = one.copy(key = "e2", end = Instant.parse("${tuesday}T01:00:00Z"))
        assertEquals(listOf(tuesday), over(listOf(two), a).keys.toList())
    }

    @Test
    fun itemsOfMyOwnAndOfOtherAgentsAreNotCountedForThisAgent() {
        val a = agent()
        val other = agent(id = "AG-0002")
        val mine = AgendaItem(key = "m", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "m", startDate = monday, endDate = monday, agentWorkHours = 20.0)
        assertTrue(over(listOf(mine, task(other, monday, monday, 20.0)), a).isEmpty())
    }

    @Test
    fun anAgentWithoutCapacityDataIsNotChecked() {
        val a = agent(capacity = 0.0)
        assertTrue(over(listOf(task(a, monday, monday, 100.0)), a).isEmpty())
        assertFalse(AgentLoad.isWorkingDay(agent(daysOff = listOf(monday.toString())), monday))
    }

    @Test
    fun aTimedCardOfTheAgentCountsLikeATimedEvent() {
        val a = agent()
        val card = AgendaItem(
            key = "c", kind = ItemKind.CARD, home = Home.BOARD, title = "c", agent = ref(a), workKind = WorkKind.MY_AGENT,
            clashStart = Instant.parse("${monday}T09:00:00Z"), clashEnd = Instant.parse("${monday}T18:00:00Z")
        )
        // 9 h against 8 h a day
        assertEquals(listOf(monday), over(listOf(card), a).keys.toList())
        // 2 h fit
        assertTrue(over(listOf(card.copy(clashEnd = Instant.parse("${monday}T11:00:00Z"))), a).isEmpty())
        // a card without times adds nothing
        assertTrue(over(listOf(card.copy(clashStart = null, clashEnd = null)), a).isEmpty())
        // it adds to the other work of the day: 6 h of tasks and a 3 h card
        val three = card.copy(clashEnd = Instant.parse("${monday}T12:00:00Z"))
        assertEquals(listOf(monday), over(listOf(task(a, monday, monday, 6.0), three), a).keys.toList())
        // a card of another agent is not counted
        assertTrue(over(listOf(card.copy(agent = ref(agent(id = "AG-0002")))), a).isEmpty())
    }

    @Test
    fun aDoneItemIsNotLoad() {
        val a = agent()
        assertTrue(over(listOf(task(a, monday, monday, 20.0).copy(done = true)), a).isEmpty())
    }
}
