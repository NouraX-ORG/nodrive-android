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
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase

/** The result of "Stop syncing" or of taking one calendar or address book away. */
sealed class StopResult {
    object Done : StopResult()

    /**
     * Nothing was removed (plan 4.6): [waiting] phone changes could not be sent (offline, an error), or [notSaved] phone versions lost a
     * conflict and wait in "Not saved" (the person decides what to do with them first).
     */
    class Refused(val waiting: Int, val notSaved: Int, val unreadable: Boolean = false) : StopResult()
}

/** When "Stop syncing" must not remove anything (plan 4.6). A seam for the tests: it knows nothing of Android. */
object StopRule {
    /**
     * [waiting] is null when the phone could not be read (a permission taken away, a provider error): that is a refusal too, because
     * "could not look" is not "nothing there", and removing the copies could take unsent edits with them.
     *
     * Null when nothing stands in the way. The reasons to refuse are the person's own data only: [waiting] phone changes that were not
     * sent (offline, an error) and [notSaved] phone versions that lost a conflict and still wait in "Not saved". How the final sync itself
     * ended does not matter: a sync that fails for a lasting reason with nothing waiting (the calendar list can not be read, the server
     * answers an error) must not hold the removal up for ever. Anything the sync could not send is still counted in [waiting].
     */
    fun refusal(outcome: SyncOutcome, waiting: Int?, notSaved: Int): StopResult.Refused? {
        val unsent = maxOf(waiting ?: 0, outcome.waiting)
        return if (waiting == null || unsent > 0 || notSaved > 0) StopResult.Refused(unsent, notSaved, unreadable = waiting == null) else null
    }
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
     * (no network, an error), or a phone version lost a conflict and is still in "Not saved", nothing is removed. Only after that are
     * our account's phone calendars and contacts removed: they are copies of what stays on the server (plan 4.6).
     *
     * The refusal is about the person's unsent changes, never about the sync's own health: a sync that fails for a lasting reason (the
     * calendar list can not be read, the server answers an error) with nothing waiting does not hold the removal up.
     * @param href the one collection to take away, or null for everything (sync is then switched off).
     */
    fun stop(context: Context, accountName: String, href: String? = null): StopResult =
        // One lock for the final sync and the removal: a sync that the phone starts in between would add an empty copy again.
        DeviceSyncRunner.exclusive { stopLocked(context.applicationContext, accountName, href) }

    private fun stopLocked(app: Context, accountName: String, href: String?): StopResult {
        val dao = DeviceSyncDatabase.get(app).dao()
        val outcome = DeviceSyncRunner.run(app, accountName, setOf(RemoteCollection.CALENDAR, RemoteCollection.CONTACTS))
        // a phone version that lost a conflict (also one from before) must be looked at first: after Stop the list is not reachable
        val notSaved = dao.notSaved(accountName).count { href == null || it.collection == href }
        StopRule.refusal(outcome, DeviceSyncRunner.waitingCountOrNull(app, accountName), notSaved)?.let { return it }
        val scope = ProviderScope(accountName, MainApp.getAccountType(app))
        val calendars = CalendarProviderStore(app.contentResolver, scope)
        val contacts = ContactsProviderStore(app.contentResolver, scope)
        try {
            for (c in dao.collections(accountName).filter { href == null || it.href == href }) {
                CollectionRemoval.takeAway(dao, accountName, c, calendars, contacts)
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
