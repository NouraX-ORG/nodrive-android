/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.BackgroundJobManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nourax.nodrive.devicesync.BackupGate
import org.nourax.nodrive.devicesync.DeviceSyncControl
import org.nourax.nodrive.devicesync.DeviceSyncRunner
import org.nourax.nodrive.devicesync.DeviceSyncSettings
import org.nourax.nodrive.devicesync.StopResult
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase

enum class Step { INTRO, PERMISSIONS, CHOOSE, BACKUP, FIRST_SYNC, STATUS }

class CollectionItem(
    val href: String,
    val kind: String,
    val name: String,
    val color: Int,
    val readOnly: Boolean,
    val offReason: String,
    val checked: Boolean
)

class NotSavedItem(val id: Long, val kind: String, val title: String)

data class DeviceSyncUi(
    val step: Step = Step.INTRO,
    val permissionsDenied: Boolean = false,
    val loading: Boolean = false,
    val offline: Boolean = false,
    val items: List<CollectionItem> = emptyList(),
    /** The person came from the status screen: the backup and the first sync are not asked again. */
    val changing: Boolean = false,
    val backupRunning: Boolean = false,
    val backupDone: Boolean = false,
    val backupFailed: Boolean = false,
    val firstSyncRunning: Boolean = false,
    val firstSyncDone: Boolean = false,
    val firstSyncFailed: Boolean = false,
    val calendarsOnPhone: Int = 0,
    val addressBooksOnPhone: Int = 0,
    val lastSyncUtc: Long = 0,
    val syncing: Boolean = false,
    val waiting: Int = 0,
    val autoSyncOff: Boolean = false,
    val notSaved: List<NotSavedItem> = emptyList(),
    val stopQuestion: Boolean = false,
    val stopRefused: Boolean = false,
    val applyFailed: Boolean = false,
    val noCalendarStorage: Boolean = false
)

/**
 * The state of the device sync screen. The steps are those of plan 4.1: intro, permissions, choose, backup, first sync, then the
 * status. Everything that talks to the server or to the phone's providers runs off the main thread.
 */
class DeviceSyncModel(
    app: Application,
    private val account: String,
    private val jobs: BackgroundJobManager,
    private val user: User
) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()

    private val mutable = MutableStateFlow(DeviceSyncUi())
    val state: StateFlow<DeviceSyncUi> = mutable

    init {
        if (DeviceSyncSettings.get(context, account).enabled) {
            mutable.value = DeviceSyncUi(step = Step.STATUS)
            refreshStatus()
        }
    }

    // ---- intro, permissions -------------------------------------------------------------------

    fun permissionsGranted() {
        mutable.update { it.copy(permissionsDenied = false) }
        loadChoices()
    }

    fun needPermissions(denied: Boolean) = mutable.update { it.copy(step = Step.PERMISSIONS, permissionsDenied = denied) }

    // ---- choose -------------------------------------------------------------------------------

    fun loadChoices(changing: Boolean = false) {
        mutable.update { it.copy(step = Step.CHOOSE, loading = true, offline = false, changing = changing) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { DeviceSyncRunner.discover(context, account) }
            val rows = withContext(Dispatchers.IO) { DeviceSyncDatabase.get(context).dao().collections(account) }
            val anyChosen = rows.any { it.chosen }
            val items = rows.map {
                CollectionItem(
                    it.href, it.kind, it.name,
                    RemoteCollection(it.href, it.kind, it.name, it.color, it.readOnly, it.offReason).colorInt(),
                    it.readOnly, it.offReason, if (anyChosen) it.chosen else it.offReason.isEmpty()
                )
            }
            mutable.update { it.copy(loading = false, offline = !ok && rows.isEmpty(), items = items) }
        }
    }

    fun toggle(href: String) = mutable.update { ui ->
        ui.copy(items = ui.items.map { if (it.href == href) CollectionItem(it.href, it.kind, it.name, it.color, it.readOnly, it.offReason, !it.checked) else it })
    }

    /** "Continue" on the choose screen. */
    fun chooseDone() {
        val items = mutable.value.items
        if (items.none { it.checked }) return
        val changing = mutable.value.changing
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) {
                DeviceSyncDatabase.get(context).dao().collections(account).filter { it.chosen }.map { it.href }.toSet()
            }
            if (!changing) {
                withContext(Dispatchers.IO) { for (i in items) DeviceSyncSettings.setChosen(context, account, i.href, i.checked) }
                startBackup()
                return@launch
            }
            // taking a collection away asks the phone to send its changes first; the others are simply added
            withContext(Dispatchers.IO) { for (i in items) if (i.checked) DeviceSyncSettings.setChosen(context, account, i.href, true) }
            var refused = false
            for (href in items.filter { !it.checked && it.href in before }.map { it.href }) {
                val result = withContext(Dispatchers.IO) { DeviceSyncControl.stop(context, account, href) }
                if (result is StopResult.Refused) refused = true
            }
            mutable.update { it.copy(step = Step.STATUS, stopRefused = refused) }
            refreshStatus()
            syncNow()
        }
    }

    // ---- backup ----------------------------------------------------------------------------

    fun startBackup() {
        mutable.update { it.copy(step = Step.BACKUP, backupRunning = true, backupDone = false, backupFailed = false) }
        viewModelScope.launch {
            val connection = withContext(Dispatchers.IO) { DeviceSyncRunner.connect(context, account) }
            if (connection == null) {
                mutable.update { it.copy(backupRunning = false, backupFailed = true) }
                return@launch
            }
            val gate = BackupGate(context, jobs, user, connection.env.http, connection.env.dav.davUser)
            val result = gate.run()
            if (result is BackupGate.Result.Done) {
                withContext(Dispatchers.IO) { DeviceSyncSettings.update(context, account, backupDoneUtc = System.currentTimeMillis()) }
                mutable.update { it.copy(backupRunning = false, backupDone = true) }
                delay(BACKUP_PAUSE_MS)
                startFirstSync()
            } else {
                mutable.update { it.copy(backupRunning = false, backupFailed = true) }
            }
        }
    }

    // ---- first sync ------------------------------------------------------------------------

    private fun startFirstSync() {
        mutable.update { it.copy(step = Step.FIRST_SYNC, firstSyncRunning = true, firstSyncDone = false, firstSyncFailed = false) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DeviceSyncSettings.update(context, account, enabled = true)
                DeviceSyncControl.switchOn(context, account)
                DeviceSyncRunner.run(context, account, setOf(RemoteCollection.CALENDAR, RemoteCollection.CONTACTS))
            }
            val counts = withContext(Dispatchers.IO) { counts() }
            mutable.update {
                it.copy(
                    firstSyncRunning = false, firstSyncDone = outcome.ok, firstSyncFailed = !outcome.ok,
                    calendarsOnPhone = counts.first, addressBooksOnPhone = counts.second
                )
            }
        }
    }

    /** "Continue" after the first sync. */
    fun finishFirstSync() {
        mutable.update { it.copy(step = Step.STATUS) }
        refreshStatus()
    }

    // ---- status ----------------------------------------------------------------------------

    private fun counts(): Pair<Int, Int> {
        val rows = DeviceSyncDatabase.get(context).dao().collections(account).filter { it.chosen }
        return rows.count { it.kind == RemoteCollection.CALENDAR } to rows.count { it.kind == RemoteCollection.CONTACTS }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val dao = DeviceSyncDatabase.get(context).dao()
                val state = DeviceSyncSettings.get(context, account)
                Triple(state, dao.notSaved(account), counts())
            }
            val waiting = withContext(Dispatchers.IO) { DeviceSyncRunner.waitingCount(context, account) }
            mutable.update {
                it.copy(
                    lastSyncUtc = snapshot.first.lastSyncUtc,
                    waiting = waiting,
                    autoSyncOff = !DeviceSyncControl.autoSyncOn(),
                    notSaved = snapshot.second.map { n -> NotSavedItem(n.id, n.kind, n.title) },
                    calendarsOnPhone = snapshot.third.first,
                    addressBooksOnPhone = snapshot.third.second,
                    noCalendarStorage = snapshot.first.lastMessage == "no_calendar_storage"
                )
            }
        }
    }

    /** "Sync now": the phone's own way first (so the adapters are the ones that run); if nothing happens, in this process. */
    fun syncNow() {
        if (mutable.value.syncing) return
        mutable.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) { DeviceSyncSettings.get(context, account).lastSyncUtc }
            withContext(Dispatchers.IO) { DeviceSyncControl.syncNow(context, account) }
            var changed = false
            for (i in 0 until SYNC_WAIT_STEPS) {
                delay(SYNC_STEP_MS)
                val now = withContext(Dispatchers.IO) { DeviceSyncSettings.get(context, account).lastSyncUtc }
                if (now != before) {
                    changed = true
                    break
                }
            }
            if (!changed) {
                withContext(Dispatchers.IO) { DeviceSyncRunner.run(context, account, setOf(RemoteCollection.CALENDAR, RemoteCollection.CONTACTS)) }
            }
            mutable.update { it.copy(syncing = false) }
            refreshStatus()
        }
    }

    fun applyAgain(id: Long) {
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) { DeviceSyncRunner.applyAgain(context, account, id) }
            mutable.update { it.copy(applyFailed = !sent) }
            refreshStatus()
        }
    }

    fun keepServerVersion(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DeviceSyncRunner.keepServerVersion(context, id) }
            refreshStatus()
        }
    }

    // ---- stop ------------------------------------------------------------------------------

    fun askStop() = mutable.update { it.copy(stopQuestion = true, stopRefused = false) }

    fun cancelStop() = mutable.update { it.copy(stopQuestion = false) }

    fun confirmStop() {
        mutable.update { it.copy(stopQuestion = false, syncing = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { DeviceSyncControl.stop(context, account) }
            if (result is StopResult.Refused) {
                mutable.update { it.copy(syncing = false, stopRefused = true) }
                refreshStatus()
            } else {
                mutable.value = DeviceSyncUi(step = Step.INTRO)
            }
        }
    }

    fun clearNotices() = mutable.update { it.copy(stopRefused = false, applyFailed = false) }

    class Factory(
        private val context: Context,
        private val account: String,
        private val jobs: BackgroundJobManager,
        private val user: User
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DeviceSyncModel(context.applicationContext as Application, account, jobs, user) as T
    }

    private companion object {
        const val BACKUP_PAUSE_MS = 1500L
        const val SYNC_STEP_MS = 1000L
        const val SYNC_WAIT_STEPS = 25
    }
}
