/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

data class BackupLine(val relativePath: String, val remotePath: String)

/** What one run changed: rows to (re)start uploads for, and the folders now covered, for the result screen. */
data class BackupResult(
    val rowsToStart: List<Long>,
    val lines: List<BackupLine>,
    val state: BackupSnapshot,
    /** Folders that were not changed because the user made their own (disabled) Auto upload row for them. */
    val leftAlone: List<String> = emptyList()
)
