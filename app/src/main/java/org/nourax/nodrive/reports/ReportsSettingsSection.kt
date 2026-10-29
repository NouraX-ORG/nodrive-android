/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

package org.nourax.nodrive.reports

import android.content.Intent
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceScreen
import android.preference.SwitchPreference
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.ThemeableSwitchPreference

/**
 * The two rows of the problem reports at the end of Settings > More: the switch "Send problem reports to Nourax" and the
 * button "Report a problem". It also takes away upstream's "Logs" row (turned on again by the nodrive flavour so reports
 * have logs): logs leave the phone only inside scrubbed reports.
 */
object ReportsSettingsSection {
    const val KEY_SWITCH = "nodrive_reports_switch"
    const val KEY_REPORT = "nodrive_reports_report"
    private const val KEY_MORE = "more"
    private const val KEY_LOGGER = "logger"
    private const val TAG = "ReportsSettingsSection"

    /** Never throws: Settings must open even if these rows cannot be built. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun install(activity: PreferenceActivity, screen: PreferenceScreen) {
        try {
            build(activity, screen)
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "problem report rows not shown: ${e.javaClass.simpleName}")
        }
    }

    private fun build(activity: PreferenceActivity, screen: PreferenceScreen) {
        if (!activity.resources.getBoolean(R.bool.nodrive_reports)) return
        val more = screen.findPreference(KEY_MORE) as? PreferenceCategory ?: return
        (more.findPreference(KEY_LOGGER) ?: screen.findPreference(KEY_LOGGER))?.let { more.removePreference(it) }

        val settings = ReportSettings.create(activity)
        val switch: SwitchPreference = ThemeableSwitchPreference(activity).apply {
            key = KEY_SWITCH
            setTitle(R.string.nodrive_reports_switch_title)
            setSummary(R.string.nodrive_reports_switch_summary)
            isPersistent = false
            isChecked = settings.autoSend
            setOnPreferenceChangeListener { _, newValue ->
                settings.autoSend = newValue == true
                true
            }
        }
        more.addPreference(switch)
        val report = Preference(activity).apply {
            key = KEY_REPORT
            setTitle(R.string.nodrive_reports_report_title)
            setOnPreferenceClickListener {
                activity.startActivity(Intent(activity, ReportProblemActivity::class.java))
                true
            }
        }
        more.addPreference(report)
    }
}
