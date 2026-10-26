/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.client.account.User
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.utils.PermissionUtil

/** Offers the backup once, after the first real sign-in of an account (design 2.6). */
object BackupOffer {
    private const val TAG = "BackupOffer"

    /** Called only from the real sign-in, so accounts made by tests or screenshots never get the offer. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun markPending(context: Context, accountName: String) {
        try {
            if (!BackupControllers.isAvailable(context)) return
            BackupState(accountName, ArbitraryDataProviderImpl(context)).markOfferPending()
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "offer not marked: ${e.javaClass.simpleName}")
        }
    }

    /** Shows the offer if it is pending and upstream's own storage question is not in the way. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun maybeShow(activity: AppCompatActivity, user: User?) {
        try {
            if (user == null || user.isAnonymous || !BackupControllers.isAvailable(activity)) return
            val state = BackupState(user.accountName, ArbitraryDataProviderImpl(activity))
            if (state.offer() != OfferState.PENDING || !storageQuestionIsFinished(activity)) return
            val root = BackupPaths.root(state.read().root, PhoneFolders.deviceName(activity))
            // Done before the dialog is shown, so the offer appears once whatever the user answers.
            state.markOfferDone()
            activity.window.decorView.post { show(activity, root) }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "offer skipped: ${e.javaClass.simpleName}")
        }
    }

    private fun storageQuestionIsFinished(activity: AppCompatActivity): Boolean {
        val choiceDialogOpen =
            activity.supportFragmentManager.findFragmentByTag(PermissionUtil.PERMISSION_CHOICE_DIALOG_TAG) != null
        return !choiceDialogOpen && (
            PermissionUtil.checkStoragePermission(activity) ||
                AppPreferencesImpl.fromContext(activity).isStoragePermissionRequested
            )
    }

    private fun show(activity: AppCompatActivity, root: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.nodrive_backup_offer_title)
            .setMessage(activity.getString(R.string.nodrive_backup_offer_message, root))
            .setPositiveButton(R.string.nodrive_backup_offer_turn_on) { _, _ ->
                activity.startActivity(BackupSetupActivity.intent(activity, BackupAction.MEDIA_ON))
            }
            .setNegativeButton(R.string.nodrive_backup_offer_not_now, null)
            .show()
    }
}
