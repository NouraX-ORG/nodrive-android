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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nextcloud.client.account.UserAccountManagerImpl
import kotlinx.coroutines.CancellationException
import org.nourax.nodrive.agenda.AgendaAccess
import org.nourax.nodrive.agenda.AgendaAccounts
import org.nourax.nodrive.agenda.AgendaLog
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The background refresh of one account (design 9: about every 15 minutes, the platform minimum). It refreshes the
 * saved copy only and posts no notification. It is scheduled only after NoAgenda was opened for that account, so
 * people who never use it cause no server load and no battery use. If the account is gone it deletes the
 * account's rows and stops.
 */
class AgendaRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
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
            AgendaAccess.repository(applicationContext, user).refresh(manual = false)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgendaLog.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            Result.success() // the next run tries again; the saved copy stays
        }
    }

    companion object {
        private const val TAG = "AgendaRefreshWorker"
        const val KEY_ACCOUNT = "account"
        private const val PERIOD_MINUTES = 15L
        private const val NAME_PREFIX = "nodrive-agenda-refresh-"
        private const val HASH_CHARS = 16

        fun uniqueName(account: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            return NAME_PREFIX + digest.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }.take(HASH_CHARS)
        }

        /** Keeps one periodic run per account (an existing one is updated, never doubled). */
        fun schedule(context: Context, account: String) {
            val request = PeriodicWorkRequestBuilder<AgendaRefreshWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString(KEY_ACCOUNT, account).build())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(uniqueName(account), ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context, account: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(account))
        }
    }
}
