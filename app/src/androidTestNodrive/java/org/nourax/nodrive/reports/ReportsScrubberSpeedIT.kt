/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrubber on the emulator's own java.util.regex. On Android a new Matcher copies the whole text into native memory, so
 * a pass that makes one Matcher per match costs matches times the length of the text; the JVM unit tests cannot see that.
 * The account name of a NoDrive user is an address (user@drive...), and upstream logs it in many lines.
 */
class ReportsScrubberSpeedIT {
    private val account = "alice@drive.example.test"

    private fun bigLog(lines: Int, lineLength: Int): String {
        val builder = StringBuilder(lines * (lineLength + 100))
        val filler = "the sync of the folder went on and the list of files was read from the server again "
        repeat(lines) { number ->
            val start = builder.length
            builder.append("2026-10-29 12:00:00.${number % 1000} D/RefreshFolderOperation: Checking changes in $account/Photos/$number ")
            while (builder.length - start < lineLength) builder.append(filler)
            builder.append("\n")
        }
        return builder.toString()
    }

    @Test
    fun aFourMegabyteLogWithThousandsOfAddressesIsScrubbedInAFewSeconds() {
        val log = bigLog(lines = 5_000, lineLength = 800)
        assertTrue("the test log is ${log.length} characters", log.length >= 3_500_000)
        val started = System.nanoTime()
        val out = Scrubber.scrub(log)
        val millis = (System.nanoTime() - started) / 1_000_000
        Log.i("NoDriveReports", "scrub of ${log.length} characters with 5000 addresses: $millis ms")
        assertFalse("an address survived", out.contains(account))
        assertTrue("addresses were not replaced", out.contains("<email>"))
        assertTrue("scrubbing ${log.length} characters took $millis ms", millis < 20_000)
    }

    @Test
    fun aVeryLongQuotedSecretIsStillRemovedOnAndroid() {
        val secret = "correct horse battery staple ".repeat(7_000) // about 200 KB
        val out = Scrubber.scrub("""before {"password":"$secret","user":"anna"} after password='$secret' end""")
        assertFalse("a word of the secret survived", out.contains("horse"))
        assertTrue(out, out.startsWith("""before {"password":"<removed>","user":"anna"} after password='<removed>' end"""))
    }
}
