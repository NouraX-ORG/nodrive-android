/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/**
 * Runs the [BackupPlanner] for one account and writes the result through a [FolderStore]. The screen, the first
 * sign-in offer and the media folder detection all come through here, inside one process-wide lock, so two of them
 * can never create the same row. It never deletes a row, a file or a Drive item.
 */
class BackupController(
    private val account: String,
    private val devices: DeviceFolders,
    private val store: FolderStore,
    private val state: BackupState,
    private val deviceName: String?
) {
    fun snapshot(): BackupSnapshot = state.read()

    fun enableMedia(defaults: BackupDefaults = BackupDefaults()): BackupResult = run(defaults) { snapshot, rows ->
        BackupPlanner.enableMedia(devices.mediaFolders(), rows, snapshot, account, rootOf(snapshot))
    }

    fun disableMedia(): BackupResult = run(BackupDefaults()) { snapshot, rows ->
        BackupPlanner.disableMedia(rows, snapshot, account)
    }

    fun enableDocs(defaults: BackupDefaults = BackupDefaults()): BackupResult = run(defaults) { snapshot, rows ->
        BackupPlanner.enableDocs(devices.documentFolders(), rows, snapshot, account, rootOf(snapshot))
    }

    fun disableDocs(): BackupResult = run(BackupDefaults()) { snapshot, rows ->
        BackupPlanner.disableDocs(rows, snapshot, account)
    }

    /** For new folders while a switch is on; does nothing (and reads nothing from the phone) while both are off. */
    fun autoAdd(defaults: BackupDefaults = BackupDefaults()): BackupResult = run(defaults) { snapshot, rows ->
        if (!snapshot.mediaOn && !snapshot.docsOn) {
            BackupPlan(emptyList(), snapshot)
        } else {
            val docs = if (snapshot.docsOn) devices.documentFolders() else emptyList()
            val media = if (snapshot.mediaOn) devices.mediaFolders() else emptyList()
            BackupPlanner.autoAdd(media, docs, rows, snapshot, account, rootOf(snapshot))
        }
    }

    private fun rootOf(snapshot: BackupSnapshot) = BackupPaths.root(snapshot.root, deviceName)

    private fun run(
        defaults: BackupDefaults,
        plan: (BackupSnapshot, List<FolderRow>) -> BackupPlan
    ): BackupResult = synchronized(LOCK) {
        val before = state.read()
        val made = plan(before, store.rows())
        val owned = made.state.owned.toMutableSet()
        val start = mutableListOf<Long>()
        val lines = mutableListOf<BackupLine>()
        val root = made.state.root.orEmpty()
        made.ops.forEach { op ->
            when (op) {
                is BackupOp.Create -> store.create(account, op.spec, defaults).takeIf { it >= 0 }?.let {
                    owned += it
                    start += it
                    lines += line(op.spec, root)
                }
                is BackupOp.Adopt -> {
                    store.adopt(op.rowId, op.spec, defaults)
                    start += op.rowId
                    lines += line(op.spec, root)
                }
                is BackupOp.Enable -> {
                    store.enable(op.rowId)
                    start += op.rowId
                }
                is BackupOp.Disable -> store.disable(op.rowId)
            }
        }
        val after = made.state.copy(owned = owned)
        if (after != before) state.write(after)
        BackupResult(start, lines, after)
    }

    private fun line(spec: FolderSpec, root: String) =
        BackupLine(spec.remotePath.removePrefix("$root/"), spec.remotePath)

    private companion object {
        val LOCK = Any()
    }
}
