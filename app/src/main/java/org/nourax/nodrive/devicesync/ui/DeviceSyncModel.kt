/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import org.nourax.nodrive.agenda.AgendaLog
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

/** One chosen calendar or address book with how many objects it holds on this phone. */
class CollectionCount(val href: String, val kind: String, val name: String, val count: Int, val gone: Boolean = false)

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
    /** Unsent phone changes that Stop would lose: they are in a calendar or address book that is no longer in NoDrive. */
    val stopLost: Int = 0,
    /** The same for unticking calendars or address books on the choose screen (the question is asked before they are taken away). */
    val untickLost: Int = 0,
    val stopRefused: Boolean = false,
    /** Stop was refused because a phone version is in "Not saved" (the text points the person to that list). */
    val stopRefusedNotSaved: Boolean = false,
    /** Stop was refused because the phone's calendars or contacts could not be read (nothing is removed; the text says to allow access). */
    val stopRefusedUnreadable: Boolean = false,
    val applyFailed: Boolean = false,
    val noCalendarStorage: Boolean = false,
    /** The phone's calendar or contacts permission was taken away: nothing can be synced until it is given again. */
    val noPermission: Boolean = false,
    /** The last sync did not finish for a reason other than the ones that have their own text. */
    val lastFailed: Boolean = false,
    /** The calendars could not be checked (NoGantt's copy calendar is not known): none are offered or synced, contacts still are. */
    val calendarsUnknown: Boolean = false,
    val chooseCalendarsUnknown: Boolean = false,
    val collectionCounts: List<CollectionCount> = emptyList()
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
            val result = withContext(Dispatchers.IO) { DeviceSyncRunner.discoverResult(context, account) }
            val ok = result?.ok == true
            // NoGantt's copy calendar is never offered
            val rows = withContext(Dispatchers.IO) {
                DeviceSyncDatabase.get(context).dao().collections(account).filter { it.offReason != RemoteCollection.OFF_TASK_COPY }
            }
            val anyChosen = rows.any { it.chosen }
            val items = rows.map {
                CollectionItem(
                    it.href, it.kind, it.name,
                    RemoteCollection(it.href, it.kind, it.name, it.color, it.readOnly, it.offReason).colorInt(),
                    it.readOnly, it.offReason, if (anyChosen) it.chosen else it.offReason.isEmpty()
                )
            }
            mutable.update {
                it.copy(
                    loading = false, offline = !ok && rows.isEmpty(), items = items, chooseCalendarsUnknown = ok && !result.calendarsKnown
                )
            }
        }
    }

    fun toggle(href: String) = mutable.update { ui ->
        ui.copy(items = ui.items.map { if (it.href == href) CollectionItem(it.href, it.kind, it.name, it.color, it.readOnly, it.offReason, !it.checked) else it })
    }

    /** "Continue" on the choose screen. Unticking a collection that is no longer in NoDrive asks first when that loses unsent changes. */
    fun chooseDone() {
        val items = mutable.value.items
        if (items.none { it.checked }) return
        if (!mutable.value.changing) {
            applyChoice()
            return
        }
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) {
                DeviceSyncDatabase.get(context).dao().collections(account).filter { it.chosen }.map { it.href }.toSet()
            }
            val removed = items.filter { !it.checked && it.href in before }.map { it.href }.toSet()
            val lost = if (removed.isEmpty()) 0 else withContext(Dispatchers.IO) { DeviceSyncRunner.lostCount(context, account, removed) }
            if (lost > 0) mutable.update { it.copy(untickLost = lost) } else applyChoice()
        }
    }

    fun confirmUntick() {
        mutable.update { it.copy(untickLost = 0) }
        applyChoice()
    }

    fun cancelUntick() = mutable.update { it.copy(untickLost = 0) }

    private fun applyChoice() {
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
            var refusedNotSaved = false
            var refusedUnreadable = false
            for (href in items.filter { !it.checked && it.href in before }.map { it.href }) {
                val result = withContext(Dispatchers.IO) { DeviceSyncControl.stop(context, account, href) }
                if (result is StopResult.Refused) {
                    refused = refused || result.waiting > 0
                    refusedNotSaved = refusedNotSaved || result.notSaved > 0
                    refusedUnreadable = refusedUnreadable || result.unreadable
                }
            }
            mutable.update {
                it.copy(step = Step.STATUS, stopRefused = refused, stopRefusedNotSaved = refusedNotSaved, stopRefusedUnreadable = refusedUnreadable)
            }
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
                AgendaLog.w(TAG, "backup: no connection to the account's server")
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
                AgendaLog.w(TAG, "backup failed: ${(result as? BackupGate.Result.Failed)?.reason}")
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

    /** True when the phone's calendar or contacts permission is not given (any more). */
    private fun permissionMissing(): Boolean = listOf(
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
    ).any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun refreshStatus() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val dao = DeviceSyncDatabase.get(context).dao()
                val state = DeviceSyncSettings.get(context, account)
                Triple(state, dao.notSaved(account), counts())
            }
            val perCollection = withContext(Dispatchers.IO) {
                DeviceSyncDatabase.get(context).dao().collections(account)
                    .filter { it.chosen && it.offReason != RemoteCollection.OFF_TASK_COPY }
                    .map { CollectionCount(it.href, it.kind, it.name, it.objectCount, it.offReason == RemoteCollection.OFF_GONE) }
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
                    noCalendarStorage = snapshot.first.lastMessage == "no_calendar_storage",
                    noPermission = snapshot.first.lastMessage == "no_permission" || permissionMissing(),
                    calendarsUnknown = snapshot.first.lastMessage == "calendars_unknown",
                    lastFailed = snapshot.first.lastMessage.isNotEmpty() && snapshot.first.lastMessage !in OWN_TEXT,
                    collectionCounts = perCollection
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

    /** Asks "Stop syncing?", with what would be lost: unsent changes of calendars and address books that are no longer in NoDrive. */
    fun askStop() {
        mutable.update {
            it.copy(stopQuestion = true, stopRefused = false, stopRefusedNotSaved = false, stopRefusedUnreadable = false, stopLost = 0)
        }
        viewModelScope.launch {
            val lost = withContext(Dispatchers.IO) { DeviceSyncRunner.lostCount(context, account) }
            mutable.update { it.copy(stopLost = lost) }
        }
    }

    fun cancelStop() = mutable.update { it.copy(stopQuestion = false, stopLost = 0) }

    fun confirmStop() {
        mutable.update { it.copy(stopQuestion = false, syncing = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { DeviceSyncControl.stop(context, account) }
            if (result is StopResult.Refused) {
                mutable.update {
                    it.copy(
                        syncing = false, stopRefused = result.waiting > 0, stopRefusedNotSaved = result.notSaved > 0,
                        stopRefusedUnreadable = result.unreadable
                    )
                }
                refreshStatus()
            } else {
                mutable.value = DeviceSyncUi(step = Step.INTRO)
            }
        }
    }

    fun clearNotices() =
        mutable.update { it.copy(stopRefused = false, stopRefusedNotSaved = false, stopRefusedUnreadable = false, applyFailed = false) }

    /** For the emulator tests of the screens: shows a state that is hard to bring about for real (a store that can not be read). */
    @androidx.annotation.VisibleForTesting
    fun showForTest(ui: DeviceSyncUi) {
        mutable.value = ui
    }

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
        const val TAG = "DeviceSyncModel"
        const val BACKUP_PAUSE_MS = 1500L
        const val SYNC_STEP_MS = 1000L
        const val SYNC_WAIT_STEPS = 25

        /** The messages of a failed sync that the status screen shows with a text of their own. */
        val OWN_TEXT = setOf("no_calendar_storage", "no_permission", "calendars_unknown")
    }
}
