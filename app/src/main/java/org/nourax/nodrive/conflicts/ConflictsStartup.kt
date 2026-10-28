/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.content.Context
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import kotlin.concurrent.thread

/** Called once when the app process starts (one marked hook in MainApp.onCreate). */
object ConflictsStartup {
    private const val TAG = "ConflictsStartup"

    /** Runs the one-time migration, puts the backup rows right and swaps old per-file notifications for the summary, off the main thread. Never throws. */
    @JvmStatic
    fun onAppCreate(context: Context) {
        val app = context.applicationContext
        if (!app.resources.getBoolean(R.bool.nodrive_conflicts)) return
        thread(name = "nodrive-conflicts-startup") {
            @Suppress("TooGenericExceptionCaught")
            try {
                val settings = ConflictSettings.create(app)
                settings.ensureMigrated()
                settings.healAll()
                ConflictNotifications.replaceOldPerFileNotifications(app)
            } catch (e: RuntimeException) {
                Log_OC.w(TAG, "startup work failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
