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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.SourceState
import org.nourax.nodrive.agenda.registry.RegistryStatus
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import org.nourax.nodrive.agenda.store.Sources
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** One account end to end with a fake server: refresh, the saved copy, offline, back-off, refusals, one refresh at a time. */
class AgendaRepositoryTest {
    private class MutableClock(var instant: Instant) : AgendaClock {
        override fun now(): Instant = instant

        override fun zone(): ZoneId = UTC
    }

    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1"
    private val account = "ali@https://drive.example.test"

    private fun agenda(http: FakeHttp, store: InMemoryAgendaStore = InMemoryAgendaStore(), clock: MutableClock = MutableClock(NOW)) =
        AccountAgenda(account, http, store, clock)

    private fun scenario(user: String = "ali"): FakeHttp = FakeHttp().also { routeScenario(it, user) }

    @Test
    fun anAccountReadsSavesAndComesBackOffline() {
        val http = scenario()
        val store = InMemoryAgendaStore()
        val first = agenda(http, store)
        // before any read: nothing, and no status
        assertEquals(0, first.snapshot().build.items.size)
        val state = first.refresh(manual = true)
        assertEquals(33, state.build.items.size)
        assertTrue(state.build.needsPersonalQuestion)
        for (key in listOf(StatusKeys.USER, StatusKeys.CALENDAR, StatusKeys.GANTT, StatusKeys.BOARD, StatusKeys.REGISTRY)) {
            assertEquals(key, SourceState.FRESH, state.status(key).state)
            assertEquals(NOW.toEpochMilli(), state.status(key).savedAtMs)
        }
        assertEquals(RegistryStatus.OK, state.registryStatus)

        // a new start reads the saved copy: the same rows, "saved", no attempt yet (so no banner)
        val second = agenda(http, store)
        val saved = second.snapshot()
        assertEquals(33, saved.build.items.size)
        for (key in listOf(StatusKeys.CALENDAR, StatusKeys.GANTT, StatusKeys.BOARD, StatusKeys.REGISTRY)) {
            assertEquals(key, SourceState.SAVED, saved.status(key).state)
            assertTrue(saved.status(key).savedAtMs > 0)
        }

        // offline: every source keeps its rows and says when they were saved
        http.offline = true
        val offline = second.refresh(manual = true)
        assertEquals(33, offline.build.items.size)
        assertEquals(SourceState.CACHED, offline.status(StatusKeys.GANTT).state)
        assertEquals(0, offline.status(StatusKeys.GANTT).httpStatus)
        assertEquals(NOW.toEpochMilli(), offline.status(StatusKeys.GANTT).savedAtMs)
        assertEquals(SourceState.CACHED, offline.status(StatusKeys.CALENDAR).state)
    }

    @Test
    fun theFirstReadOfNothingSavedIsFailedNotCached() {
        val http = scenario()
        http.offline = true
        val state = agenda(http).refresh(manual = true)
        assertEquals(0, state.build.items.size)
        assertEquals(0L, state.savedAtMs)
        assertEquals(SourceState.FAILED, state.status(StatusKeys.USER).state)
        assertEquals(SourceState.FAILED, state.status(StatusKeys.CALENDAR).state)
    }

    @Test
    fun aFailingSourceKeepsItsSavedRowsAndTheOthersStayFresh() {
        val http = scenario()
        val agenda = agenda(http)
        agenda.refresh(manual = true)
        http.route("GET", "$gantt/my-tasks", 500, "")
        val state = agenda.refresh(manual = true)
        assertEquals(SourceState.CACHED, state.status(StatusKeys.GANTT).state)
        assertEquals(500, state.status(StatusKeys.GANTT).httpStatus)
        assertEquals(33, state.build.items.size) // the old rows stay
        assertEquals(SourceState.FRESH, state.status(StatusKeys.CALENDAR).state)
        assertEquals(SourceState.FRESH, state.status(StatusKeys.BOARD).state)
    }

    @Test
    fun aFailedCapabilitiesReadIsNotTakenForNoBoardAppOnTheServer() {
        val http = scenario()
        http.route("GET", "ocs/v1.php/cloud/capabilities", 500, "")
        val agenda = agenda(http)
        val first = agenda.refresh(manual = true)
        // Not known whether the board app is installed: it is not shown as "isn't available on your server".
        assertFalse(first.build.boardUnavailable)
        assertEquals(SourceState.FAILED, first.status(StatusKeys.BOARD).state)
        assertEquals(SourceState.FRESH, first.status(StatusKeys.CALENDAR).state)
        // The next read finds the capabilities and then the boards.
        routeScenario(http, "ali")
        val second = agenda.refresh(manual = true)
        assertFalse(second.build.boardUnavailable)
        assertEquals(SourceState.FRESH, second.status(StatusKeys.BOARD).state)
        assertEquals(33, second.build.items.size)
    }

    @Test
    fun aPartialReadNeverReplacesTheCache() {
        val http = scenario()
        val store = InMemoryAgendaStore()
        val agenda = agenda(http, store)
        agenda.refresh(manual = true)
        val before = store.read(account, Sources.GANTT)!!.json
        http.offline = true
        agenda.refresh(manual = true)
        assertEquals(before, store.read(account, Sources.GANTT)!!.json)
    }

    @Test
    fun aFailingSourceBacksOffAndAManualRefreshIgnoresIt() {
        val http = scenario()
        val clock = MutableClock(NOW)
        val agenda = agenda(http, clock = clock)
        agenda.refresh(manual = true)
        http.route("GET", "$gantt/my-tasks", 500, "")
        agenda.refresh(manual = true) // first failure: 5 minutes
        val after = http.count("GET", "$gantt/my-tasks")
        // a background run inside the 5 minutes does not ask NoGantt again
        clock.instant = NOW.plusSeconds(60)
        val state = agenda.refresh(manual = false)
        assertEquals(after, http.count("GET", "$gantt/my-tasks"))
        assertEquals(SourceState.CACHED, state.status(StatusKeys.GANTT).state)
        // a manual refresh ignores the back-off
        agenda.refresh(manual = true)
        assertEquals(after + 1, http.count("GET", "$gantt/my-tasks"))
        // the second failure waits 10 minutes: still closed after 6, open after 11
        clock.instant = NOW.plusSeconds(60 + 6 * 60)
        agenda.refresh(manual = false)
        assertEquals(after + 1, http.count("GET", "$gantt/my-tasks"))
        clock.instant = NOW.plusSeconds(60 + 11 * 60)
        agenda.refresh(manual = false)
        assertEquals(after + 2, http.count("GET", "$gantt/my-tasks"))
        // the other sources are not held back by it
        assertEquals(SourceState.FRESH, agenda.snapshot().status(StatusKeys.CALENDAR).state)
    }

    @Test
    fun aRefusingAppTurnsIntoRefusedNotIntoASignInRequest() {
        val http = scenario()
        val agenda = agenda(http)
        val rowsBefore = agenda.refresh(manual = true).build.items.size
        http.route("GET", "$gantt/my-tasks", 403, "")
        http.route("GET", "index.php/apps/deck/api/v1.0/boards", 401, "")
        val state = agenda.refresh(manual = true)
        assertEquals(SourceState.REFUSED, state.status(StatusKeys.GANTT).state)
        assertEquals(SourceState.REFUSED, state.status(StatusKeys.BOARD).state)
        assertEquals(SourceState.FRESH, state.status(StatusKeys.CALENDAR).state)
        assertNotEquals(SourceState.NEEDS_LOGIN, state.status(StatusKeys.USER).state)
        assertEquals(rowsBefore, state.build.items.size) // what was read stays
        // no request ever went to another host: the fake has only paths, the real transport refuses other origins (see AccountHttpTest)
        assertTrue(http.requests.all { it.method in setOf("GET", "PROPFIND", "REPORT") })
    }

    @Test
    fun aRefusalFromTheServerItselfStillAsksToSignIn() {
        val http = scenario()
        val agenda = agenda(http)
        agenda.refresh(manual = true)
        http.route("PROPFIND", "remote.php/dav/principals/users/ali/", 401, "")
        val state = agenda.refresh(manual = true)
        assertEquals(SourceState.NEEDS_LOGIN, state.status(StatusKeys.CALENDAR).state)
        assertEquals(33, state.build.items.size)
    }

    @Test
    fun aChoiceIsSavedAndRebuildsWithoutANewRequest() {
        val http = scenario()
        val store = InMemoryAgendaStore()
        val agenda = agenda(http, store)
        agenda.refresh(manual = true)
        val requests = http.requests.size
        val state = agenda.updateSettings { it.answerPersonalQuestion(setOf("personal"), listOf("personal", "work", "ali-task-copy"), false) }
        assertFalse(state.build.needsPersonalQuestion)
        assertEquals(org.nourax.nodrive.agenda.model.Category.WORK, state.build.items.byKey("cal:work/call.ics")!!.category)
        assertEquals(requests, http.requests.size)
        // it survives a restart
        val again = agenda(http, store).snapshot()
        assertEquals(org.nourax.nodrive.agenda.model.Category.WORK, again.build.items.byKey("cal:work/call.ics")!!.category)
        assertFalse(again.build.needsPersonalQuestion)
    }

    @Test
    fun hidingAProjectRemovesItsRowsAndShowingItReadsItAgain() {
        val http = scenario()
        val agenda = agenda(http)
        agenda.refresh(manual = true)
        agenda.updateSettings { it.withProject(1, WorkMode.HIDDEN) }
        val hidden = agenda.refresh(manual = true)
        assertTrue(hidden.build.items.none { it.projectId == 1 })
        val shown = agenda.updateSettings { it.withProject(1, WorkMode.DO) }
        assertTrue("the rows come back after the next read", shown.build.items.none { it.projectId == 1 })
        val again = agenda.refresh(manual = true)
        assertTrue(again.build.items.any { it.projectId == 1 })
    }

    @Test
    fun aMissingRegistryIsNotAnErrorAndEverythingIsSelf() {
        val http = scenario()
        http.route("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json", 404, "")
        val state = agenda(http).refresh(manual = true)
        assertEquals(RegistryStatus.MISSING, state.registryStatus)
        assertEquals(SourceState.FRESH, state.status(StatusKeys.REGISTRY).state)
        assertTrue(state.build.items.filter { it.home == org.nourax.nodrive.agenda.model.Home.GANTT && !it.problemOnly }.all { it.agent.isSelf || it.agent.unknown })
    }

    @Test
    fun anInvalidRegistryIsKeptAsInvalid() {
        val http = scenario()
        http.route("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json", 200, "{ this is not json")
        val state = agenda(http).refresh(manual = true)
        assertEquals(RegistryStatus.INVALID, state.registryStatus)
    }

    @Test
    fun theOnlyRequestsAreReads() {
        val http = scenario()
        agenda(http).refresh(manual = true)
        assertTrue(http.requests.isNotEmpty())
        assertTrue(http.requests.all { it.method == "GET" || it.method == "PROPFIND" || it.method == "REPORT" })
    }

    @Test
    fun oneRefreshAtATime() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val calls = AtomicInteger()
        val real = agenda(scenario())
        val engine = object : AgendaEngine {
            override fun snapshot() = real.snapshot()

            override fun refresh(manual: Boolean): AgendaSnapshot {
                calls.incrementAndGet()
                maxRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                entered.complete(Unit)
                runBlocking { release.await() }
                running.decrementAndGet()
                return real.snapshot()
            }

            override fun updateSettings(change: (SourceSettings) -> SourceSettings) = real.updateSettings(change)
        }
        val repository = AgendaRepository(engine)
        runBlocking {
            val first = async(Dispatchers.Default) { repository.refresh(manual = true) }
            entered.await()
            // a background run while one is going does nothing; a manual one waits its turn
            repository.refresh(manual = false)
            assertEquals(1, calls.get())
            val waiting = async(Dispatchers.Default) { repository.refresh(manual = true) }
            release.complete(Unit)
            awaitAll(first, waiting)
        }
        assertEquals(2, calls.get())
        assertEquals(1, maxRunning.get())
        assertFalse(repository.refreshing.value)
    }

    @Test
    fun accountRemovalDeletesOnlyThatAccountsRows() {
        val http = scenario()
        val store = InMemoryAgendaStore()
        val ali = AccountAgenda(account, http, store, MutableClock(NOW))
        ali.refresh(manual = true)
        val other = AccountAgenda("sara@https://drive.example.test", scenario("ali"), store, MutableClock(NOW))
        other.refresh(manual = true)
        store.forget(account)
        assertEquals(null, store.read(account, Sources.GANTT))
        assertEquals(null, store.loadSettings(account))
        assertTrue(store.read("sara@https://drive.example.test", Sources.GANTT) != null)
    }
}
