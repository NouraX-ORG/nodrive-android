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
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.CategoryFilter
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AgendaClock
import java.time.LocalDate

enum class AgendaPage { MAIN, SOURCES }

/** Everything the screen draws, in one value. */
data class AgendaUiState(
    val snapshot: AgendaSnapshot?,
    val refreshing: Boolean,
    val view: ViewState,
    val page: AgendaPage,
    /** The key of the row whose detail sheet is open. */
    val selectedKey: String?,
    /** The saved copy has been put on screen (before that "Loading your agenda..." shows). */
    val loaded: Boolean
)

/** The choices of the screen (view, day, switch, chips) and the actions of its buttons. */
class AgendaViewModel(
    private val repository: AgendaRepository,
    private val clock: AgendaClock,
    private val onOpened: () -> Unit = {}
) : ViewModel() {
    private val viewState = MutableStateFlow(ViewState(date = clock.today()))
    private val page = MutableStateFlow(AgendaPage.MAIN)
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<AgendaUiState> = combine(repository.state, repository.refreshing, viewState, page, selected) { snapshot, refreshing, view, page, selected ->
        AgendaUiState(snapshot, refreshing, view, page, selected, snapshot != null)
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
        private val onOpened: () -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgendaViewModel(repository, clock, onOpened) as T
    }
}
