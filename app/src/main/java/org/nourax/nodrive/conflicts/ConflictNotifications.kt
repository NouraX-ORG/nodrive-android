/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nextcloud.client.jobs.upload.FileUploadHelper
import com.nextcloud.client.notifications.AppWideNotificationManager
import com.owncloud.android.R
import com.owncloud.android.datamodel.UploadsStorageManager.UploadStatus
import com.owncloud.android.db.UploadResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.notifications.NotificationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * ONE notification per account for all waiting conflicts ("N files are already in NoDrive", with a "Resolve all" action),
 * instead of upstream's one notification per file. Its key is the pair (tag, id): the tag is ours, so it never collides
 * with upstream's per-upload ids.
 */
object ConflictNotifications {
    const val TAG = "nodrive_conflicts"
    private const val UPSTREAM_APP_WIDE_CONFLICT_ID = 112
    private const val NOTIFICATION_TAG_LOG = "ConflictNotifications"

    private fun enabled(context: Context) = context.resources.getBoolean(R.bool.nodrive_conflicts)

    private fun idOf(account: String) = account.hashCode()

    /** Ids of the uploads that wait for a decision: status "failed", last result SYNC_CONFLICT. */
    suspend fun waitingIds(account: String): Set<Long> = withContext(Dispatchers.IO) {
        FileUploadHelper.instance().uploadsStorageManager.uploadDao
            .getUploadsByAccountNameAndStatus(account, UploadStatus.UPLOAD_FAILED.value, null, Int.MAX_VALUE)
            .filter { it.lastResult == UploadResult.SYNC_CONFLICT.value }
            .mapNotNull { it.id?.toLong() }
            .toSet()
    }

    /** For background threads only (it waits for the database). */
    fun countWaiting(account: String): Int = runBlocking { waitingIds(account).size }

    /**
     * Hook for upstream's per-file conflict notification: posts or updates the one summary and returns true, so the
     * per-file notification is not shown. Returns false (upstream shows its own) when the feature is off or it failed.
     * [uploadId] is counted even if its row is not saved yet.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun showSummary(context: Context, account: String, uploadId: Long): Boolean {
        if (!enabled(context)) return false
        return try {
            post(context, account, (waitingIds(account) + uploadId).size)
            true
        } catch (e: RuntimeException) {
            Log_OC.w(NOTIFICATION_TAG_LOG, "summary not shown: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Counts again: updates the summary, or removes it when nothing waits. Creates it only when [createIfMissing]. */
    @Suppress("TooGenericExceptionCaught")
    fun refresh(context: Context, account: String, createIfMissing: Boolean = true) {
        if (!enabled(context)) return
        try {
            val count = countWaiting(account)
            if (count <= 0) {
                NotificationManagerCompat.from(context).cancel(TAG, idOf(account))
            } else if (createIfMissing || isActive(context, account)) {
                post(context, account, count)
            }
        } catch (e: RuntimeException) {
            Log_OC.w(NOTIFICATION_TAG_LOG, "summary not refreshed: ${e.javaClass.simpleName}")
        }
    }

    fun refreshAsync(context: Context, account: String) {
        thread(name = "nodrive-conflict-refresh") { refresh(context.applicationContext, account) }
    }

    /** Hook after upstream's per-file resolve: the count may have changed. Never creates a notification. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        thread(name = "nodrive-conflict-refresh") {
            try {
                FileUploadHelper.instance().accountManager.allUsers.filterNotNull().forEach {
                    refresh(app, it.accountName, createIfMissing = false)
                }
            } catch (e: RuntimeException) {
                Log_OC.w(NOTIFICATION_TAG_LOG, "summaries not refreshed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * After an update: the previous version showed one notification per waiting file (no tag, the upload id as id). Those
     * are removed and the one summary is posted instead.
     */
    fun replaceOldPerFileNotifications(context: Context) {
        if (!enabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        FileUploadHelper.instance().accountManager.allUsers.filterNotNull().forEach { user ->
            val ids = runBlocking { waitingIds(user.accountName) }
            val old = manager.activeNotifications.filter {
                it.tag == null && it.id.toLong() in ids && it.id != UPSTREAM_APP_WIDE_CONFLICT_ID &&
                    it.notification.channelId == NotificationUtils.NOTIFICATION_CHANNEL_UPLOAD
            }
            old.forEach { manager.cancel(it.tag, it.id) }
            if (old.isNotEmpty()) refresh(context, user.accountName)
        }
    }

    private fun isActive(context: Context, account: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.activeNotifications.any { it.tag == TAG && it.id == idOf(account) }
    }

    private fun post(context: Context, account: String, count: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (count <= 0) {
            manager.cancel(TAG, idOf(account))
            return
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log_OC.w(NOTIFICATION_TAG_LOG, "no notification permission, conflict summary not shown")
            return
        }
        val text = context.getString(R.string.nodrive_conflicts_notification_text)
        val notification = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_UPLOAD)
            .setSmallIcon(R.drawable.uploads)
            .setContentTitle(context.resources.getQuantityString(R.plurals.nodrive_conflicts_notification_title, count, count))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(
                R.drawable.ic_cloud_upload,
                context.getString(R.string.nodrive_conflicts_notification_action),
                ResolveAll.pendingIntent(context, account)
            )
            .setContentIntent(AppWideNotificationManager.getUploadListPendingIntent(context))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(TAG, idOf(account), notification)
    }
}
