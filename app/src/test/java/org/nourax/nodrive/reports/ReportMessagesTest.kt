/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import com.owncloud.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text the report screen shows when its run is done. */
class ReportMessagesTest {
    private val zip = "2026-10-28_13-05-09_android_35.0.1-nourax.1.zip"

    @Test
    fun theOutcomesOfARunHaveTheirOwnTexts() {
        assertEquals(R.string.nodrive_reports_sent, ReportMessages.forOutcome(ReportUploadWorker.OUTCOME_SENT))
        assertEquals(R.string.nodrive_reports_saved, ReportMessages.forOutcome(ReportUploadWorker.OUTCOME_SAVED))
        assertEquals(R.string.nodrive_reports_waiting, ReportMessages.forOutcome(ReportUploadWorker.OUTCOME_WAITING))
        assertNull(ReportMessages.forOutcome(ReportUploadWorker.OUTCOME_NOTHING))
        assertNull(ReportMessages.forOutcome(null))
    }

    @Test
    fun aRunThatFoundNothingMeansAnEarlierRunSentThisReport() {
        val nothing = ReportUploadWorker.OUTCOME_NOTHING
        assertEquals(R.string.nodrive_reports_saved, ReportMessages.forScreen(nothing, zip, emptyList()))
        assertEquals(R.string.nodrive_reports_saved, ReportMessages.forScreen(nothing, zip, listOf("other.zip")))
    }

    @Test
    fun aRunThatFoundNothingWhileThisZipIsStillThereKeepsWaiting() {
        val nothing = ReportUploadWorker.OUTCOME_NOTHING
        assertEquals(R.string.nodrive_reports_waiting, ReportMessages.forScreen(nothing, zip, listOf(zip)))
    }

    @Test
    fun withoutAKnownZipAnEmptyOutboxCountsAsSent() {
        val nothing = ReportUploadWorker.OUTCOME_NOTHING
        assertEquals(R.string.nodrive_reports_saved, ReportMessages.forScreen(nothing, null, emptyList()))
        assertEquals(R.string.nodrive_reports_waiting, ReportMessages.forScreen(nothing, null, listOf(zip)))
    }

    @Test
    fun otherOutcomesAreShownAsTheyAre() {
        assertEquals(R.string.nodrive_reports_sent, ReportMessages.forScreen(ReportUploadWorker.OUTCOME_SENT, zip, listOf(zip)))
        assertEquals(R.string.nodrive_reports_waiting, ReportMessages.forScreen(ReportUploadWorker.OUTCOME_WAITING, zip, listOf(zip)))
    }
}
