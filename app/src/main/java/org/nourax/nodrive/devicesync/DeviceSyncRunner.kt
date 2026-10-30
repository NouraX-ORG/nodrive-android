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
    fun discover(context: Context, accountName: String): Boolean {
        val connection = connect(context, accountName) ?: return false
        return synchronized(lock) { refreshCollections(connection.env) }
    }

    private fun refreshCollections(env: SyncEnv): Boolean {
        val gantt = env.dav.ganttCopyCalendar()
        val calendars = env.dav.calendars(if (gantt.isEmpty()) emptySet() else setOf(gantt))
        val books = env.dav.addressBooks()
        if (calendars == null && books == null) return false
        fun merge(list: List<RemoteCollection>?, kind: String) {
            if (list == null) return
            val have = env.dao.collections(env.account).filter { it.kind == kind }.associateBy { it.href }
            for (r in list) {
                val old = have[r.href]
                env.dao.putCollection(
                    CollectionEntity(
                        env.account, r.href, kind, r.name, r.colorText, r.readOnly, r.offReason, old?.chosen ?: false,
                        old?.syncToken.orEmpty(), old?.androidId ?: 0L, old?.firstSyncDone ?: false, old?.objectCount ?: 0
                    )
                )
            }
        }
        merge(calendars, RemoteCollection.CALENDAR)
        merge(books, RemoteCollection.CONTACTS)
        return true
    }

    /** The calendars and address books that were chosen. */
    fun chosen(context: Context, account: String): List<CollectionEntity> =
        DeviceSyncDatabase.get(context).dao().collections(account).filter { it.chosen }

    /** What is waiting on the phone: rows that were changed or deleted and not yet sent. */
    fun waitingCount(context: Context, accountName: String): Int {
        val app = context.applicationContext
        return try {
            val scope = ProviderScope(accountName, MainApp.getAccountType(app))
            val calendar = CalendarProviderStore(app.contentResolver, scope)
            var n = 0
            for (id in calendar.calendarIds().keys) n += calendar.rows(id).count { !it.isException && (it.dirty || it.deleted) }
            n += ContactsProviderStore(app.contentResolver, scope).rows().count { it.dirty || it.deleted }
            n
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "waiting count failed: ${e.javaClass.simpleName}")
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
        if (!refreshCollections(env)) return SyncOutcome.failed("offline")
        val scope = ProviderScope(env.account, MainApp.getAccountType(app))
        var total = SyncOutcome.EMPTY
        val chosen = env.dao.collections(env.account).filter { it.chosen }
        if (RemoteCollection.CALENDAR in kinds) {
            val engine = CalendarSyncEngine(env, CalendarProviderStore(app.contentResolver, scope))
            for (c in chosen.filter { it.kind == RemoteCollection.CALENDAR }) total = total.plus(engine.sync(c))
        }
        if (RemoteCollection.CONTACTS in kinds) {
            val engine = ContactsSyncEngine(env, ContactsProviderStore(app.contentResolver, scope), safety)
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
