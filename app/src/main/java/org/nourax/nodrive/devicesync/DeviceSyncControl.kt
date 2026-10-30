/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import com.owncloud.android.MainApp
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.devicesync.calendar.CalendarProviderStore
import org.nourax.nodrive.devicesync.contacts.ContactsProviderStore
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase

/** The result of "Stop syncing" or of taking one calendar or address book away. */
sealed class StopResult {
    object Done : StopResult()

    /** A phone change could not be sent (offline, conflict): nothing was removed (plan 4.6). */
    class Refused(val waiting: Int) : StopResult()
}

/** Switching on and off, and the phone's sync settings of the NoDrive account. */
object DeviceSyncControl {
    private const val TAG = "DeviceSyncControl"
    private val authorities = listOf(DeviceSyncConstants.AUTHORITY_CALENDAR, DeviceSyncConstants.AUTHORITY_CONTACTS)

    private fun account(context: Context, name: String) = Account(name, MainApp.getAccountType(context))

    /** Syncable, automatic and every 30 minutes (Android's own minimum is 15), for the calendar and the contacts of the account. */
    fun switchOn(context: Context, accountName: String) {
        val account = account(context, accountName)
        for (authority in authorities) {
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.setSyncAutomatically(account, authority, true)
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, DeviceSyncConstants.PERIODIC_SECONDS)
        }
    }

    /** Not syncable any more: the phone's account settings show no toggle and no sync is asked for. */
    fun switchOff(context: Context, accountName: String) {
        val account = account(context, accountName)
        for (authority in authorities) {
            ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
            ContentResolver.setSyncAutomatically(account, authority, false)
            ContentResolver.setIsSyncable(account, authority, 0)
        }
    }

    /** True when the phone's global auto-sync is on (the status screen says so when it is not). */
    fun autoSyncOn(): Boolean = ContentResolver.getMasterSyncAutomatically()

    /** "Sync now": the way the phone's own button does it (user-started, at once, whatever the settings). */
    fun syncNow(context: Context, accountName: String) {
        val account = account(context, accountName)
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
        }
        for (authority in authorities) ContentResolver.requestSync(account, authority, Bundle(extras))
    }

    /** Whether the phone knows each adapter as syncable (for the screens and the tests). */
    fun isSyncable(context: Context, accountName: String, authority: String): Int =
        ContentResolver.getIsSyncable(account(context, accountName), authority)

    /**
     * Stop syncing, or take one chosen collection away. First a final sync sends every change of the phone. If one can not be sent
     * (no network, a conflict), nothing is removed. Only after that are our account's phone calendars and contacts removed: they are
     * copies of what stays on the server (plan 4.6).
     * @param href the one collection to take away, or null for everything (sync is then switched off).
     */
    fun stop(context: Context, accountName: String, href: String? = null): StopResult {
        val app = context.applicationContext
        val dao = DeviceSyncDatabase.get(app).dao()
        val outcome = DeviceSyncRunner.run(app, accountName, setOf(RemoteCollection.CALENDAR, RemoteCollection.CONTACTS))
        val waiting = DeviceSyncRunner.waitingCount(app, accountName)
        if (!outcome.ok || waiting > 0) {
            return StopResult.Refused(waiting.coerceAtLeast(outcome.waiting).coerceAtLeast(1))
        }
        val scope = ProviderScope(accountName, MainApp.getAccountType(app))
        val calendars = CalendarProviderStore(app.contentResolver, scope)
        val contacts = ContactsProviderStore(app.contentResolver, scope)
        val collections = dao.collections(accountName).filter { href == null || it.href == href }
        try {
            for (c in collections) {
                if (c.kind == RemoteCollection.CALENDAR) {
                    calendars.findCalendarId(c.href)?.let { calendars.deleteCalendar(it) }
                } else if (href != null) {
                    // one address book: its contacts (they are linked to its addresses)
                    for (row in contacts.rows()) if (row.syncId?.startsWith(c.href) == true) contacts.delete(row.id)
                }
                dao.deleteObjects(accountName, c.href)
                dao.putCollection(c.with(chosen = false, syncToken = "", androidId = 0, firstSyncDone = false, objectCount = 0))
            }
            if (href == null) {
                // every phone calendar of ours (also one the server no longer lists), and all our contacts and groups
                for (id in calendars.calendarIds().keys) calendars.deleteCalendar(id)
                contacts.deleteAll()
            }
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "removal stopped: ${e.javaClass.simpleName}")
        }
        if (href == null) {
            switchOff(app, accountName)
            DeviceSyncSettings.update(app, accountName, enabled = false, lastMessage = "", waiting = 0)
        }
        return StopResult.Done
    }
}
