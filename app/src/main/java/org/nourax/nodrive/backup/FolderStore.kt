/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** Auto upload rows as the backup sees them. The real one is [SyncedFolderStore]; tests use a fake. */
interface FolderStore {
    /** The rows of every account: the planner filters by account itself. */
    fun rows(): List<FolderRow>

    /** Writes a new enabled row and returns its id, or a negative number when it could not be stored. */
    fun create(account: String, spec: FolderSpec, defaults: BackupDefaults): Long

    /** Gives an existing disabled row the backup's settings and enables it. */
    fun adopt(rowId: Long, spec: FolderSpec, defaults: BackupDefaults)

    fun enable(rowId: Long)

    /** Stops the row: it is kept, and so are the phone's and the Drive's files. */
    fun disable(rowId: Long)
}
