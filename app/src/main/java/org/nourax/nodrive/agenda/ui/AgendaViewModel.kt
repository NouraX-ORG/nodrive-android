/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nourax.nodrive.agenda.AgendaRepository
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.CategoryFilter
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.Home
import java.time.LocalDate

enum class AgendaPage { MAIN, SOURCES }

/** What the "+" button makes (design 8, 9). */
enum class NewKind { EVENT, TODO }

/** Everything the screen draws, in one value. */
data class AgendaUiState(
    val snapshot: AgendaSnapshot?,
    val refreshing: Boolean,
    val view: ViewState,
    val page: AgendaPage,
    /** The key of the row whose detail sheet is open. */
    val selectedKey: String?,
    /** The saved copy has been put on screen (before that "Loading your agenda..." shows). */
    val loaded: Boolean,
    /** The detail sheet shows the fields to change instead of the facts. */
    val editing: Boolean = false,
    /** The sheet of a new event or to-do is open. */
    val creating: NewKind? = null
)

/** The choices of the screen (view, day, switch, chips) and the actions of its buttons. */
class AgendaViewModel(
    private val repository: AgendaRepository,
    private val clock: AgendaClock,
    /** A change could not be sent now: the background send is queued for when the phone is online. */
    private val onQueued: () -> Unit = {},
    private val onOpened: () -> Unit = {}
) : ViewModel() {
    private val viewState = MutableStateFlow(ViewState(date = clock.today()))
    private val page = MutableStateFlow(AgendaPage.MAIN)
    private val selected = MutableStateFlow<String?>(null)
    private val editing = MutableStateFlow(false)
    private val creating = MutableStateFlow<NewKind?>(null)

    private class Local(val page: AgendaPage, val selected: String?, val editing: Boolean, val creating: NewKind?)

    private val local = combine(page, selected, editing, creating) { page, selected, editing, creating ->
        Local(page, selected, editing, creating)
    }

    val state: StateFlow<AgendaUiState> = combine(repository.state, repository.refreshing, viewState, local) { snapshot, refreshing, view, local ->
        AgendaUiState(snapshot, refreshing, view, local.page, local.selected, snapshot != null, local.editing, local.creating)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AgendaUiState(null, false, ViewState(date = clock.today()), AgendaPage.MAIN, null, false)
    )

    init {
        viewModelScope.launch {
            repository.load()
            repository.state.value?.settings?.let { restore(it) }
            // From now on the background refresh keeps this account's copy fresh (design 9).
            repository.updateSettings { it.copy(everOpened = true) }
            onOpened()
            repository.refresh(manual = true)
        }
    }

    private fun restore(settings: org.nourax.nodrive.agenda.logic.SourceSettings) {
        viewState.update {
            it.copy(
                view = when (settings.lastView) {
                    "week" -> AgendaView.WEEK
                    "month" -> AgendaView.MONTH
                    else -> AgendaView.DAY
                },
                category = when (settings.lastCategory) {
                    "work" -> CategoryFilter.WORK
                    "personal" -> CategoryFilter.PERSONAL
                    else -> CategoryFilter.ALL
                },
                chipMe = settings.chipMe,
                chipAgents = settings.chipAgents,
                chipTeam = settings.chipTeam,
                showAllTeam = settings.showAllTeam
            )
        }
    }

    private fun change(block: (ViewState) -> ViewState) {
        viewState.update(block)
        val now = viewState.value
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    lastView = now.view.name.lowercase(),
                    lastCategory = now.category.name.lowercase(),
                    chipMe = now.chipMe,
                    chipAgents = now.chipAgents,
                    chipTeam = now.chipTeam,
                    showAllTeam = now.showAllTeam
                )
            }
        }
    }

    fun setView(view: AgendaView) = change { it.copy(view = view) }

    fun setCategory(category: CategoryFilter) = change { it.copy(category = category) }

    fun toggleMe() = change { it.copy(chipMe = !it.chipMe) }

    fun toggleAgents() = change { it.copy(chipAgents = !it.chipAgents) }

    fun toggleTeam() = change { it.copy(chipTeam = !it.chipTeam) }

    fun toggleShowAllTeam() = change { it.copy(showAllTeam = !it.showAllTeam) }

    fun toggleNoDate() = viewState.update { it.copy(noDateExpanded = !it.noDateExpanded) }

    fun setDate(date: LocalDate) = viewState.update { it.copy(date = date) }

    fun today() = setDate(clock.today())

    /** One step back (-1) or forward (+1): a day, a week or a month. */
    fun shift(direction: Int) = viewState.update {
        it.copy(
            date = when (it.view) {
                AgendaView.DAY -> it.date.plusDays(direction.toLong())
                AgendaView.WEEK -> it.date.plusWeeks(direction.toLong())
                AgendaView.MONTH -> it.date.plusMonths(direction.toLong())
            }
        )
    }

    fun select(key: String?) {
        selected.value = key
        editing.value = false
    }

    fun startEdit() {
        editing.value = true
    }

    fun stopEdit() {
        editing.value = false
    }

    fun startCreate(kind: NewKind) {
        creating.value = kind
    }

    fun stopCreate() {
        creating.value = null
    }

    fun toggleConflicts() = viewState.update { it.copy(conflictsOnly = !it.conflictsOnly) }

    /**
     * Keeps the changes and closes the sheets, then sends them. The changes are stored FIRST, before any wait for the lock that a
     * refresh may hold and in a way a closed screen cannot cancel, so a change is never lost when the person presses Back at once.
     * If the screen goes away or the phone is offline, the background send takes over (it also finishes what is still waiting).
     */
    fun submit(commands: List<EditCommand>) {
        if (commands.isEmpty()) return
        editing.value = false
        creating.value = null
        selected.value = null
        viewModelScope.launch {
            try {
                repository.store(commands)
                repository.sendPending(manual = true)
                rememberCalendars(commands)
            } finally {
                // Cancelled (the screen is gone) or not sent: it stays in the queue and goes out in the background.
                // hasPending() cannot be cancelled, so this also runs when the screen was closed.
                if (repository.hasPending()) onQueued()
            }
        }
    }

    // The calendar or list of a new item is the first choice of the next "+" when the server has no default.
    private suspend fun rememberCalendars(commands: List<EditCommand>) {
        val created = commands.filter { it.kind == CommandKind.CAL_CREATE }
        val event = created.firstOrNull { it.home != Home.TASKS }?.loc("calendarUri").orEmpty()
        val todo = created.firstOrNull { it.home == Home.TASKS }?.loc("calendarUri").orEmpty()
        if (event.isEmpty() && todo.isEmpty()) return
        repository.updateSettings {
            it.copy(
                lastEventCalendar = event.ifEmpty { it.lastEventCalendar },
                lastTodoList = todo.ifEmpty { it.lastTodoList }
            )
        }
    }

    /** "Apply my change again" ([applyAgain]) or "Keep the other change". */
    fun resolve(conflictId: Long, applyAgain: Boolean) {
        viewModelScope.launch {
            repository.resolveConflict(conflictId, applyAgain)
            if (repository.state.value?.edit?.pending?.isNotEmpty() == true) onQueued()
        }
    }

    fun undo(opId: String) {
        viewModelScope.launch { repository.undo(opId) }
    }

    fun openSources() {
        page.value = AgendaPage.SOURCES
    }

    /** Back from the Sources screen. Returns false when there is nothing to go back to. */
    fun back(): Boolean {
        if (page.value == AgendaPage.SOURCES) {
            page.value = AgendaPage.MAIN
            return true
        }
        return false
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(manual = true) }
    }

    private fun changeSettingsAndRead(change: (org.nourax.nodrive.agenda.logic.SourceSettings) -> org.nourax.nodrive.agenda.logic.SourceSettings) {
        viewModelScope.launch {
            repository.updateSettings(change)
            // A source that was Hidden has not been read: read it now.
            repository.refresh(manual = true)
        }
    }

    fun setCalendarMode(uri: String, mode: CalendarMode) = changeSettingsAndRead { it.withCalendar(uri, mode) }

    fun setProjectMode(id: Int, mode: WorkMode) = changeSettingsAndRead { it.withProject(id, mode) }

    fun setBoardMode(id: Int, mode: WorkMode) = changeSettingsAndRead { it.withBoard(id, mode) }

    /** The answer to "Which of your calendars are personal?" (Skip keeps all as Personal and never asks again). */
    fun answerPersonal(personalUris: Set<String>, ownUris: List<String>, skipped: Boolean) =
        changeSettingsAndRead { it.answerPersonalQuestion(personalUris, ownUris, skipped) }

    fun setWorkingHours(hours: WorkingHours) {
        viewModelScope.launch { repository.updateSettings { it.copy(workingHours = hours) } }
    }

    class Factory(
        private val repository: AgendaRepository,
        private val clock: AgendaClock,
        private val onOpened: () -> Unit,
        private val onQueued: () -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgendaViewModel(repository, clock, onQueued, onOpened) as T
    }
}
