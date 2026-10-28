/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

/** One finished process as the system remembers it (a copy of the fields of ApplicationExitInfo that matter). */
data class ExitRecord(
    val reason: Int,
    val timestamp: Long,
    val description: String? = null,
    val importance: Int = 0,
    /** Reads the trace of an ANR, only when it is needed. */
    val trace: (() -> String?)? = null
)

/** What a look at the finished processes found. [record] is null for [ReportKind.CRASH], which has its own crash file. */
data class ExitFinding(val kind: ReportKind, val record: ExitRecord?)

/**
 * Decides which finished processes count as an unclean exit. Android ends app processes all the time without any
 * callback (low memory, the user swiping the app away), so only what the system itself records as abnormal counts:
 * a native crash, an ANR, and a Java crash for which no crash file was written. Everything else is ignored.
 */
object ExitClassifier {
    // The values of ApplicationExitInfo.REASON_CRASH, REASON_CRASH_NATIVE and REASON_ANR (Android 11), copied here so
    // this class loads on every Android version and in unit tests.
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    private val abnormal = setOf(REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR)

    fun isAbnormal(reason: Int): Boolean = reason in abnormal

    /**
     * [lastCheckMillis] is when the list was last looked at (0 on the first start of this version: nothing older is
     * reported). A crash file means the crash is already written down: one report, whatever the system also recorded.
     */
    fun classify(records: List<ExitRecord>, lastCheckMillis: Long, crashFileExists: Boolean): ExitFinding? {
        if (crashFileExists) return ExitFinding(ReportKind.CRASH, null)
        if (lastCheckMillis <= 0L) return null
        val newest = records.filter { it.timestamp > lastCheckMillis && isAbnormal(it.reason) }
            .maxByOrNull { it.timestamp } ?: return null
        return ExitFinding(ReportKind.UNCLEAN_EXIT, newest)
    }

    fun reasonName(reason: Int): String = when (reason) {
        REASON_CRASH -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        else -> "OTHER($reason)"
    }
}
