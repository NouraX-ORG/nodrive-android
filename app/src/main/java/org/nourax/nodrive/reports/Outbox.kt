/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.File

/** The finished reports waiting to be sent. Only the newest [KEEP] are kept on the phone. */
class Outbox(private val files: ReportFiles) {
    /** Oldest first: the names start with the UTC time. */
    fun list(): List<File> =
        files.outbox.listFiles { f -> f.isFile && f.extension == "zip" }.orEmpty().sortedBy { it.name }

    fun trim(keep: Int = KEEP) {
        val all = list()
        if (all.size > keep) all.take(all.size - keep).forEach { files.deleteOwn(it) }
    }

    companion object {
        const val KEEP = 5
    }
}
