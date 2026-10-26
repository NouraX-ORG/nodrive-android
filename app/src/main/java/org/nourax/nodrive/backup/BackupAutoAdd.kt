/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.core.Clock
import com.owncloud.android.datamodel.MediaFolder
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.lib.common.utils.Log_OC

/** Hook for upstream's media folder detection: new folders are added instead of announced while a switch is on. */
object BackupAutoAdd {
    private const val TAG = "BackupAutoAdd"

    /** Never throws: upstream's detection must not fail because of the backup. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun onMediaFoldersDetected(
        context: Context,
        userAccountManager: UserAccountManager,
        syncedFolderProvider: SyncedFolderProvider,
        clock: Clock,
        detected: List<MediaFolder>
    ) {
        try {
            if (!BackupControllers.isAvailable(context)) return
            userAccountManager.allUsers.forEach { user ->
                val controller = BackupControllers.create(context, syncedFolderProvider, clock, user.accountName)
                val snapshot = controller.snapshot()
                if (snapshot.mediaOn || snapshot.docsOn) controller.autoAdd()
            }
        } catch (e: Exception) {
            Log_OC.w(TAG, "automatic folder add skipped: ${e.javaClass.simpleName}")
        }
    }
}
