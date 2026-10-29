/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.android

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
import kotlinx.coroutines.CancellationException
import org.nourax.nodrive.agenda.AgendaAccess
import org.nourax.nodrive.agenda.AgendaAccounts
import org.nourax.nodrive.agenda.AgendaLog
import java.security.MessageDigest

/**
 * Sends the changes that wait in the queue (design 9, plan 2.2) as soon as the phone is online, also when NoAgenda is closed. It
 * is queued when a change could not be sent. If the account is gone it forgets the account's rows and stops.
 */
class AgendaSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val account = inputData.getString(KEY_ACCOUNT).orEmpty()
        if (account.isEmpty()) return Result.success()
        val user = UserAccountManagerImpl.fromContext(applicationContext).getUser(account).orElse(null)
        if (user == null) {
            AgendaAccounts.forget(applicationContext, account)
            return Result.success()
        }
        return try {
            val repository = AgendaAccess.repository(applicationContext, user)
            repository.sendPending(manual = true)
            // Still something waiting (the server did not answer): try again later.
            if (repository.state.value?.edit?.pending?.isNotEmpty() == true) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgendaLog.w(TAG, "send failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AgendaSendWorker"
        const val KEY_ACCOUNT = "account"
        private const val NAME_PREFIX = "nodrive-agenda-send-"
        private const val HASH_CHARS = 16

        fun uniqueName(account: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            return NAME_PREFIX + digest.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }.take(HASH_CHARS)
        }

        /** One send job per account, run when the phone is online. A second request while one waits is queued after it. */
        fun enqueue(context: Context, account: String) {
            val request = OneTimeWorkRequestBuilder<AgendaSendWorker>()
                .setInputData(Data.Builder().putString(KEY_ACCOUNT, account).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(account), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context, account: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(account))
        }
    }
}
