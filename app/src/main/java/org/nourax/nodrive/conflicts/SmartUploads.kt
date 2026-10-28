/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.R
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.operations.UploadFileOperation
import org.nourax.nodrive.backup.BackupState
import java.io.File

/**
 * What the upload path does with a name collision. [policy] replaces the operation's policy for this one run (null: leave
 * it); [failure] ends the operation with that result (null: go on).
 */
data class SmartOutcome(val policy: NameCollisionPolicy?, val failure: RemoteOperationResult<*>?) {
    companion object {
        val NO_CHANGE = SmartOutcome(null, null)
    }
}

/**
 * The Smart rule in the upload path (round 3 contract, A). Called by one marked hook in UploadFileOperation, when the
 * target name already exists on the Drive. The hook turns Smart into upstream's SKIP (same size) or RENAME (anything
 * else) for that run; upstream's own branches then do the work, so Smart can never overwrite and never delete.
 */
object SmartUploads {
    private const val TAG = "SmartUploads"

    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    fun decide(
        operation: UploadFileOperation,
        policy: NameCollisionPolicy,
        remotePath: String,
        client: OwnCloudClient,
        encrypted: Boolean
    ): SmartOutcome {
        val context = operation.context ?: return SmartOutcome.NO_CHANGE
        return try {
            if (!context.resources.getBoolean(R.bool.nodrive_conflicts)) return SmartOutcome.NO_CHANGE
            if (policy != NameCollisionPolicy.ASK_USER) return SmartOutcome.NO_CHANGE
            // Only automatic uploads: a file the user picks by hand keeps upstream's question.
            if (!isAutoUpload(operation.createdBy)) return SmartOutcome.NO_CHANGE
            val account = operation.user.accountName
            val settings = ConflictSettings.create(context)
            settings.ensureMigrated()
            if (settings.choice(account) != ConflictChoice.SMART) return SmartOutcome.NO_CHANGE
            val data = com.owncloud.android.datamodel.ArbitraryDataProviderImpl(context)
            val owned = BackupState(account, data).read().owned
            if (OwnedRows.find(SyncedFolderPolicyStore(context).rows(), owned, account, operation.originalStoragePath, remotePath)
                == null
            ) {
                return SmartOutcome.NO_CHANGE
            }
            // The server holds ciphertext in an end-to-end encrypted folder, so sizes cannot be compared: keep both.
            if (encrypted) return SmartOutcome(NameCollisionPolicy.RENAME, null)
            outcome(operation.originalStoragePath, remotePath, client)
        } catch (e: RuntimeException) {
            // Upstream's visible question runs instead: never a silent skip and never an overwrite.
            Log_OC.w(TAG, "smart rule not applied: ${e.javaClass.simpleName}")
            SmartOutcome.NO_CHANGE
        }
    }

    private fun isAutoUpload(createdBy: Int) =
        createdBy == UploadFileOperation.CREATED_AS_INSTANT_PICTURE || createdBy == UploadFileOperation.CREATED_AS_INSTANT_VIDEO

    private fun outcome(localPath: String?, remotePath: String, client: OwnCloudClient): SmartOutcome {
        val local = localPath?.let { File(it) }?.takeIf { it.isFile } ?: return SmartOutcome.NO_CHANGE
        return when (val decision = SmartRule.decide(local.length(), remoteSize(remotePath, client))) {
            SmartDecision.AlreadyBackedUp -> {
                Log_OC.d(TAG, "same size on the Drive, counted as backed up")
                SmartOutcome(NameCollisionPolicy.SKIP, null)
            }
            SmartDecision.KeepBoth -> SmartOutcome(NameCollisionPolicy.RENAME, null)
            is SmartDecision.Retry -> SmartOutcome(null, decision.failure)
        }
    }

    /** PROPFIND of the file: its `getcontentlength` (RemoteFile.length), not `oc:size`. */
    fun remoteSize(remotePath: String, client: OwnCloudClient): RemoteSize<RemoteOperationResult<*>> {
        val result = ReadFileRemoteOperation(remotePath).execute(client)
        if (result.isSuccess) {
            val file = result.data?.firstOrNull() as? RemoteFile
            return if (file != null) RemoteSize.Known(file.length) else RemoteSize.Unreadable(result)
        }
        return if (result.code == ResultCode.FILE_NOT_FOUND) RemoteSize.Missing else RemoteSize.Unreadable(result)
    }
}
