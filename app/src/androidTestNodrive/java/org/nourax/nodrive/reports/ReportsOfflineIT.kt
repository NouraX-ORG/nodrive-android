/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.nourax.nodrive.backup.BackupTestSupport

/**
 * "Report a problem" without a network. The script (tools/ci/reports-device.sh) turns mobile data off before c1 and on again
 * before c2. The upload run needs a network, so it stays queued; the screen must say that the report is saved on the phone
 * and let the user send again, and the report must stay in the outbox.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReportsOfflineIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = ReportsTestSupport()
    private val backup = support.backup

    private fun runs() = WorkManager.getInstance(support.context).getWorkInfosForUniqueWork(ReportUploadWorker.UNIQUE_NAME).get()

    @Test
    fun c1WithoutANetworkTheScreenSaysTheReportIsSavedOnThePhone() {
        val connectivity = support.context.getSystemService(ConnectivityManager::class.java)
        backup.waitFor("no network (the script turned mobile data off)", NETWORK_TIMEOUT_MILLIS) {
            true.takeIf { connectivity.activeNetwork == null }
        }
        clearOutboxAndWork()
        support.addLocalAccount()
        support.settings.autoSend = false
        ActivityScenario.launch(ReportProblemActivity::class.java).use {
            backup.sleepForAnimation()
            support.typeNoteAndSend("report without a network")
            support.waitForResult(WAITING_TEXT)
            onView(withText("Send")).check(matches(isEnabled()))
            backup.sleepForAnimation()
            support.screenshot("offline")
        }
        assertEquals("the report stays in the outbox: ${support.outboxNames()}", 1, support.outboxNames().size)
        assertTrue("the run is still queued: ${runs().map { it.state }}", runs().any { it.state == WorkInfo.State.ENQUEUED })
    }

    private fun clearOutboxAndWork() {
        WorkManager.getInstance(support.context).cancelUniqueWork(ReportUploadWorker.UNIQUE_NAME).result.get()
        Outbox(support.files).list().forEach { support.files.deleteOwn(it) }
    }

    @Test
    fun c2CleanUp() {
        clearOutboxAndWork()
        support.settings.autoSend = false
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
        assertTrue("the outbox is empty", support.outboxNames().isEmpty())
    }

    private companion object {
        const val WAITING_TEXT = "The report is saved on this phone. NoDrive sends it as soon as it can reach your server."
        const val NETWORK_TIMEOUT_MILLIS = 60_000L
    }
}
