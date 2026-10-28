/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.utils.Log_OC
import org.nourax.nodrive.backup.BackupState

/**
 * How a backup folder shows its policy in upstream's per-folder Auto upload dialog (round 3 contract, A): the row
 * "What to do if the file already exists?" shows the NoDrive name of the account's choice, and tapping it explains where
 * to change it instead of opening upstream's picker. Folders that are not owned by the backup are left as upstream has them.
 */
object OwnedFolderPolicy {
    private const val TAG = "OwnedFolderPolicy"

    /** Never throws: the dialog must open even if this cannot be applied. */
    @JvmStatic
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    fun bind(
        activity: Activity,
        folderId: Long?,
        account: String?,
        rowPolicy: NameCollisionPolicy?,
        summary: TextView,
        container: View
    ) {
        try {
            if (!activity.resources.getBoolean(R.bool.nodrive_conflicts)) return
            if (folderId == null || account == null) return
            val data = ArbitraryDataProviderImpl(activity)
            if (folderId !in BackupState(account, data).read().owned) return
            val shown = ConflictChoice.forOwnedRow(rowPolicy, ConflictSettings(data, SyncedFolderPolicyStore(activity)).choice(account))
            summary.text = ChoiceDialog.label(activity, shown)
            container.setOnClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.nodrive_conflicts_setting_title)
                    .setMessage(R.string.nodrive_conflicts_folder_explanation)
                    .setPositiveButton(R.string.common_ok, null)
                    .show()
            }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "owned folder policy not shown: ${e.javaClass.simpleName}")
        }
    }
}
