/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC

/** Entry points of "Resolve all conflicts…": the Uploads screen menu and the notification action. */
object ResolveAll {
    private const val TAG = "ResolveAll"
    private const val EXTRA_ACCOUNT = "nodrive_conflicts_account"
    private const val MENU_ID = 0x4E44C0

    /** Hook for the Uploads screen's overflow menu. Never throws. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun addMenuItem(activity: Activity, menu: Menu) {
        try {
            if (!activity.resources.getBoolean(R.bool.nodrive_conflicts)) return
            if (menu.findItem(MENU_ID) != null) return
            menu.add(Menu.NONE, MENU_ID, Menu.NONE, R.string.nodrive_conflicts_resolve_all_menu).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    activity.startActivity(Intent(activity, ResolveAllActivity::class.java))
                    true
                }
            }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "menu item not added: ${e.javaClass.simpleName}")
        }
    }

    /** The intent of the notification action: the Resolve all dialog for that account. */
    fun intent(context: Context, account: String): Intent =
        Intent(context, ResolveAllActivity::class.java).apply {
            putExtra(EXTRA_ACCOUNT, account)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** The notification action: opens the Resolve all dialog for that account (no broadcast hop, so it works on Android 12+). */
    fun pendingIntent(context: Context, account: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            account.hashCode(),
            intent(context, account),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun accountOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ACCOUNT)

    /**
     * One run at a time per account. A second Resolve all while one runs waits for it and then handles what still
     * waits with its own choice (so a different choice is never dropped, and a repeated tap finds nothing left to do).
     */
    fun start(context: Context, account: String, choice: ConflictChoice) {
        val request = OneTimeWorkRequestBuilder<ResolveAllWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ResolveAllWorker.ACCOUNT, account)
                    .putString(ResolveAllWorker.CHOICE, choice.key)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("nodrive_resolve_all_$account", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
