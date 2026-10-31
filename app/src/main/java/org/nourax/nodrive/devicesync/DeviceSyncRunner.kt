/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.Context
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.MainApp
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.net.AccountAgendaHttp
import org.nourax.nodrive.devicesync.calendar.CalendarProviderStore
import org.nourax.nodrive.devicesync.calendar.CalendarSyncEngine
import org.nourax.nodrive.devicesync.contacts.ContactsProviderStore
import org.nourax.nodrive.devicesync.contacts.ContactsSyncEngine
import org.nourax.nodrive.devicesync.contacts.RemovedContactsCopy
import org.nourax.nodrive.devicesync.dav.CardDavClient
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.AccountEntity
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import org.nourax.nodrive.workspace.WorkspaceAccess
import java.time.ZoneId

object DeviceSyncConstants {
    const val AUTHORITY_CALENDAR = "com.android.calendar"
    const val AUTHORITY_CONTACTS = "com.android.contacts"
    const val PERIODIC_SECONDS = 30L * 60L
    const val ACTION_SETTINGS = "org.nourax.nodrive.devicesync.SETTINGS"
}

/** The account row (on or off, backup, last sync); an account that was never seen is off. */
object DeviceSyncSettings {
    fun get(context: Context, account: String): AccountEntity =
        DeviceSyncDatabase.get(context).dao().account(account) ?: AccountEntity(account, false, 0, 0, "", 0)

    fun put(context: Context, row: AccountEntity) = DeviceSyncDatabase.get(context).dao().putAccount(row)

    fun update(
        context: Context, account: String, enabled: Boolean? = null, backupDoneUtc: Long? = null,
        lastSyncUtc: Long? = null, lastMessage: String? = null, waiting: Int? = null
    ) {
        val old = get(context, account)
        put(
            context,
            AccountEntity(
                account, enabled ?: old.enabled, backupDoneUtc ?: old.backupDoneUtc, lastSyncUtc ?: old.lastSyncUtc,
                lastMessage ?: old.lastMessage, waiting ?: old.waiting
            )
        )
    }

    fun setChosen(context: Context, account: String, href: String, chosen: Boolean) {
        val dao = DeviceSyncDatabase.get(context).dao()
        val c = dao.collection(account, href) ?: return
        dao.putCollection(c.with(chosen = chosen))
    }
}

/**
 * One sync of one account, for both the system's sync adapters and the app's own buttons. One sync runs at a time in the process
 * (the lock), so a sync the phone starts after an edit and a "Sync now" never write at the same time.
 */
object DeviceSyncRunner {
    private const val TAG = "DeviceSyncRunner"
    private val lock = Any()

    /** A live connection to the account's own server: its own credential, only its own origin, only the writes of [DeviceSyncWritePolicy]. */
    class Connection(val env: SyncEnv, val safety: RemovedContactsCopy)

    @Suppress("TooGenericExceptionCaught")
    fun connect(context: Context, accountName: String, flags: SyncFlags = SyncFlags()): Connection? {
        val app = context.applicationContext
        return try {
            val user = UserAccountManagerImpl.fromContext(app).getUser(accountName).orElse(null) ?: return null
            val client = WorkspaceAccess.client(app, user)
            val http = AccountAgendaHttp.of(client, DeviceSyncWritePolicy::allows)
            val dav = CardDavClient(http, client.userId)
            Connection(SyncEnv(accountName, http, dav, DeviceSyncDatabase.get(app).dao(), ZoneId.systemDefault(), flags = flags), RemovedContactsCopy(http, client.userId))
        } catch (e: Exception) {
            AgendaLog.w(TAG, "no connection: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Asks the server for the calendars and address books and keeps their rows (what is chosen stays chosen). False when it could not ask. */
    fun discover(context: Context, accountName: String): Boolean = discoverResult(context, accountName)?.ok == true

    /** Like [discover], with what could be read (the calendars can be unknown while the address books are not). Null when there is no connection. */
    fun discoverResult(context: Context, accountName: String): RefreshResult? {
        val connection = connect(context, accountName) ?: return null
        return synchronized(lock) { CollectionRefresh.run(connection.env) }
    }

    /**
     * Runs [block] while no sync runs and none can start (the lock of the runner is held, and it can be taken again by [run] inside).
     * Stop syncing needs this: the final sync and the removal of the phone's copies must not have a sync in between that adds a copy again.
     */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    /** The calendars and address books that were chosen. */
    fun chosen(context: Context, account: String): List<CollectionEntity> =
        DeviceSyncDatabase.get(context).dao().collections(account).filter { it.chosen }

    /** What is waiting on the phone: changes that can still be sent and have not been (see [WaitingCount]). */
    fun waitingCount(context: Context, accountName: String): Int {
        val app = context.applicationContext
        return try {
            val scope = ProviderScope(accountName, MainApp.getAccountType(app))
            WaitingCount.of(
                CalendarProviderStore(app.contentResolver, scope), ContactsProviderStore(app.contentResolver, scope),
                DeviceSyncDatabase.get(app).dao().collections(accountName)
            )
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "waiting count failed: ${e.javaClass.simpleName}")
            0
        }
    }

    /** The unsent phone changes that removing the copies of [hrefs] (null = all) would take with them: see [WaitingCount.lost]. */
    fun lostCount(context: Context, accountName: String, hrefs: Set<String>? = null): Int {
        val app = context.applicationContext
        return try {
            val scope = ProviderScope(accountName, MainApp.getAccountType(app))
            WaitingCount.lost(
                CalendarProviderStore(app.contentResolver, scope), ContactsProviderStore(app.contentResolver, scope),
                DeviceSyncDatabase.get(app).dao().collections(accountName), hrefs
            )
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "lost count failed: ${e.javaClass.simpleName}")
            0
        }
    }

    /**
     * Runs one sync. [kinds] says which of the two adapters asked (a set of [RemoteCollection.CALENDAR] / [RemoteCollection.CONTACTS]).
     * It does nothing while sync is off, and nothing before the backup of the phone's calendars and contacts is done (plan 4.6).
     */
    fun run(context: Context, accountName: String, kinds: Set<String>, flags: SyncFlags = SyncFlags()): SyncOutcome {
        val app = context.applicationContext
        val state = DeviceSyncSettings.get(app, accountName)
        if (!state.enabled) return SyncOutcome.EMPTY
        if (state.backupDoneUtc <= 0) return SyncOutcome.failed("backup_pending")
        val connection = connect(app, accountName, flags) ?: return SyncOutcome.failed("no_account")
        return synchronized(lock) {
            // Stop syncing may have switched sync off while this one waited for the lock.
            if (!DeviceSyncSettings.get(app, accountName).enabled) return@synchronized SyncOutcome.EMPTY
            val env = connection.env
            val outcome = try {
                runLocked(app, env, connection.safety, kinds)
            } catch (e: RuntimeException) {
                AgendaLog.w(TAG, "sync failed: ${e.javaClass.simpleName}")
                SyncOutcome.failed("error")
            }
            DeviceSyncSettings.update(
                app, accountName, lastSyncUtc = if (outcome.ok) env.clock().toEpochMilli() else null,
                lastMessage = outcome.message, waiting = waitingCount(app, accountName)
            )
            if (outcome.notSaved > 0) DeviceSyncNotifier.notSaved(app, accountName)
            outcome
        }
    }

    private fun runLocked(app: Context, env: SyncEnv, safety: RemovedContactsCopy, kinds: Set<String>): SyncOutcome {
        val refresh = CollectionRefresh.run(env)
        if (!refresh.ok) return SyncOutcome.failed("offline")
        val scope = ProviderScope(env.account, MainApp.getAccountType(app))
        val calendarStore = CalendarProviderStore(app.contentResolver, scope)
        val contactsStore = ContactsProviderStore(app.contentResolver, scope)
        // NoGantt's copy calendar holds copies of tasks: if it was chosen once, its copy leaves the phone now (design 10).
        for (c in env.dao.collections(env.account)) {
            if (c.chosen && c.offReason == RemoteCollection.OFF_TASK_COPY) CollectionRemoval.takeAway(env.dao, env.account, c, calendarStore, contactsStore)
        }
        var total = SyncOutcome.EMPTY
        val chosen = CollectionRefresh.live(env.dao.collections(env.account))
        if (RemoteCollection.CALENDAR in kinds) {
            if (!refresh.calendarsKnown) {
                // The server could not say which calendar holds the copies of tasks (or the list failed): no calendar is synced blind.
                total = total.plus(SyncOutcome.failed("calendars_unknown"))
            } else {
                val engine = CalendarSyncEngine(env, calendarStore)
                for (c in chosen.filter { it.kind == RemoteCollection.CALENDAR }) total = total.plus(engine.sync(c))
            }
        }
        if (RemoteCollection.CONTACTS in kinds) {
            // a contact made on the phone goes to the first writable address book that was chosen
            val newBook = chosen.firstOrNull { it.kind == RemoteCollection.CONTACTS && !it.readOnly }?.href
            val engine = ContactsSyncEngine(env.withNewContactsBook(newBook), contactsStore, safety)
            for (c in chosen.filter { it.kind == RemoteCollection.CONTACTS }) total = total.plus(engine.sync(c))
        }
        return total
    }

    /** "Apply my change again" for one entry of "Not saved". True when the phone's change was sent. */
    fun applyAgain(context: Context, accountName: String, id: Long): Boolean {
        val app = context.applicationContext
        val connection = connect(app, accountName) ?: return false
        return synchronized(lock) {
            val env = connection.env
            val entry = env.dao.notSavedById(id) ?: return@synchronized false
            val scope = ProviderScope(accountName, MainApp.getAccountType(app))
            val sent = if (entry.kind == RemoteCollection.CALENDAR) {
                CalendarSyncEngine(env, CalendarProviderStore(app.contentResolver, scope)).applyAgain(entry)
            } else {
                ContactsSyncEngine(env, ContactsProviderStore(app.contentResolver, scope), connection.safety).applyAgain(entry)
            }
            if (sent) env.dao.deleteNotSaved(id)
            sent
        }
    }

    /** "Keep the NoDrive version": the lost phone version is dropped. */
    fun keepServerVersion(context: Context, id: Long) {
        DeviceSyncDatabase.get(context.applicationContext).dao().deleteNotSaved(id)
    }
}
