/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import androidx.work.WorkManager
import com.nextcloud.client.core.Clock
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.utils.Log_OC
import org.nourax.nodrive.conflicts.ConflictSettings

object BackupControllers {
    private const val TAG = "BackupControllers"

    fun create(context: Context, provider: SyncedFolderProvider, clock: Clock, account: String): BackupController =
        BackupController(
            account,
            PhoneFolders(context),
            SyncedFolderStore(
                provider,
                clock,
                { cancelAutoUpload(context, it) },
                newRowPolicy(context)
            ),
            BackupState(account, ArbitraryDataProviderImpl(context)),
            PhoneFolders.deviceName(context)
        )

    /** New rows get the account's conflict choice (Smart by default) instead of upstream's Ask; off, upstream's value stays. */
    private fun newRowPolicy(context: Context): (String) -> Int {
        if (!context.resources.getBoolean(com.owncloud.android.R.bool.nodrive_conflicts)) {
            return { NameCollisionPolicy.ASK_USER.serialize() }
        }
        val settings = ConflictSettings.create(context)
        return { account -> settings.newRowPolicy(account) }
    }

    /** The same unique work name as upstream's BackgroundJobManagerImpl.startAutoUpload; the unsent files stay pending. */
    @Suppress("TooGenericExceptionCaught")
    private fun cancelAutoUpload(context: Context, rowId: Long) {
        try {
            WorkManager.getInstance(context)
                .cancelUniqueWork("${BackgroundJobManagerImpl.JOB_IMMEDIATE_FILES_SYNC}_$rowId")
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "could not stop the running upload of folder $rowId: ${e.javaClass.simpleName}")
        }
    }

    fun isAvailable(context: Context): Boolean =
        context.resources.getBoolean(com.owncloud.android.R.bool.nodrive_backup)
}
