/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.common.utils.Log_OC
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Sends the reports of the outbox, oldest first, through the signed-in account (see [ReportSender]). One run per request,
 * never periodic. A network problem gives [MAX_ATTEMPTS] tries; after that the zip waits in the outbox for the next start.
 */
class ReportUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val files = ReportFiles.of(context)
        val zips = Outbox(files).list()
        if (zips.isEmpty()) return@withContext Result.success(outcome(OUTCOME_NOTHING))
        val run = try {
            val user = UserAccountManagerImpl.fromContext(context).user
            if (user.isAnonymous) return@withContext Result.success(outcome(OUTCOME_WAITING))
            val client = OwnCloudClientManagerFactory.getDefaultSingleton()
                .getClientFor(OwnCloudAccount(user.toPlatformAccount(), context), context)
            ReportSender(WebDavReportServer(client)).sendAll(zips) { files.deleteOwn(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log_OC.w(TAG, "reports not sent: ${e.javaClass.simpleName}")
            SendRun(0, 0, retry = true, sharedWithSupport = false)
        }
        when {
            run.retry && runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            run.sent > 0 && run.sharedWithSupport -> Result.success(outcome(OUTCOME_SENT))
            run.sent > 0 -> Result.success(outcome(OUTCOME_SAVED))
            else -> Result.success(outcome(OUTCOME_WAITING))
        }
    }

    private fun outcome(value: String): Data = Data.Builder().putString(OUTCOME, value).build()

    companion object {
        const val OUTCOME = "outcome"

        /** At least one report arrived and the folder is shared with Nourax Support: "Problem report sent". */
        const val OUTCOME_SENT = "sent"

        /** A report arrived but the share failed: "Saved in your NoDrive > Problem reports." */
        const val OUTCOME_SAVED = "saved"

        /** Nothing arrived (no account, no network, the server said no): the report stays on the phone. */
        const val OUTCOME_WAITING = "waiting"
        const val OUTCOME_NOTHING = "nothing"
        const val UNIQUE_NAME = "nodrive_report_upload"
        private const val MAX_ATTEMPTS = 3
        private const val TAG = "ReportUploadWorker"

        /** Queues one run (after the one that may be running). Returns the id to watch. */
        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            return request.id
        }
    }
}
