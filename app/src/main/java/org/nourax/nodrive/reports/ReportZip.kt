/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Makes the zip of one report and keeps it within 5 MB: the oldest log lines go first, then the end of the crash text. */
object ReportZip {
    const val MAX_BYTES = 5L * 1024 * 1024
    const val REPORT_JSON = "report.json"
    const val CRASH_TXT = "crash.txt"
    const val LOG_TXT = "logs/log.txt"
    private const val DROP_FRACTION = 4

    /**
     * [logLines] oldest first and already scrubbed. While the zip is over [maxBytes], a quarter of the oldest log lines
     * is dropped; when no log lines are left the crash text is cut from its end.
     */
    fun assemble(json: String, crash: String?, logLines: List<String>, maxBytes: Long = MAX_BYTES): ByteArray {
        var lines = logLines
        var crashBytes = crash?.toByteArray(Charsets.UTF_8)
        var bytes = zip(json, crashBytes, lines)
        while (bytes.size > maxBytes) {
            if (lines.isNotEmpty()) {
                lines = lines.subList(maxOf(1, lines.size / DROP_FRACTION), lines.size)
            } else if (crashBytes != null && crashBytes.isNotEmpty()) {
                crashBytes = crashBytes.copyOf(crashBytes.size / 2)
            } else {
                break
            }
            bytes = zip(json, crashBytes, lines)
        }
        return bytes
    }

    private fun zip(json: String, crash: ByteArray?, lines: List<String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            put(out, REPORT_JSON, json.toByteArray(Charsets.UTF_8))
            if (crash != null) put(out, CRASH_TXT, crash)
            if (lines.isNotEmpty()) {
                out.putNextEntry(ZipEntry(LOG_TXT))
                lines.forEach { line ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun put(out: ZipOutputStream, name: String, data: ByteArray) {
        out.putNextEntry(ZipEntry(name))
        out.write(data)
        out.closeEntry()
    }
}
