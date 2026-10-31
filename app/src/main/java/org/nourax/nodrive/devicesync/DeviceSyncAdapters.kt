/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import org.nourax.nodrive.devicesync.model.RemoteCollection

/** What a system sync request means for NoDrive (plan 4.3). */
object DeviceSyncRequests {
    /**
     * Runs one sync for the account the phone asked about. Before the person switched sync on in NoDrive, the first request of the phone
     * (`SYNC_EXTRAS_INITIALIZE`) only sets "not syncable", so Android's account settings offer no Calendar or Contacts toggle that would
     * bypass the backup and the first-sync rules.
     */
    fun perform(context: Context, account: Account, authority: String, extras: Bundle, syncResult: SyncResult, kind: String) {
        val enabled = DeviceSyncSettings.get(context, account.name).enabled
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)) {
            ContentResolver.setIsSyncable(account, authority, if (enabled) 1 else 0)
            return
        }
        if (!enabled) return
        val flags = SyncFlags(
            overrideTooManyDeletions = extras.getBoolean(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS, false),
            discardLocalDeletions = extras.getBoolean(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS, false)
        )
        val outcome = DeviceSyncRunner.run(context, account.name, setOf(kind), flags)
        syncResult.stats.numInserts += outcome.added.toLong()
        syncResult.stats.numUpdates += outcome.changed.toLong() + outcome.sentToServer
        // the deletes that the guard held back count too: Android's "too many deletions" screen shows this number
        syncResult.stats.numDeletes += (outcome.removed + outcome.heldBack).toLong()
        if (outcome.tooManyDeletions) syncResult.tooManyDeletions = true
        if (!outcome.ok) {
            // no network, or the server was not reachable: the phone tries again later with its own back-off
            if (outcome.message == "offline" || outcome.message.startsWith("server_")) syncResult.stats.numIoExceptions++
        }
    }
}

class CalendarSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        DeviceSyncRequests.perform(context, account, authority, extras, syncResult, RemoteCollection.CALENDAR)
    }
}

class ContactsSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        DeviceSyncRequests.perform(context, account, authority, extras, syncResult, RemoteCollection.CONTACTS)
    }
}
