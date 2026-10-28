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

/** What the upload hook does, decided from plain facts (no I/O), so a table test covers every branch. */
internal sealed interface UploadStep {
    /** Leave the operation's policy as it is. */
    data object NoChange : UploadStep

    /** Use this upstream policy for this one run. */
    data class Use(val policy: NameCollisionPolicy) : UploadStep

    /** Smart: compare the size on the phone with the size on the Drive. */
    data object CompareSizes : UploadStep
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
            // Only automatic uploads: a file the user picks by hand keeps upstream's question.
            if (!isAutoUpload(operation.createdBy)) return SmartOutcome.NO_CHANGE
            val account = operation.user.accountName
            val settings = ConflictSettings.create(context)
            settings.ensureMigrated()
            val data = com.owncloud.android.datamodel.ArbitraryDataProviderImpl(context)
            val owned = BackupState(account, data).read().owned
            val rowStore = SyncedFolderPolicyStore(context)
            val row = OwnedRows.find(rowStore.rows(), owned, account, operation.originalStoragePath, remotePath)
                ?: return SmartOutcome.NO_CHANGE
            val choice = settings.choice(account)
            // Upstream's Auto upload writes its whole row back at the end of a run, with the value it read at the start.
            // A choice the user made while it ran can be undone that way, so the folder row is healed here.
            if (choice in KEEP_CHOICES) heal(choice, row, rowStore)
            when (val next = step(true, choice, policy, encrypted)) {
                UploadStep.NoChange -> SmartOutcome.NO_CHANGE
                is UploadStep.Use -> SmartOutcome(next.policy, null)
                UploadStep.CompareSizes -> compare(operation.originalStoragePath, remotePath, client)
            }
        } catch (e: RuntimeException) {
            // Upstream's visible question runs instead: never a silent skip and never an overwrite.
            Log_OC.w(TAG, "smart rule not applied: ${e.javaClass.simpleName}")
            SmartOutcome.NO_CHANGE
        }
    }

    private val KEEP_CHOICES = setOf(ConflictChoice.KEEP_NODRIVE, ConflictChoice.KEEP_PHONE, ConflictChoice.KEEP_BOTH)

    /**
     * Pure: what to do for one upload that meets an existing file. The operation's own policy rules: ASK_USER is what a
     * folder row carries (the Smart carrier, or a stale Auto upload write); anything else (RENAME, SKIP, OVERWRITE) was
     * chosen for this very upload, for example by "Resolve all conflicts", and is never replaced.
     */
    internal fun step(
        autoUpload: Boolean,
        choice: ConflictChoice,
        policy: NameCollisionPolicy,
        encrypted: Boolean
    ): UploadStep =
        when {
            !autoUpload -> UploadStep.NoChange
            policy != NameCollisionPolicy.ASK_USER -> UploadStep.NoChange
            choice in KEEP_CHOICES -> UploadStep.Use(choice.upstreamPolicy())
            choice != ConflictChoice.SMART -> UploadStep.NoChange
            // The server holds ciphertext in an end-to-end encrypted folder, so sizes cannot be compared: keep both.
            encrypted -> UploadStep.Use(NameCollisionPolicy.RENAME)
            else -> UploadStep.CompareSizes
        }

    /** The row says something else than the explicit choice (a stale write): put the choice back into the column. */
    internal fun heal(choice: ConflictChoice, row: PolicyRow, store: ConflictRowStore) {
        val wanted = choice.upstreamPolicy().serialize()
        if (row.policy != wanted) store.setPolicy(row.id, wanted)
    }

    private fun isAutoUpload(createdBy: Int) =
        createdBy == UploadFileOperation.CREATED_AS_INSTANT_PICTURE || createdBy == UploadFileOperation.CREATED_AS_INSTANT_VIDEO

    private fun compare(localPath: String?, remotePath: String, client: OwnCloudClient): SmartOutcome {
        val local = localPath?.let { File(it) }?.takeIf { it.isFile } ?: return SmartOutcome.NO_CHANGE
        return sizeOutcome(local.length(), remoteSize(remotePath, client))
    }

    /** Pure: the Smart rule's answer as the hook's outcome. An unreadable size ends the run with its failure. */
    internal fun sizeOutcome(localBytes: Long, remote: RemoteSize<RemoteOperationResult<*>>): SmartOutcome =
        when (val decision = SmartRule.decide(localBytes, remote)) {
            SmartDecision.AlreadyBackedUp -> {
                Log_OC.d(TAG, "same size on the Drive, counted as backed up")
                SmartOutcome(NameCollisionPolicy.SKIP, null)
            }
            SmartDecision.KeepBoth -> SmartOutcome(NameCollisionPolicy.RENAME, null)
            is SmartDecision.Retry -> SmartOutcome(null, decision.failure)
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
