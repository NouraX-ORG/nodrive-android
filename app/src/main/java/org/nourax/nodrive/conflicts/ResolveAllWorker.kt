/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.User
import com.nextcloud.client.database.entity.UploadEntity
import com.nextcloud.client.jobs.upload.FileUploadHelper
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.UploadsStorageManager.UploadStatus
import com.owncloud.android.db.UploadResult
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.common.utils.Log_OC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies one choice to every waiting conflict (SYNC_CONFLICT) of one account. It never deletes anything: not a phone
 * file, not a Drive file, not an upload row. "Requeue" writes the new policy into the same upload row and starts
 * upstream's own upload job for it, with the phone file always kept (LOCAL_BEHAVIOUR_FORGET).
 */
class ResolveAllWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = inputData.getString(ACCOUNT) ?: return@withContext Result.failure()
        val choice = ConflictChoice.fromKey(inputData.getString(CHOICE))
        if (choice !in ConflictChoice.RESOLVE_ALL_CHOICES) return@withContext Result.failure()
        try {
            resolve(applicationContext, account, choice)
            Result.success()
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "resolve all stopped: ${e.javaClass.simpleName}")
            Result.failure()
        }
    }

    @Suppress("LongMethod")
    private suspend fun resolve(context: Context, account: String, choice: ConflictChoice) {
        ConflictSettings.create(context).ensureMigrated()
        val helper = FileUploadHelper.instance()
        val user = helper.accountManager.getUser(account).orElse(null) ?: return
        val entities = waitingEntities(helper, account)
        val byId = entities.associateBy { it.id?.toLong() }
        val conflicts = entities.map { entity ->
            val file = File(entity.localPath.orEmpty())
            WaitingConflict(
                entity.id?.toLong() ?: -1L,
                entity.localPath.orEmpty(),
                entity.remotePath.orEmpty(),
                file.isFile,
                if (file.isFile) file.length() else 0L,
                FileUploadWorker.isUploading(entity.remotePath, account)
            )
        }
        val client by lazy {
            OwnCloudClientManagerFactory.getDefaultSingleton()
                .getClientFor(OwnCloudAccount(user.toPlatformAccount(), context), context)
        }
        val actions = ResolveAllPlanner.plan(choice, conflicts) { SmartUploads.remoteSize(it.remotePath, client) }
        val requeued = mutableListOf<Long>()
        var left = 0
        actions.forEach { action ->
            val entity = byId[action.uploadId] ?: return@forEach
            when (action) {
                is ResolveAction.MarkDone -> markDone(context, user, helper, entity)
                is ResolveAction.Requeue -> {
                    helper.uploadsStorageManager.uploadDao.insertOrReplace(
                        entity.copy(
                            nameCollisionPolicy = action.policy.serialize(),
                            localBehaviour = FileUploadWorker.LOCAL_BEHAVIOUR_FORGET,
                            status = UploadStatus.UPLOAD_IN_PROGRESS.value,
                            lastResult = UploadResult.UNKNOWN.value
                        )
                    )
                    requeued += action.uploadId
                }
                is ResolveAction.Leave -> {
                    left++
                    Log_OC.d(TAG, "conflict left waiting: ${action.reason}")
                }
            }
        }
        // The files are in Auto upload folders, which the upload job would otherwise hand back to Auto upload.
        if (requeued.isNotEmpty()) {
            helper.backgroundJobManager.startFilesUploadJob(user, requeued.toLongArray(), false, true)
        }
        helper.uploadsStorageManager.notifyObserversNow()
        Log_OC.d(
            TAG,
            "resolve all (${choice.key}): ${actions.size - left - requeued.size} done, ${requeued.size} requeued, $left left"
        )
        ConflictNotifications.refresh(context, account)
    }

    private suspend fun waitingEntities(helper: FileUploadHelper, account: String): List<UploadEntity> =
        helper.uploadsStorageManager.uploadDao
            .getUploadsByAccountNameAndStatus(account, UploadStatus.UPLOAD_FAILED.value, null, Int.MAX_VALUE)
            .filter { it.lastResult == UploadResult.SYNC_CONFLICT.value }

    @Suppress("TooGenericExceptionCaught")
    private fun markDone(context: Context, user: User, helper: FileUploadHelper, entity: UploadEntity) {
        helper.uploadsStorageManager.uploadDao.insertOrReplace(
            entity.copy(
                status = UploadStatus.UPLOAD_SUCCEEDED.value,
                lastResult = UploadResult.SKIPPED.value,
                uploadEndTimestampLong = System.currentTimeMillis()
            )
        )
        try {
            // The file list marks a file that is in conflict: clear it, and the old per-file notification.
            val manager = FileDataStorageManager(user, context.contentResolver)
            entity.remotePath?.let { path -> manager.getFileByPath(path)?.let { manager.saveConflict(it, null) } }
            entity.id?.let { NotificationManagerCompat.from(context).cancel(it) }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "conflict mark not cleared: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACCOUNT = "account"
        const val CHOICE = "choice"
        private const val TAG = "ResolveAllWorker"
    }
}
