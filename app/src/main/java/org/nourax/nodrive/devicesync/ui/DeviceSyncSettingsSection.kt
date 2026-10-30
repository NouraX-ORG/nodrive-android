/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

package org.nourax.nodrive.devicesync.ui

import android.content.Intent
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import org.nourax.nodrive.devicesync.DeviceSyncSettings
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase

/**
 * The row "Calendar and contacts on this phone" at the end of Settings > More (it takes the place of the DAVx5 row, which the nodrive
 * build removes). Its summary says "Off", or how many calendars and address books are on this phone.
 */
object DeviceSyncSettingsSection {
    const val KEY_ROW = "nodrive_device_sync_row"
    private const val KEY_MORE = "more"
    private const val TAG = "DeviceSyncSettings"

    /** Never throws: Settings must open even if this row cannot be built. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun install(activity: PreferenceActivity, screen: PreferenceScreen) {
        try {
            build(activity, screen)
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "device sync row not shown: ${e.javaClass.simpleName}")
        }
    }

    private fun summaryOf(activity: PreferenceActivity): String {
        val user = UserAccountManagerImpl.fromContext(activity).user
        if (user.isAnonymous) return activity.getString(R.string.nodrive_device_sync_off)
        val state = DeviceSyncSettings.get(activity, user.accountName)
        if (!state.enabled) return activity.getString(R.string.nodrive_device_sync_off)
        // Settings reads a few rows from the app's own small database, like the other rows read their values.
        val rows = DeviceSyncDatabase.get(activity).dao().collections(user.accountName).filter { it.chosen }
        return activity.getString(
            R.string.nodrive_device_sync_on_summary,
            rows.count { it.kind == RemoteCollection.CALENDAR }, rows.count { it.kind == RemoteCollection.CONTACTS }
        )
    }

    private fun build(activity: PreferenceActivity, screen: PreferenceScreen) {
        if (!activity.resources.getBoolean(R.bool.nodrive_device_sync)) return
        val more = screen.findPreference(KEY_MORE) as? PreferenceCategory ?: return
        val row = Preference(activity).apply {
            key = KEY_ROW
            setTitle(R.string.nodrive_device_sync_title)
            summary = try {
                summaryOf(activity)
            } catch (e: RuntimeException) {
                activity.getString(R.string.nodrive_device_sync_off)
            }
            setOnPreferenceClickListener {
                val user = UserAccountManagerImpl.fromContext(activity).user
                activity.startActivity(Intent(activity, DeviceSyncActivity::class.java).apply {
                    if (!user.isAnonymous) putExtra("ACCOUNT_NAME", user.accountName)
                })
                true
            }
        }
        more.addPreference(row)
    }
}
