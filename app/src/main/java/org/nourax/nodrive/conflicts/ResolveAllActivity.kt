/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.UploadListActivity
import kotlin.concurrent.thread

/**
 * Shows only the "Resolve all conflicts" dialog (over a see-through window), starts the work and closes. It has no
 * injection: it needs just the account name, which comes from the notification or from the current account.
 */
class ResolveAllActivity : AppCompatActivity() {
    private var fromNotification = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromNotification = ResolveAll.accountOf(intent) != null
        val account = ResolveAll.accountOf(intent) ?: UserAccountManagerImpl.fromContext(this).user.accountName
        // On every start, also after a rotation or a new process: the dialog is not kept, so it is shown again, and
        // this see-through window is never left on top without one. Count first, off the main thread: nothing to
        // resolve is a short message, not a dialog of choices.
        thread(name = "nodrive-conflict-count") {
            val waiting = ConflictNotifications.countWaiting(account)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    dialog = if (waiting == 0) showNone(account) else showChoices(account)
                }
            }
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showNone(account: String): AlertDialog {
        ConflictNotifications.refreshAsync(this, account)
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nodrive_conflicts_resolve_all_title)
            .setMessage(R.string.nodrive_conflicts_resolve_all_none)
            .setPositiveButton(R.string.common_ok) { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }

    private fun showChoices(account: String): AlertDialog =
        ChoiceDialog.show(
            this,
            R.string.nodrive_conflicts_resolve_all_title,
            ConflictChoice.RESOLVE_ALL_CHOICES,
            null,
            R.string.nodrive_conflicts_resolve_all_apply,
            R.string.common_cancel,
            onCancel = { finish() }
        ) { choice ->
            Log_OC.d(TAG, "resolve all conflicts with ${choice.key}")
            ResolveAll.start(applicationContext, account, choice)
            done()
        }

    /** From the notification the Uploads screen opens, where the rows change as they are resolved. */
    private fun done() {
        if (fromNotification) {
            startActivity(
                Intent(this, UploadListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "ResolveAllActivity"
    }
}
