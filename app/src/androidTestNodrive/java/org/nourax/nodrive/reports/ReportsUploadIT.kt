/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import androidx.test.core.app.ActivityScenario
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.FileDisplayActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.nourax.nodrive.backup.BackupTestSupport

/**
 * The real thing against the throwaway Nextcloud of the CI job (never drive.nourax.org). The script runs the steps one by
 * one and does the server side in between (it creates the user "nourax-support" after b1, and it crashes the app between
 * b4 and b5). All secrets here are made up; the script checks the files on the server for them.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReportsUploadIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = ReportsTestSupport()
    private val backup = support.backup

    private fun needServer() = assumeTrue("needs the CI test server", support.hasServer)

    private fun plantSecretsInTheLog() {
        listOf(
            "request Authorization: Bearer PLANTEDBEARER0123456789abcdefghijklmnop",
            "cookies Cookie: oc_sessionPassphrase=PLANTEDPASSPHRASE; nc_session_id=PLANTEDSESSIONID",
            "GET /index.php/apps/files/ajax?token=PLANTEDQUERYTOKEN&dir=/Photos",
            "app password Pl4nt-Edapp-Pass1-Word2-Xyz12 was typed",
            "contact planted.person@example.org about the problem",
            "uploading /Phone backup/Camera/nodrive-rp-photo-123.jpg"
        ).forEach { Log_OC.d(TAG, it) }
        backup.waitFor("the log file has the planted lines", LOG_TIMEOUT_MILLIS) {
            true.takeIf { support.logText().contains("nodrive-rp-photo-123.jpg") }
        }
    }

    private fun sendFromTheReportScreen(note: String, expected: String, screenshot: String? = null) {
        ActivityScenario.launch(ReportProblemActivity::class.java).use {
            backup.sleepForAnimation()
            support.typeNoteAndSend(note)
            support.waitForResult(expected)
            backup.sleepForAnimation()
            screenshot?.let { support.screenshot(it) }
        }
    }

    @Test
    fun b1TheFirstReportIsSavedWhenTheSupportUserDoesNotExistYet() {
        needServer()
        support.addServerAccount()
        support.settings.autoSend = false
        plantSecretsInTheLog()
        sendFromTheReportScreen(
            "first report, my password=PLANTEDNOTEPW and mail note.person@example.org",
            "Saved in your NoDrive › Problem reports.",
            "saved"
        )
    }

    @Test
    fun b2TheSecondReportIsSentOnceTheSupportUserExists() {
        needServer()
        support.addServerAccount()
        sendFromTheReportScreen("second report", "Problem report sent", "sent")
    }

    @Test
    fun b3TheThirdReportMakesNoNewShareRequest() {
        needServer()
        support.addServerAccount()
        sendFromTheReportScreen("third report", "Problem report sent")
    }

    @Test
    fun b4PrepareTheAutomaticSend() {
        needServer()
        support.addServerAccount()
        support.settings.autoSend = true
        assertTrue(support.pendingNames().none { it.startsWith("crash-") })
    }

    @Test
    fun b5TheCrashReportArrivedWithoutAQuestion() {
        needServer()
        assertTrue("the switch is on", support.settings.autoSend)
        ActivityScenario.launch(FileDisplayActivity::class.java).use {
            support.waitForResult("Problem report sent")
            backup.sleepForAnimation()
            support.screenshot("auto-sent")
        }
        assertTrue("the crash file is gone: ${support.pendingNames()}", support.pendingNames().isEmpty())
        support.settings.autoSend = false
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
    }

    private companion object {
        const val TAG = "ReportsUploadIT"
        const val LOG_TIMEOUT_MILLIS = 30_000L
    }
}
