/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

/**
 * What happens to a crash or an unclean exit that was written down, as plain logic with every side effect handed in, so
 * the unit tests can check it without a phone:
 *  - switch on: the report is made and sent at once (at start, or at the first main screen after the switch was turned on);
 *  - switch off: the user is asked once per problem ("NoDrive closed unexpectedly. Send a problem report?");
 *  - "Not now" sends nothing;
 *  - no signed-in account: the report waits in the outbox (the newest 5) and goes out after the next sign-in.
 */
class ReportFlow(
    private val files: ReportFiles,
    private val autoSend: () -> Boolean,
    private val askedFor: () -> String?,
    private val rememberAsked: (String) -> Unit,
    /** Makes a scrubbed zip in the outbox from the raw file; false when that did not work. */
    private val makeReport: (PendingReport) -> Boolean,
    private val startUpload: () -> Unit,
    private val signedIn: () -> Boolean
) {
    private val pending get() = PendingStore(files)

    /** At app start, in the background: with the switch on, a waiting problem is reported now. */
    fun atStart() {
        if (autoSend()) sendPending()
    }

    /**
     * The main screen is visible. Sends the outbox when [flushOutbox] and an account is signed in, then returns the
     * problem to ask about, or null when there is nothing to ask (switch on, nothing waiting, already asked, or [mayAsk] is
     * false because another dialog is in the way: the question then waits for the next time and is not counted as asked).
     */
    fun atMainScreen(flushOutbox: Boolean, mayAsk: Boolean = true): PendingReport? {
        if (flushOutbox && signedIn() && Outbox(files).list().isNotEmpty()) startUpload()
        val newest = pending.newest() ?: return null
        if (autoSend()) {
            sendPending()
            return null
        }
        if (!mayAsk || askedFor() == newest.name) return null
        rememberAsked(newest.name)
        return newest
    }

    /** The user answered "Send" to the question. */
    fun send() = sendPending()

    /** The user answered "Not now" (or closed the question): nothing is sent, the raw files go. */
    fun notNow() = pending.consume()

    private fun sendPending() {
        val newest = pending.newest() ?: return
        if (makeReport(newest)) {
            pending.consume()
            if (signedIn()) startUpload()
        }
    }
}
