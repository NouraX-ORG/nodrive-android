/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.File

/** A crash or an unclean exit that was written down and waits for a decision. */
class PendingReport(val file: File) {
    val kind: ReportKind get() = if (file.name.startsWith(CRASH_PREFIX)) ReportKind.CRASH else ReportKind.UNCLEAN_EXIT
    val name: String get() = file.name

    fun text(): String = try {
        file.readText(Charsets.UTF_8)
    } catch (e: java.io.IOException) {
        ""
    }

    companion object {
        const val CRASH_PREFIX = "crash-"
        const val EXIT_PREFIX = "exit-"
    }
}

/** The raw crash and exit files in the pending folder. */
class PendingStore(private val files: ReportFiles) {
    /** Oldest first. */
    fun all(): List<PendingReport> = files.pending.listFiles { f -> f.isFile && f.extension == "txt" }.orEmpty()
        .sortedBy { it.name }.map { PendingReport(it) }

    fun newest(): PendingReport? = all().maxByOrNull { it.file.lastModified() }

    fun hasCrash(): Boolean = all().any { it.kind == ReportKind.CRASH }

    /** The decision is made: the raw files are not needed any more. */
    fun consume() {
        all().forEach { files.deleteOwn(it.file) }
    }
}
