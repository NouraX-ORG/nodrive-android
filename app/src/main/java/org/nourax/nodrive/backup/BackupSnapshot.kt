/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** The saved state of the backup for one account (what the planner reads and returns). */
data class BackupSnapshot(
    val mediaOn: Boolean = false,
    val docsOn: Boolean = false,
    val root: String? = null,
    /** Ids of the rows the backup created or adopted; only these are ever changed by it later. */
    val owned: Set<Long> = emptySet(),
    /** Folder keys the backup has already looked at, so a folder the user removed is not brought back. */
    val seen: Set<String> = emptySet()
)

/** Operations to apply, and the state to save after them (ids of rows created by [BackupOp.Create] are added later). */
data class BackupPlan(val ops: List<BackupOp>, val state: BackupSnapshot) {
    val isEmpty: Boolean get() = ops.isEmpty()
}
