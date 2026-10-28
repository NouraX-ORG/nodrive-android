/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.nextcloud.client.core.Clock
import com.nextcloud.client.jobs.upload.FileUploadHelper
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.files.services.NameCollisionPolicy
import kotlinx.coroutines.runBlocking

/** [FolderStore] on upstream's synced folder table, so the Auto upload screen shows and edits the same rows. */
class SyncedFolderStore(
    private val provider: SyncedFolderProvider,
    private val clock: Clock,
    /** Stops the Auto upload work that is running or queued for a row id (the real one asks WorkManager). */
    private val cancelUploads: (Long) -> Unit = {},
    /** The name collision column of a new row, per account (the account's "If a file is already in NoDrive" choice). */
    private val newRowPolicy: (String) -> Int = { NameCollisionPolicy.ASK_USER.serialize() }
) : FolderStore {
    override fun rows(): List<FolderRow> = provider.syncedFolders.map {
        FolderRow(it.id, it.localPath, it.type, it.account, it.isEnabled)
    }

    override fun create(account: String, spec: FolderSpec, defaults: BackupDefaults): Long {
        val row = SyncedFolder(
            spec.path,
            spec.remotePath,
            defaults.wifiOnly,
            defaults.chargingOnly,
            true,
            false,
            account,
            FileUploadWorker.LOCAL_BEHAVIOUR_FORGET,
            newRowPolicy(account),
            true,
            clock.currentTime,
            spec.type,
            false,
            SubFolderRule.YEAR_MONTH,
            spec.excludeHidden,
            SyncedFolder.NOT_SCANNED_YET
        )
        return provider.storeSyncedFolder(row)
    }

    override fun enable(rowId: Long) {
        provider.updateSyncedFolderEnabled(rowId, true)
    }

    override fun disable(rowId: Long) {
        // First the work that is uploading now: its loop stops before the next file (the unsent files stay pending).
        cancelUploads(rowId)
        provider.updateSyncedFolderEnabled(rowId, false)
        // The same step as the Auto upload switch: queued upload entries go, never a file.
        runBlocking { FileUploadHelper.instance().removeEntityFromUploadEntities(rowId) }
    }
}
