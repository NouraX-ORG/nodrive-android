/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import java.io.File

/**
 * The folders of the problem reports, all inside the app's private files: pending (raw crash or exit text waiting for a
 * decision), outbox (finished, scrubbed zips) and work (temporary). [deleteOwn] is the only place in this package that
 * deletes a file, and it refuses any path outside the root.
 */
class ReportFiles(val root: File) {
    val pending = File(root, "pending")
    val outbox = File(root, "outbox")
    val work = File(root, "work")

    fun ensure() {
        listOf(pending, outbox, work).forEach { it.mkdirs() }
    }

    /** Deletes [file] only when it lies inside [root]. Returns true when the file is gone afterwards. */
    fun deleteOwn(file: File): Boolean {
        val inside = try {
            file.canonicalFile.path.startsWith(root.canonicalFile.path + File.separator)
        } catch (e: java.io.IOException) {
            false
        }
        if (!inside) return false
        return file.delete() || !file.exists()
    }

    companion object {
        fun of(context: Context) = ReportFiles(File(context.applicationContext.filesDir, "nodrive-reports"))
    }
}
