/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.File
import java.io.IOException

/** What a report is made of. */
class ReportInput(
    val kind: ReportKind,
    val meta: ReportMeta,
    val note: String,
    val crashText: String?,
    /** The app's own log files, oldest first. */
    val logFiles: List<File>,
    val createdMillis: Long,
    val appVersion: String
)

/** Makes one scrubbed zip in the outbox. Nothing here reads the network. */
class ReportBuilder(private val files: ReportFiles) {
    /** Returns the zip, now in the outbox. */
    @Throws(IOException::class)
    fun build(input: ReportInput): File {
        files.ensure()
        val json = ReportJson.build(input.kind, input.meta, input.note)
        val crash = input.crashText?.let { Scrubber.scrub(it) }
        val lines = Scrubber.scrub(readLogs(input.logFiles)).split("\n").let { all ->
            if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all
        }
        val bytes = ReportZip.assemble(json, crash, lines)
        val base = ReportName.base(input.createdMillis, input.appVersion)
        val name = ReportName.firstFree(base) { File(files.outbox, it).exists() }
        val part = File(files.work, "$name.part")
        part.writeBytes(bytes)
        val target = File(files.outbox, name)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = false)
            files.deleteOwn(part)
        }
        Outbox(files).trim()
        return target
    }

    private fun readLogs(logFiles: List<File>): String = buildString {
        logFiles.filter { it.isFile }.forEach { file ->
            try {
                val text = file.readText(Charsets.UTF_8)
                append(text)
                if (!text.endsWith("\n")) append('\n')
            } catch (e: IOException) {
                // a log file that cannot be read is left out
            }
        }
    }
}
