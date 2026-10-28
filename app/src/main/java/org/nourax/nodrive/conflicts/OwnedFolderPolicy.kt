/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.SyncedFolder
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

    /**
     * Called by a marked hook when upstream's Auto upload starts a run on [folder] (read from the database a moment ago).
     * An owned folder that holds an old value, written back by an earlier run, is put right in the database and in this
     * object, so the whole run uses the saved choice. Never throws: the run must start even if this cannot be applied.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun healBeforeRun(context: Context, folder: SyncedFolder) {
        try {
            if (!context.resources.getBoolean(R.bool.nodrive_conflicts)) return
            val account = folder.account ?: return
            val row = PolicyRow(
                folder.id,
                account,
                folder.localPath.orEmpty(),
                folder.remotePath.orEmpty(),
                folder.nameCollisionPolicyInt
            )
            val wanted = ConflictSettings.create(context).healRow(row) ?: return
            if (folder.nameCollisionPolicyInt != wanted) folder.setNameCollisionPolicy(wanted)
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "folder not put right before the run: ${e.javaClass.simpleName}")
        }
    }

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
