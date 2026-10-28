/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

package org.nourax.nodrive.conflicts

import android.app.Activity
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import kotlin.concurrent.thread

/** "If a file is already in NoDrive" in the Backup section of Settings (round 3 contract, A). One choice for every folder the backup owns. */
object ConflictSettingsItem {
    const val KEY = "nodrive_conflict_choice_setting"
    private const val TAG = "ConflictSettingsItem"

    /** Adds the row to [category] and returns it (null when the feature is off), so the section can refresh it. */
    fun addTo(activity: PreferenceActivity, category: PreferenceCategory, account: String): Preference? {
        if (!activity.resources.getBoolean(R.bool.nodrive_conflicts)) return null
        val item = Preference(activity).apply {
            key = KEY
            setTitle(R.string.nodrive_conflicts_setting_title)
            isPersistent = false
            setOnPreferenceClickListener {
                choose(activity, this, account)
                true
            }
        }
        category.addPreference(item)
        refresh(activity, item, account)
        return item
    }

    /** Shows the saved choice as the summary. */
    @Suppress("TooGenericExceptionCaught")
    fun refresh(activity: Activity, item: Preference, account: String) {
        val choice = try {
            ConflictSettings.create(activity).choice(account)
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "choice not read: ${e.javaClass.simpleName}")
            ConflictChoice.SMART
        }
        item.summary = ChoiceDialog.label(activity, choice)
    }

    private fun choose(activity: Activity, item: Preference, account: String) {
        val current = ConflictSettings.create(activity).choice(account)
        ChoiceDialog.show(
            activity,
            R.string.nodrive_conflicts_setting_title,
            ConflictChoice.entries,
            current,
            null,
            R.string.common_cancel
        ) { choice -> save(activity, item, account, choice) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun save(activity: Activity, item: Preference, account: String, choice: ConflictChoice) {
        // The rows of the account are rewritten, so not on the main thread.
        thread(name = "nodrive-conflict-choice") {
            try {
                ConflictSettings.create(activity).setChoice(account, choice)
            } catch (e: RuntimeException) {
                Log_OC.w(TAG, "choice not saved: ${e.javaClass.simpleName}")
            }
            activity.runOnUiThread { refresh(activity, item, account) }
        }
    }
}
