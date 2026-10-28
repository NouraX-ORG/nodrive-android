/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

/** Finds the backup row an upload belongs to. Pure, so the matching rules are tested. */
object OwnedRows {
    /**
     * The owned row of [account] whose local folder holds [localPath] and whose Drive folder holds [remotePath].
     * A folder only matches whole path segments: `DCIM/Camera` never matches `DCIM/Camera2/a.jpg`.
     */
    fun find(
        rows: List<PolicyRow>,
        owned: Set<Long>,
        account: String,
        localPath: String?,
        remotePath: String?
    ): PolicyRow? {
        if (localPath.isNullOrEmpty() || remotePath.isNullOrEmpty()) return null
        return rows.firstOrNull {
            it.account == account && it.id in owned && isInside(localPath, it.localPath) &&
                isInside(remotePath, it.remotePath)
        }
    }

    fun isInside(path: String, folder: String): Boolean {
        val base = folder.trimEnd('/')
        if (base.isEmpty()) return false
        return path.startsWith("$base/")
    }
}
