/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes the stack trace of an uncaught exception into the pending folder, then calls the handler that was set before
 * (upstream's crash screen in a release build, the platform's dialog in a debug build). It never throws and never uses the
 * network. The write is synchronous because the process ends right after.
 */
class CrashRecorder(
    private val pendingDir: File,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Long = System::currentTimeMillis
) : Thread.UncaughtExceptionHandler {
    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            record(thread, throwable)
        } catch (ignored: Throwable) {
            // recording must never hide the crash itself
        }
        previous?.uncaughtException(thread, throwable)
    }

    private fun record(thread: Thread, throwable: Throwable) {
        pendingDir.mkdirs()
        val file = File(pendingDir, "${PendingReport.CRASH_PREFIX}${clock()}.txt")
        file.writeText(format(thread, throwable), Charsets.UTF_8)
    }

    companion object {
        const val MAX_CAUSES = 10

        /** The thread, the exception and at most [MAX_CAUSES] causes. */
        fun format(thread: Thread, throwable: Throwable): String = buildString {
            append("Thread: ").append(thread.name).append('\n')
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSES) {
                val one: Throwable = current
                if (depth > 0) append("Caused by: ")
                val trace = StringWriter()
                PrintWriter(trace).use { one.printStackTrace(it) }
                append(firstBlock(trace.toString(), depth > 0)).append('\n')
                current = one.cause
                depth++
            }
        }

        // printStackTrace of one exception already prints its causes; keep only the exception's own part.
        private fun firstBlock(trace: String, isCause: Boolean): String {
            val marker = trace.indexOf("\nCaused by: ")
            val own = if (marker >= 0) trace.substring(0, marker) else trace
            return if (isCause) own else own.trimEnd()
        }
    }
}
