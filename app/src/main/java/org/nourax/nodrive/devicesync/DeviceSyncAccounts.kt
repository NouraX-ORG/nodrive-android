/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.Context
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase

object DeviceSyncAccounts {
    private const val TAG = "DeviceSyncAccounts"

    /**
     * Called when the account is removed: forgets this account's rows in the device sync's own database. Android itself removes the
     * phone calendars and contacts of a removed account (changes that were not sent yet go with them: the status screen shows how
     * many are waiting, so the person can sync first).
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun forget(context: Context, accountName: String) {
        try {
            val dao = DeviceSyncDatabase.get(context.applicationContext).dao()
            dao.deleteAllNotSaved(accountName)
            dao.deleteAllObjects(accountName)
            dao.deleteCollections(accountName)
            dao.deleteAccount(accountName)
        } catch (e: Exception) {
            AgendaLog.w(TAG, "not forgotten: ${e.javaClass.simpleName}")
        }
    }
}
