/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitClassifierTest {
    private val last = 1_000L

    // The ApplicationExitInfo.REASON_* values (Android 11).
    private val lowMemory = 3
    private val userRequested = 10
    private val signaled = 2
    private val exitSelf = 1
    private val dependencyDied = 12
    private val permissionChange = 8
    private val other = 13
    private val packageUpdated = 16

    private fun record(reason: Int, at: Long) = ExitRecord(reason, at, "d$reason")

    @Test
    fun onlyCrashNativeCrashAndAnrAreAbnormal() {
        assertTrue(ExitClassifier.isAbnormal(ExitClassifier.REASON_CRASH))
        assertTrue(ExitClassifier.isAbnormal(ExitClassifier.REASON_CRASH_NATIVE))
        assertTrue(ExitClassifier.isAbnormal(ExitClassifier.REASON_ANR))
        listOf(lowMemory, userRequested, signaled, exitSelf, dependencyDied, permissionChange, other, packageUpdated, 0, 7, 9, 11, 14, 15)
            .forEach { assertFalse("reason $it must be ignored", ExitClassifier.isAbnormal(it)) }
    }

    @Test
    fun everyAbnormalReasonGivesAnUncleanExit() {
        listOf(ExitClassifier.REASON_CRASH, ExitClassifier.REASON_CRASH_NATIVE, ExitClassifier.REASON_ANR).forEach { reason ->
            val finding = ExitClassifier.classify(listOf(record(reason, 2_000L)), last, crashFileExists = false)
            assertEquals(ReportKind.UNCLEAN_EXIT, finding?.kind)
            assertEquals(reason, finding?.record?.reason)
        }
    }

    @Test
    fun ignoredReasonsGiveNothing() {
        val records = listOf(lowMemory, userRequested, signaled, exitSelf, packageUpdated).map { record(it, 2_000L) }
        assertNull(ExitClassifier.classify(records, last, crashFileExists = false))
    }

    @Test
    fun oldRecordsAreNotReportedAgain() {
        assertNull(ExitClassifier.classify(listOf(record(ExitClassifier.REASON_ANR, last)), last, false))
        assertNull(ExitClassifier.classify(listOf(record(ExitClassifier.REASON_ANR, last - 1)), last, false))
    }

    @Test
    fun theFirstStartOfThisVersionReportsNothingOlder() {
        assertNull(ExitClassifier.classify(listOf(record(ExitClassifier.REASON_CRASH_NATIVE, 5_000L)), 0L, false))
    }

    @Test
    fun theNewestAbnormalRecordIsTheOneReported() {
        val finding = ExitClassifier.classify(
            listOf(
                record(ExitClassifier.REASON_ANR, 2_000L),
                record(lowMemory, 4_000L),
                record(ExitClassifier.REASON_CRASH_NATIVE, 3_000L)
            ),
            last,
            false
        )
        assertEquals(3_000L, finding?.record?.timestamp)
    }

    @Test
    fun aCrashFileAndTheSystemsCrashRecordMakeOneReportOfKindCrash() {
        val finding = ExitClassifier.classify(listOf(record(ExitClassifier.REASON_CRASH, 2_000L)), last, crashFileExists = true)
        assertEquals(ReportKind.CRASH, finding?.kind)
        assertNull("the crash file is the report, no exit text is added", finding?.record)
    }

    @Test
    fun aJavaCrashWithoutACrashFileIsAnUncleanExit() {
        val finding = ExitClassifier.classify(listOf(record(ExitClassifier.REASON_CRASH, 2_000L)), last, crashFileExists = false)
        assertEquals(ReportKind.UNCLEAN_EXIT, finding?.kind)
    }

    @Test
    fun theExitTextNamesTheReasonAndTime() {
        val text = ExitCheck.describe(ExitRecord(ExitClassifier.REASON_ANR, 1_793_192_709_000L, "input dispatching timed out", 100, trace = { "main trace" }))
        assertTrue(text.contains("Reason: ANR"))
        assertTrue(text.contains("Time: 2026-10-28T13:05:09Z"))
        assertTrue(text.contains("input dispatching timed out"))
        assertTrue(text.contains("main trace"))
    }
}
