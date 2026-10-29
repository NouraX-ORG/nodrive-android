/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import com.owncloud.android.R

/** Which text tells the result of an upload run. Pure, so the unit tests can check it. */
object ReportMessages {
    /** The text for the outcome of a run, or null when there is nothing to tell. */
    fun forOutcome(outcome: String?): Int? = when (outcome) {
        ReportUploadWorker.OUTCOME_SENT -> R.string.nodrive_reports_sent
        ReportUploadWorker.OUTCOME_SAVED -> R.string.nodrive_reports_saved
        ReportUploadWorker.OUTCOME_WAITING -> R.string.nodrive_reports_waiting
        else -> null
    }

    /**
     * The text for the report screen when its watched run is done. A run that finds the outbox empty ("nothing") means an
     * earlier run already sent this screen's zip (a second Send, or an automatic crash run, was queued meanwhile): the
     * report is then in the Problem reports folder, so the "saved" text is true. [zipName] is the zip this screen made, or
     * null when the screen was recreated after the process was gone; then an empty outbox counts as sent.
     */
    fun forScreen(outcome: String?, zipName: String?, outboxNames: List<String>): Int? =
        if (outcome == ReportUploadWorker.OUTCOME_NOTHING) {
            val stillWaiting = if (zipName != null) zipName in outboxNames else outboxNames.isNotEmpty()
            if (stillWaiting) R.string.nodrive_reports_waiting else R.string.nodrive_reports_saved
        } else {
            forOutcome(outcome)
        }
}
