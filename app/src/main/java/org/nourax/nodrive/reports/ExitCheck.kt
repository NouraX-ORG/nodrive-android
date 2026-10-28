/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.owncloud.android.lib.common.utils.Log_OC
import java.io.File
import java.io.IOException

/**
 * Looks, once per start, at how the earlier runs of the app ended (Android 11 and newer). An abnormal end is written down
 * as a pending file, like a crash. On Android 9 and 10 only the crash file exists.
 */
object ExitCheck {
    private const val TAG = "ReportsExitCheck"
    private const val MAX_TRACE_BYTES = 512 * 1024

    /** Returns the pending file written, or null when there is nothing new. Never throws. */
    @Suppress("TooGenericExceptionCaught")
    fun run(context: Context, settings: ReportSettings, files: ReportFiles, nowMillis: Long = System.currentTimeMillis()): File? {
        val last = settings.lastExitCheckMillis
        settings.lastExitCheckMillis = nowMillis
        if (last <= 0L) return null
        return try {
            val records = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readRecords(context) else emptyList()
            val finding = ExitClassifier.classify(records, last, PendingStore(files).hasCrash())
            val record = finding?.record
            if (finding == null || finding.kind != ReportKind.UNCLEAN_EXIT || record == null) {
                null
            } else {
                write(files, record)
            }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "exit reasons not read: ${e.javaClass.simpleName}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readRecords(context: Context): List<ExitRecord> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName == context.packageName }
            .map { info ->
                ExitRecord(
                    info.reason,
                    info.timestamp,
                    info.description,
                    info.importance,
                    if (info.reason == ExitClassifier.REASON_ANR) ({ trace(info) }) else null
                )
            }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun trace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val buffer = ByteArray(MAX_TRACE_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }
    } catch (e: IOException) {
        null
    }

    private fun write(files: ReportFiles, record: ExitRecord): File {
        files.ensure()
        val file = File(files.pending, "${PendingReport.EXIT_PREFIX}${record.timestamp}.txt")
        file.writeText(describe(record), Charsets.UTF_8)
        return file
    }

    fun describe(record: ExitRecord): String = buildString {
        append("The system recorded that NoDrive ended abnormally.\n")
        append("Reason: ").append(ExitClassifier.reasonName(record.reason)).append('\n')
        append("Time: ").append(ReportName.isoUtc(record.timestamp)).append('\n')
        append("Importance: ").append(record.importance).append('\n')
        record.description?.takeIf { it.isNotBlank() }?.let { append("Description: ").append(it).append('\n') }
        record.trace?.invoke()?.takeIf { it.isNotBlank() }?.let { append("\nTrace:\n").append(it).append('\n') }
    }
}
