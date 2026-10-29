/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import android.content.Context
import org.nourax.nodrive.agenda.android.AgendaRefreshWorker

object AgendaAccounts {
    private const val TAG = "AgendaAccounts"

    /**
     * Called when the account is removed: cancels its background refresh and deletes its rows from NoAgenda's own
     * cache. They are only copies of server data, never a user file, and would otherwise keep personal events on the phone.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun forget(context: Context, accountName: String) {
        try {
            val app = context.applicationContext
            AgendaRefreshWorker.cancel(app, accountName)
            AgendaAccess.store(app).forget(accountName)
            AgendaAccess.forgetRepository(accountName)
        } catch (e: Exception) {
            AgendaLog.w(TAG, "not forgotten: ${e.javaClass.simpleName}")
        }
    }
}
