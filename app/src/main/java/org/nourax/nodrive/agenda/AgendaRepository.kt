/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.logic.SourceSettings

/**
 * One account's data for the screen and for the background refresh: it reads one refresh at a time, keeps the
 * latest [AgendaSnapshot] and gives it as a flow. Reading the saved copy needs no network.
 */
class AgendaRepository(private val agenda: AgendaEngine) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AgendaSnapshot?>(null)
    private val _refreshing = MutableStateFlow(false)

    val state: StateFlow<AgendaSnapshot?> = _state.asStateFlow()
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Puts what is saved on the phone on screen (no request). */
    suspend fun load() {
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.snapshot() }
        }
    }

    /** Reads every source. A second call while one runs waits and then reads again only if [manual]. */
    suspend fun refresh(manual: Boolean) {
        if (!manual && mutex.isLocked) return
        mutex.withLock {
            _refreshing.value = true
            try {
                withContext(Dispatchers.IO) { _state.value = agenda.refresh(manual) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Changes a setting and shows the result at once from what is read. */
    suspend fun updateSettings(change: (SourceSettings) -> SourceSettings) {
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.updateSettings(change) }
        }
    }

    /** Queues a change and sends it at once when there is a network. The screen shows it from the first moment (design 9). */
    suspend fun submit(command: EditCommand) {
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.submit(command) }
        }
    }

    /** Sends the changes that wait (back online, or the background send). */
    suspend fun sendPending(manual: Boolean) {
        if (!manual && mutex.isLocked) return
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.sendPending(manual) }
        }
    }

    suspend fun resolveConflict(id: Long, applyAgain: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.resolveConflict(id, applyAgain) }
        }
    }

    suspend fun undo(opId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) { _state.value = agenda.undo(opId) }
        }
    }
}
