/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import org.nourax.nodrive.agenda.ui.AgendaUiState
import org.nourax.nodrive.agenda.ui.AgendaViewModel
import org.nourax.nodrive.agenda.ui.NewKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The buttons of the edit step on the view model: the sheets, the queue, the Conflicts filter. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelEditTest {
    private val account = "ali@https://drive.example.test"
    private val clock = FixedAgendaClock(NOW)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun waitFor(vm: AgendaViewModel, what: (AgendaUiState) -> Boolean): AgendaUiState = runBlocking { withTimeout(WAIT_MS) { vm.state.first(what) } }

    private class Fixture(val vm: AgendaViewModel, val http: FakeHttp, val store: InMemoryEditStore, val queued: AtomicInteger)

    private fun fixture(): Fixture {
        val http = FakeHttp()
        routeScenario(http, "ali")
        val store = InMemoryEditStore()
        val queued = AtomicInteger()
        val repository = AgendaRepository(AccountAgenda(account, http, InMemoryAgendaStore(), clock, editStore = store))
        return Fixture(AgendaViewModel(repository, clock, onQueued = { queued.incrementAndGet() }), http, store, queued)
    }

    @Test
    fun aChangeThatCanNotBeSentIsKeptAndQueuesTheBackgroundSendOnce() {
        val f = fixture()
        val ready = waitFor(f.vm) { it.snapshot?.build?.items?.size == 33 && !it.refreshing }
        val item = ready.snapshot!!.build.items.byTitle("Client call")!!
        f.vm.select(item.key)
        f.vm.startEdit()
        waitFor(f.vm) { it.editing }
        f.http.offline = true
        val command = EditCommands.event(item, EventForm.of(item, UTC).copy(title = "Moved"), UTC, ready.snapshot!!.build.myAgents)!!
        f.vm.submit(listOf(command))
        val state = waitFor(f.vm) { it.snapshot?.edit?.pending?.size == 1 }
        // the sheet closed, the change is on screen and marked, and the background send was queued
        assertFalse(state.editing)
        assertNull(state.selectedKey)
        // the view model asks for the background send right after the queue took the change
        runBlocking { withTimeout(WAIT_MS) { while (f.queued.get() == 0) kotlinx.coroutines.delay(10) } }
        assertEquals(1, f.queued.get())
        val row = state.snapshot!!.build.items.first { it.key == item.key }
        assertEquals("Moved", row.title)
        assertTrue(row.pending)
        assertEquals(1, f.store.commands(account).size)
    }

    @Test
    fun aChangeSavedWhileARefreshHoldsTheLockIsStoredEvenWhenTheScreenIsClosedAtOnce() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val store = InMemoryEditStore()
        val real = AccountAgenda(account, FakeHttp(), InMemoryAgendaStore(), clock, editStore = store)
        // the refresh that starts when NoAgenda opens is slow and holds the repository's lock
        val engine = object : AgendaEngine by real {
            override fun refresh(manual: Boolean): AgendaSnapshot {
                started.countDown()
                gate.await()
                return real.snapshot()
            }
        }
        val queued = AtomicInteger()
        val vm = AgendaViewModel(AgendaRepository(engine), clock, onQueued = { queued.incrementAndGet() })
        assertTrue("the refresh started", started.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val first = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = "cal:a", home = Home.CALENDAR, title = "A", change = mapOf(EditKeys.SUMMARY to "A2"))
        val second = EditCommand(kind = CommandKind.CAL_PATCH, itemKey = "cal:b", home = Home.CALENDAR, title = "B", change = mapOf(EditKeys.SUMMARY to "B2"))
        // one Save that makes two commands, then Back at once
        vm.submit(listOf(first, second))
        runBlocking { withTimeout(WAIT_MS) { while (store.commands(account).size < 2) kotlinx.coroutines.delay(10) } }
        vm.viewModelScope.cancel()
        runBlocking { withTimeout(WAIT_MS) { while (queued.get() == 0) kotlinx.coroutines.delay(10) } }
        gate.countDown()
        // both commands are in the queue, and the background send was asked for
        assertEquals(listOf("A", "B"), store.commands(account).map { it.title })
        assertEquals(1, queued.get())
    }

    @Test
    fun aChangeThatIsSentQueuesNothing() {
        val f = fixture()
        val ready = waitFor(f.vm) { it.snapshot?.build?.items?.size == 33 && !it.refreshing }
        val item = ready.snapshot!!.build.items.byTitle("Client call")!!
        f.http.route("GET", "remote.php/dav/calendars/ali/work/call.ics", 200, sampleEvent("ci-call"))
        f.http.route("PUT", "remote.php/dav/calendars/ali/work/call.ics", 204, "")
        f.vm.submit(listOf(EditCommands.event(item, EventForm.of(item, UTC).copy(title = "Moved"), UTC, ready.snapshot!!.build.myAgents)!!))
        waitFor(f.vm) { it.snapshot?.edit?.notice != null }
        assertEquals(0, f.queued.get())
        assertTrue(f.store.commands(account).isEmpty())
        assertTrue(f.http.requests.any { it.method == "PUT" })
    }

    @Test
    fun theNewItemSheetOpensAndCloses() {
        val f = fixture()
        f.vm.startCreate(NewKind.EVENT)
        assertEquals(NewKind.EVENT, f.vm.state.value.creating)
        f.vm.stopCreate()
        assertNull(f.vm.state.value.creating)
        f.vm.startCreate(NewKind.TODO)
        assertEquals(NewKind.TODO, f.vm.state.value.creating)
    }

    @Test
    fun theConflictsFilterIsAChoiceOfTheDayView() {
        val f = fixture()
        assertFalse(f.vm.state.value.view.conflictsOnly)
        f.vm.toggleConflicts()
        assertTrue(f.vm.state.value.view.conflictsOnly)
        f.vm.toggleConflicts()
        assertFalse(f.vm.state.value.view.conflictsOnly)
    }

    @Test
    fun selectingAnotherRowLeavesTheEditMode() {
        val f = fixture()
        f.vm.select("a")
        f.vm.startEdit()
        f.vm.select("b")
        assertFalse(f.vm.state.value.editing)
    }

    private companion object {
        const val WAIT_MS = 20_000L
    }
}
