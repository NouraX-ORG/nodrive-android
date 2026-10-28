/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.work.WorkManager
import com.owncloud.android.ui.activity.FileDisplayActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.nourax.nodrive.backup.BackupTestSupport

/**
 * After a crash with the switch off, the next start asks once: "NoDrive closed unexpectedly. Send a problem report?".
 * The script (tools/ci/reports-device.sh) runs the steps one by one: a1 (account), then it starts the app and runs
 * "adb shell am crash org.nourax.nodrive" (a debug APK has no upstream crash handler, so this tests NoDrive's own
 * recorder), then a2 (the question, "Not now", nothing sent), then a3 (clean up).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReportsPromptIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = ReportsTestSupport()
    private val backup = support.backup

    @Test
    fun a1PrepareAnOfflineAccountWithTheSwitchOff() {
        support.addLocalAccount()
        support.settings.autoSend = false
        assertTrue("nothing is waiting yet: ${support.pendingNames()}", support.pendingNames().none { it.startsWith("crash-") })
    }

    @Test
    fun a2AfterTheCrashTheUserIsAskedOnceAndNotNowSendsNothing() {
        assertTrue("the crash was written down: ${support.pendingNames()}", support.pendingNames().any { it.startsWith("crash-") })
        assertTrue("the switch is off", !support.settings.autoSend)
        ActivityScenario.launch(FileDisplayActivity::class.java).use {
            backup.waitFor("the question", TIMEOUT_MILLIS) {
                onView(withText("NoDrive closed unexpectedly. Send a problem report?")).check(matches(isDisplayed()))
                true
            }
            onView(withText("Send")).check(matches(isDisplayed()))
            onView(withText("Not now")).check(matches(isDisplayed()))
            backup.sleepForAnimation()
            support.screenshot("crash-prompt")
            onView(withText("Not now")).perform(click())
            backup.waitFor("the crash file is gone", TIMEOUT_MILLIS) { true.takeIf { support.pendingNames().isEmpty() } }
            assertEquals("nothing was made", emptyList<String>(), support.outboxNames())
            val runs = WorkManager.getInstance(support.context).getWorkInfosForUniqueWork(ReportUploadWorker.UNIQUE_NAME).get()
            assertEquals("nothing was sent", 0, runs.size)
        }
        ActivityScenario.launch(FileDisplayActivity::class.java).use {
            backup.sleepForAnimation()
            backup.sleepForAnimation()
            onView(withText("NoDrive closed unexpectedly. Send a problem report?")).check(doesNotExist())
        }
    }

    @Test
    fun a3CleanUp() {
        support.settings.autoSend = false
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}
