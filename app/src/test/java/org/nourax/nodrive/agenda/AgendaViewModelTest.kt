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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.CategoryFilter
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import org.nourax.nodrive.agenda.ui.AgendaPage
import org.nourax.nodrive.agenda.ui.AgendaViewModel
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {
    private val account = "ali@https://drive.example.test"
    private val clock = FixedAgendaClock(NOW)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun waitFor(vm: AgendaViewModel, what: (org.nourax.nodrive.agenda.ui.AgendaUiState) -> Boolean) = runBlocking {
        withTimeout(WAIT_MS) { vm.state.first(what) }
    }

    private fun model(store: InMemoryAgendaStore = InMemoryAgendaStore(), opened: AtomicInteger = AtomicInteger()): Pair<AgendaViewModel, FakeHttp> {
        val http = FakeHttp()
        routeScenario(http, "ali")
        val repository = AgendaRepository(AccountAgenda(account, http, store, clock))
        return AgendaViewModel(repository, clock) { opened.incrementAndGet() } to http
    }

    @Test
    fun opensOnTodayReadsAndSchedulesTheBackgroundRefreshOnce() {
        val opened = AtomicInteger()
        val (vm, http) = model(opened = opened)
        val state = waitFor(vm) { it.snapshot?.build?.items?.size == 33 && !it.refreshing }
        assertEquals(TODAY, state.view.date)
        assertEquals(AgendaView.DAY, state.view.view)
        assertEquals(AgendaPage.MAIN, state.page)
        assertEquals(1, opened.get())
        assertTrue(http.requests.isNotEmpty())
        // it was marked as opened, which is what lets the background refresh run for this account
        assertTrue(state.snapshot!!.settings.everOpened)
    }

    @Test
    fun theStepsAreADayAWeekAndAMonth() {
        val (vm, _) = model()
        waitFor(vm) { it.snapshot?.settings?.everOpened == true }
        vm.shift(1)
        assertEquals(TODAY.plusDays(1), vm.state.value.view.date)
        vm.setView(AgendaView.WEEK)
        vm.shift(-1)
        assertEquals(TODAY.plusDays(1).minusWeeks(1), vm.state.value.view.date)
        vm.setView(AgendaView.MONTH)
        vm.shift(1)
        assertEquals(TODAY.plusDays(1).minusWeeks(1).plusMonths(1), vm.state.value.view.date)
        vm.today()
        assertEquals(TODAY, vm.state.value.view.date)
        vm.setDate(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2027, 1, 1), vm.state.value.view.date)
    }

    @Test
    fun theChoicesAreSavedAndComeBackNextTime() {
        val store = InMemoryAgendaStore()
        val (vm, _) = model(store)
        waitFor(vm) { it.snapshot?.settings?.everOpened == true }
        vm.setView(AgendaView.WEEK)
        vm.setCategory(CategoryFilter.WORK)
        vm.toggleAgents()
        vm.toggleShowAllTeam()
        waitFor(vm) { it.snapshot?.settings?.lastView == "week" && it.snapshot?.settings?.showAllTeam == true }
        val (again, _) = model(store)
        val state = waitFor(again) { it.view.view == AgendaView.WEEK }
        assertEquals(CategoryFilter.WORK, state.view.category)
        assertFalse(state.view.chipAgents)
        assertTrue(state.view.chipMe)
        assertTrue(state.view.showAllTeam)
    }

    @Test
    fun theSourcesScreenIsAPageAndBackReturns() {
        val (vm, _) = model()
        assertFalse(vm.back())
        vm.openSources()
        assertEquals(AgendaPage.SOURCES, vm.state.value.page)
        assertTrue(vm.back())
        assertEquals(AgendaPage.MAIN, vm.state.value.page)
    }

    @Test
    fun theDetailSheetOpensAndCloses() {
        val (vm, _) = model()
        vm.select("cal:work/call.ics")
        assertEquals("cal:work/call.ics", vm.state.value.selectedKey)
        vm.select(null)
        assertEquals(null, vm.state.value.selectedKey)
    }

    @Test
    fun answeringTheQuestionOrChangingASourceReadsAgain() {
        val (vm, http) = model()
        waitFor(vm) { it.snapshot?.build?.needsPersonalQuestion == true && !it.refreshing }
        val before = http.requests.size
        vm.answerPersonal(setOf("personal"), listOf("personal", "work", "ali-task-copy"), skipped = false)
        val state = waitFor(vm) { it.snapshot?.build?.needsPersonalQuestion == false && !it.refreshing && http.requests.size > before }
        assertFalse(state.snapshot!!.build.needsPersonalQuestion)
        vm.setProjectMode(1, WorkMode.HIDDEN)
        waitFor(vm) { it.snapshot?.build?.items?.none { item -> item.projectId == 1 } == true && !it.refreshing }
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
