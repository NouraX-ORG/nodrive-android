/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.owncloud.android.R
import com.owncloud.android.ui.notifications.NotificationUtils
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import org.nourax.nodrive.devicesync.ui.DeviceSyncActivity

/** "N changes on this phone weren't saved because they also changed in NoDrive" (plan 4.5). Names, counts, no content. */
object DeviceSyncNotifier {
    private const val TAG = "nodrive-device-sync"
    private const val ID = 4411

    @Suppress("TooGenericExceptionCaught")
    fun notSaved(context: Context, account: String) {
        try {
            val count = DeviceSyncDatabase.get(context).dao().notSaved(account).size
            val manager = NotificationManagerCompat.from(context)
            if (count <= 0) {
                manager.cancel(TAG, ID)
                return
            }
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val text = context.resources.getQuantityString(R.plurals.nodrive_device_sync_not_saved_notification, count, count)
            val open = PendingIntent.getActivity(
                context, ID, DeviceSyncActivity.intent(context, account), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_UPLOAD)
                .setSmallIcon(R.drawable.uploads)
                .setContentTitle(context.getString(R.string.nodrive_device_sync_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(TAG, ID, notification)
        } catch (_: RuntimeException) {
            // a missing notification must never stop a sync
        }
    }
}
