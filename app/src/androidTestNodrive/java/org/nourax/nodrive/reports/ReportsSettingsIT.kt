/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.preference.Preference
import android.preference.PreferenceGroup
import android.preference.SwitchPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.PreferenceMatchers.withKey
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.google.android.material.textfield.TextInputLayout
import com.owncloud.android.R
import com.owncloud.android.ui.activity.SettingsActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings > More: the switch (off) and "Report a problem", no "Logs" row, no DAVx5 entry, "Contacts and calendar backup"
 * still there; then the report screen and "What's included". An offline account, no server.
 */
@Suppress("DEPRECATION")
class ReportsSettingsIT {
    private val support = ReportsTestSupport()
    private val backup = support.backup

    @Before
    fun setUp() {
        support.addLocalAccount()
        support.settings.autoSend = false
    }

    @After
    fun tearDown() {
        support.settings.autoSend = false
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
    }

    private fun allPreferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).flatMap { index ->
            val item = group.getPreference(index)
            if (item is PreferenceGroup) listOf(item) + allPreferences(item) else listOf(item)
        }

    @Test
    fun theSwitchIsOffByDefaultAndTheReportRowIsNextToTheOtherItems() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val switch = activity.findPreference(ReportsSettingsSection.KEY_SWITCH) as SwitchPreference?
                assertNotNull("the switch is in Settings", switch)
                assertEquals("Send problem reports to Nourax", switch!!.title.toString())
                assertEquals(
                    "If the app has a problem, send a report to Nourax Support so we can fix it. Reports are saved in " +
                        "your NoDrive and shared with Nourax Support. They never include passwords.",
                    switch.summary.toString()
                )
                assertFalse("off by default", switch.isChecked)
                val report = activity.findPreference(ReportsSettingsSection.KEY_REPORT)
                assertNotNull("the report row is in Settings", report)
                assertEquals("Report a problem", report.title.toString())
                val more = activity.findPreference("more") as PreferenceGroup
                assertTrue("both rows are in More", allPreferences(more).any { it.key == ReportsSettingsSection.KEY_SWITCH })
            }
            onData(withKey(ReportsSettingsSection.KEY_SWITCH)).check(matches(isDisplayed()))
            backup.sleepForAnimation()
            support.screenshot("settings")

            // The switch saves its state and is turned off again at the end.
            onData(withKey(ReportsSettingsSection.KEY_SWITCH)).perform(click())
            backup.waitFor("the switch is saved on", TIMEOUT_MILLIS) { true.takeIf { support.settings.autoSend } }
            onData(withKey(ReportsSettingsSection.KEY_SWITCH)).perform(click())
            backup.waitFor("the switch is saved off", TIMEOUT_MILLIS) { true.takeIf { !support.settings.autoSend } }
        }
    }

    @Test
    fun noLogsRowAndNoDavx5EntryAndTheCalendarAndContactsBackupStays() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull("no Logs row (logs leave the phone only inside scrubbed reports)", activity.findPreference("logger"))
                assertNull("no 'Sync calendar and contacts' row", activity.findPreference("calendar_contacts"))
                val screen = activity.preferenceScreen
                val texts = allPreferences(screen).flatMap { listOf(it.title, it.summary) }.filterNotNull().map { it.toString() }
                assertTrue(
                    "no row names DAVx5 or DAVdroid: ${texts.filter { it.contains("DAV", ignoreCase = true) }}",
                    texts.none { it.contains("DAVx", ignoreCase = true) || it.contains("DAVdroid", ignoreCase = true) }
                )
                val backupRow = activity.findPreference("backup")
                assertNotNull("'Contacts and calendar backup' stays", backupRow)
                assertEquals("Contacts and calendar backup", backupRow.title.toString())
            }
        }
    }

    @Test
    fun theReportScreenAndWhatIsIncluded() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onData(withKey(ReportsSettingsSection.KEY_REPORT)).perform(click())
            backup.waitFor("the report screen", TIMEOUT_MILLIS) {
                onView(withText("What's included")).check(matches(isDisplayed()))
                true
            }
            onView(withText("Send")).check(matches(isDisplayed()))
            backup.sleepForAnimation()
            support.screenshot("report")
            val hint = backup.waitFor("the report screen", TIMEOUT_MILLIS) {
                backup.resumed<ReportProblemActivity>()
            }.findViewById<TextInputLayout>(R.id.nodrive_report_note_layout).hint.toString()
            assertEquals("What went wrong?", hint)

            onView(withText("What's included")).perform(click())
            backup.waitFor("the list", TIMEOUT_MILLIS) {
                onView(withText(containsString("Never included: passwords, app passwords, sign-in tokens and cookies"))).check(matches(isDisplayed()))
                true
            }
            onView(withText(containsString("names of your files and folders"))).check(matches(isDisplayed()))
            backup.sleepForAnimation()
            support.screenshot("included")
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
    }
}
