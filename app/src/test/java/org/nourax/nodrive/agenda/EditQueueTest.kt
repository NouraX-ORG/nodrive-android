/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.edit.PendingOverlay
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The offline queue as the person meets it: a change is on screen at once, marked "Waiting to send", goes out when the phone is
 * online, is kept as a conflict until a tap, and never runs twice at the same time (design 9, plan 2.6, 2.8).
 */
class EditQueueTest {
    private class MutableClock(var instant: Instant) : AgendaClock {
        override fun now(): Instant = instant

        override fun zone(): ZoneId = UTC
    }

    private val account = "ali@https://drive.example.test"
    private val callHref = "/remote.php/dav/calendars/ali/work/call.ics"

    private class Fixture(val http: FakeHttp, val store: InMemoryEditStore, val agenda: AccountAgenda)

    private fun fixture(): Fixture {
        val http = FakeHttp().also { routeScenario(it, "ali") }
        val store = InMemoryEditStore()
        return Fixture(http, store, AccountAgenda(account, http, InMemoryAgendaStore(), MutableClock(NOW), editStore = store))
    }

    private fun Fixture.titleCommand(title: String): EditCommand {
        val snapshot = agenda.refresh(manual = true)
        val item = snapshot.build.items.byTitle("Client call")!!
        assertEquals("\"e2\"", item.version)
        return EditCommands.event(item, EventForm.of(item, UTC).copy(title = title), UTC, snapshot.build.myAgents)!!
    }

    private fun FakeHttp.serverHas(text: String) {
        route("GET", callHref, 200, text)
        route("PUT", callHref, 204, "")
    }

    @Test
    fun aChangeWithoutANetworkIsOnScreenAtOnceMarkedWaitingAndGoesOutWhenTheNetworkIsBack() {
        val f = fixture()
        val command = f.titleCommand("Client call moved")
        f.http.offline = true
        val offline = f.agenda.submit(command)
        // on screen at once, with the change, and marked
        val row = offline.build.items.first { it.key == command.itemKey }
        assertEquals("Client call moved", row.title)
        assertTrue(row.pending)
        assertEquals(1, offline.edit.pending.size)
        assertTrue(offline.edit.notice!!.queued)
        assertTrue(offline.edit.conflicts.isEmpty())

        f.http.offline = false
        f.http.serverHas(sampleEvent("ci-call"))
        f.http.requests.clear()
        val online = f.agenda.sendPending(manual = true)
        assertTrue(online.edit.pending.isEmpty())
        val put = f.http.requests.single { it.method == "PUT" }
        assertTrue(put.body.contains("SUMMARY:Client call moved"))
        assertEquals("\"e2\"", put.headers["If-Match"])
        assertTrue("the home is read again after the write", f.http.requests.any { it.method == "REPORT" })
        assertFalse(online.build.items.any { it.pending })
    }

    @Test
    fun aRefreshSendsWhatWaitsBeforeItReadsAnything() {
        val f = fixture()
        val command = f.titleCommand("Moved")
        f.http.offline = true
        f.agenda.submit(command)
        f.http.offline = false
        f.http.serverHas(sampleEvent("ci-call"))
        f.http.requests.clear()
        val state = f.agenda.refresh(manual = true)
        val order = f.http.requests.map { it.method }
        assertTrue("the write comes before the first read of a home", order.indexOf("PUT") in 0 until order.indexOf("REPORT"))
        assertTrue(state.edit.pending.isEmpty())
    }

    @Test
    fun theChangeOfAnItemIsKeptAsAConflictUntilTheTap() {
        val f = fixture()
        val command = f.titleCommand("Mine")
        f.http.serverHas(sampleEvent("ci-call"))
        f.http.route("PUT", callHref, 412, "")
        val state = f.agenda.submit(command)
        assertTrue(state.edit.pending.isEmpty())
        val conflict = state.edit.conflicts.single()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, conflict.reason)
        assertTrue(state.edit.notice!!.conflict)
        // the other change is what is shown: the row is not changed, nor marked
        val row = state.build.items.byTitle("Client call")!!
        assertFalse(row.pending)
        // nothing happens by itself: a refresh does not drop it, and does not send it again
        f.http.requests.clear()
        val later = f.agenda.refresh(manual = true)
        assertEquals(1, later.edit.conflicts.size)
        assertTrue(f.http.requests.none { it.method == "PUT" })
    }

    @Test
    fun applyMyChangeAgainSendsItOnTopOfTheNewVersion() {
        val f = fixture()
        val command = f.titleCommand("Mine")
        f.http.serverHas(sampleEvent("ci-call"))
        f.http.route("PUT", callHref, 412, "")
        val state = f.agenda.submit(command)
        f.http.route("PUT", callHref, 204, "")
        f.http.requests.clear()
        val after = f.agenda.resolveConflict(state.edit.conflicts.single().id, applyAgain = true)
        assertTrue(after.edit.conflicts.isEmpty())
        assertTrue(after.edit.pending.isEmpty())
        // one fresh read, then one write (a last read follows to learn the new version)
        assertEquals(listOf("GET", "PUT"), f.http.requests.filter { it.path.endsWith("call.ics") }.map { it.method }.take(2))
        assertTrue(f.http.requests.single { it.method == "PUT" }.body.contains("SUMMARY:Mine"))
    }

    @Test
    fun keepTheOtherChangeLeavesNothingAndSendsNothing() {
        val f = fixture()
        val command = f.titleCommand("Mine")
        f.http.serverHas(sampleEvent("ci-call"))
        f.http.route("PUT", callHref, 412, "")
        val state = f.agenda.submit(command)
        f.http.requests.clear()
        val after = f.agenda.resolveConflict(state.edit.conflicts.single().id, applyAgain = false)
        assertTrue(after.edit.conflicts.isEmpty())
        assertTrue(after.edit.pending.isEmpty())
        assertTrue(f.http.requests.none { it.method == "PUT" })
    }

    @Test
    fun theUnsentChangesAreForgottenWithTheAccount() {
        val f = fixture()
        val command = f.titleCommand("Mine")
        f.http.offline = true
        f.agenda.submit(command)
        assertEquals(1, f.store.commands(account).size)
        f.store.forget(account)
        assertTrue(f.store.commands(account).isEmpty())
        assertTrue(f.store.conflicts(account).isEmpty())
    }

    @Test
    fun onlyOneSendRunsAtATime() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val calls = AtomicInteger()
        val real = fixture().agenda
        val engine = object : AgendaEngine {
            override fun snapshot() = real.snapshot()

            override fun refresh(manual: Boolean) = real.snapshot()

            override fun updateSettings(change: (SourceSettings) -> SourceSettings) = real.updateSettings(change)

            override fun submit(command: EditCommand): AgendaSnapshot {
                calls.incrementAndGet()
                maxRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                entered.complete(Unit)
                runBlocking { release.await() }
                running.decrementAndGet()
                return real.snapshot()
            }
        }
        val repository = AgendaRepository(engine)
        val command = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = "k")
        runBlocking {
            val first = async(Dispatchers.Default) { repository.submit(command) }
            entered.await()
            val second = async(Dispatchers.Default) { repository.submit(command) }
            release.complete(Unit)
            awaitAll(first, second)
        }
        assertEquals(2, calls.get())
        assertEquals(1, maxRunning.get())
    }

    @Test
    fun commandsAreStoredInTheOrderTheyWereMadeAndKeepTheirIds() {
        val store = InMemoryEditStore()
        val a = store.add(EditCommand(account = account, itemKey = "a"))
        val b = store.add(EditCommand(account = account, itemKey = "b"))
        val c = store.add(EditCommand(account = "other", itemKey = "c"))
        assertEquals(listOf("a", "b"), store.commands(account).map { it.itemKey })
        assertTrue(a.id < b.id)
        assertEquals(listOf("c"), store.commands("other").map { it.itemKey })
        store.remove(account, a.id)
        assertEquals(listOf("b"), store.commands(account).map { it.itemKey })
        assertTrue(c.id > b.id)
    }

    // ---- the overlay: the change is on screen before the server has it ------------------------------------------------

    private val registry = AgentRegistry.parse(Fx.bytes("agent-registry.json"))
    private val bot = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot")

    private fun overlay(items: List<AgendaItem>, vararg commands: EditCommand) = PendingOverlay.apply(items, commands.toList(), registry, TODAY, UTC)

    private val event = AgendaItem(
        key = "cal:work/call.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Client call", timed = true,
        start = Instant.parse("2026-10-28T10:00:00Z"), end = Instant.parse("2026-10-28T11:00:00Z")
    )
    private val todo = AgendaItem(key = "cal:p/t.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Renew", due = LocalDate.of(2026, 11, 1), endDate = LocalDate.of(2026, 11, 1))

    @Test
    fun theOverlayChangesTitleTimeAndAgentAndMarksTheRow() {
        val moved = Instant.parse("2026-10-29T09:00:00Z")
        val command = EditCommand(
            kind = CommandKind.CAL_PATCH, itemKey = event.key,
            change = mapOf(EditKeys.SUMMARY to "Moved", EditKeys.START to EditKeys.moment(moved), EditKeys.END to EditKeys.moment(moved.plusSeconds(1800)), EditKeys.AGENT to "AG-0001")
        )
        val row = overlay(listOf(event), command).single()
        assertEquals("Moved", row.title)
        assertEquals(moved, row.start)
        assertEquals(moved.plusSeconds(1800), row.end)
        assertEquals("Writer-Bot", row.agent.name)
        assertEquals(WorkKind.MY_AGENT, row.workKind)
        assertTrue(row.pending)
        // the rows that are not changed are the same objects
        val other = event.copy(key = "x")
        assertTrue(overlay(listOf(event, other), command)[1] === other)
        // Self puts the agent away again
        val back = overlay(listOf(event.copy(agent = bot, workKind = WorkKind.MY_AGENT)), EditCommand(kind = CommandKind.CAL_PATCH, itemKey = event.key, change = mapOf(EditKeys.AGENT to EditKeys.SELF))).single()
        assertTrue(back.agent.isSelf)
        assertEquals(WorkKind.SELF, back.workKind)
    }

    @Test
    fun aDoneItemAndADeletedItemLeaveTheLists() {
        val done = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = todo.key, change = mapOf(EditKeys.DONE to "true"))
        assertTrue(overlay(listOf(todo), done).isEmpty())
        val delete = EditCommand(kind = CommandKind.CAL_DELETE, itemKey = event.key)
        assertTrue(overlay(listOf(event), delete).isEmpty())
        val gantt = AgendaItem(key = "gantt:task:1", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t")
        assertTrue(overlay(listOf(gantt), EditCommand(kind = CommandKind.GANTT_UPDATE, itemKey = gantt.key, change = mapOf(EditKeys.DONE to "true"))).isEmpty())
    }

    @Test
    fun aNewDueDateMakesAnItemLateOrNotAtOnce() {
        val earlier = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = todo.key, change = mapOf(EditKeys.DUE to EditKeys.day(LocalDate.of(2026, 10, 20))))
        val late = overlay(listOf(todo), earlier).single()
        assertTrue(late.late)
        assertEquals(8, late.lateDays)
        val later = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = todo.key, change = mapOf(EditKeys.DUE to EditKeys.day(TODAY)))
        assertTrue(overlay(listOf(todo.copy(late = true, lateDays = 3)), later).single().dueToday)
        val none = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = todo.key, change = mapOf(EditKeys.DUE to ""))
        assertNull(overlay(listOf(todo), none).single().due)
    }

    @Test
    fun aNewEventIsOnScreenWhileItWaits() {
        val change = mapOf(
            EditKeys.NEW_KIND to "event", EditKeys.SUMMARY to "Review", EditKeys.START to EditKeys.moment(Instant.parse("2026-10-30T14:00:00Z")),
            EditKeys.END to EditKeys.moment(Instant.parse("2026-10-30T15:00:00Z")), EditKeys.AGENT to "AG-0001"
        )
        val command = EditCommand(
            kind = CommandKind.CAL_CREATE, itemKey = "new:1", home = Home.CALENDAR, title = "Review",
            locator = mapOf("calendarUri" to "work", "calendarName" to "Work", "calendarColor" to "#2563EB"), change = change
        )
        val row = overlay(emptyList(), command).single()
        assertEquals("Review", row.title)
        assertTrue(row.timed)
        assertTrue(row.pending)
        assertEquals("Work", row.sourceName)
        assertEquals("Writer-Bot", row.agent.name)
        assertEquals(ItemKind.EVENT, row.kind)
    }

    @Test
    fun theGanttDatesPercentAndAgentShowAtOnce() {
        val task = AgendaItem(
            key = "gantt:task:1", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "t", startDate = LocalDate.of(2026, 10, 27),
            endDate = LocalDate.of(2026, 10, 29), due = LocalDate.of(2026, 10, 29), percentDone = 10
        )
        val update = EditCommand(
            kind = CommandKind.GANTT_UPDATE, itemKey = task.key,
            change = mapOf(EditKeys.END to EditKeys.day(LocalDate.of(2026, 11, 4)), EditKeys.PERCENT to "60", EditKeys.STATUS to "in_progress")
        )
        val agent = EditCommand(kind = CommandKind.GANTT_AGENT, itemKey = task.key, change = mapOf(EditKeys.AGENT to "AG-0001"))
        val row = overlay(listOf(task), update, agent).single()
        assertEquals(LocalDate.of(2026, 11, 4), row.endDate)
        assertEquals(60, row.percentDone)
        assertEquals("Writer-Bot", row.agent.name)
        assertEquals(bot.name, row.agent.name)
    }
}
