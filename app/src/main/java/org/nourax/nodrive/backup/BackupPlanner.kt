/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.owncloud.android.datamodel.MediaFolderType

/**
 * Decides which Auto upload rows the backup switch creates, adopts, enables or disables (design 2.2). Pure: it reads
 * lists and returns a [BackupPlan]; nothing here touches the phone, the database or the Drive. There is no delete.
 */
object BackupPlanner {
    private val MEDIA_TYPES = setOf(MediaFolderType.IMAGE, MediaFolderType.VIDEO)
    private val DOC_TYPES = setOf(MediaFolderType.CUSTOM)

    fun enableMedia(
        folders: List<MediaFolderInfo>,
        rows: List<FolderRow>,
        snapshot: BackupSnapshot,
        account: String,
        root: String
    ): BackupPlan {
        val planning = Planning(rows, snapshot, account, root)
        planning.reenableOwned(MEDIA_TYPES)
        val qualifying = folders.filter { it.qualifies }
        qualifying.sortedBy { it.path.length }.forEach {
            planning.place(FolderSpec(it.path, it.type, BackupPaths.remotePath(root, it.path)), skipUnderParent = true)
        }
        return planning.result(snapshot.copy(mediaOn = true), qualifying.map { it.key() })
    }

    fun disableMedia(rows: List<FolderRow>, snapshot: BackupSnapshot, account: String): BackupPlan =
        Planning(rows, snapshot, account, snapshot.root.orEmpty()).let {
            it.disableOwned(MEDIA_TYPES)
            it.result(snapshot.copy(mediaOn = false), emptyList())
        }

    fun enableDocs(
        docFolders: List<String>,
        rows: List<FolderRow>,
        snapshot: BackupSnapshot,
        account: String,
        root: String
    ): BackupPlan {
        val planning = Planning(rows, snapshot, account, root)
        planning.reenableOwned(DOC_TYPES)
        docFolders.forEach { planning.place(docSpec(it, root), skipUnderParent = false) }
        return planning.result(snapshot.copy(docsOn = true), docFolders.map { BackupKeys.of(MediaFolderType.CUSTOM, it) })
    }

    fun disableDocs(rows: List<FolderRow>, snapshot: BackupSnapshot, account: String): BackupPlan =
        Planning(rows, snapshot, account, snapshot.root.orEmpty()).let {
            it.disableOwned(DOC_TYPES)
            it.result(snapshot.copy(docsOn = false), emptyList())
        }

    /** New folders while a switch is on: only folders never seen before; never re-enables what the user turned off. */
    fun autoAdd(
        folders: List<MediaFolderInfo>,
        docFolders: List<String>,
        rows: List<FolderRow>,
        snapshot: BackupSnapshot,
        account: String,
        root: String
    ): BackupPlan {
        if (!snapshot.mediaOn && !snapshot.docsOn) return BackupPlan(emptyList(), snapshot)
        val planning = Planning(rows, snapshot, account, root)
        val seenNow = mutableListOf<String>()
        if (snapshot.mediaOn) {
            val qualifying = folders.filter { it.qualifies }
            seenNow += qualifying.map { it.key() }
            qualifying.filter { it.key() !in snapshot.seen }.sortedBy { it.path.length }.forEach {
                planning.place(
                    FolderSpec(it.path, it.type, BackupPaths.remotePath(root, it.path)),
                    skipUnderParent = true
                )
            }
        }
        if (snapshot.docsOn) {
            seenNow += docFolders.map { BackupKeys.of(MediaFolderType.CUSTOM, it) }
            docFolders.filter { BackupKeys.of(MediaFolderType.CUSTOM, it) !in snapshot.seen }
                .forEach { planning.place(docSpec(it, root), skipUnderParent = false) }
        }
        return planning.result(snapshot, seenNow)
    }

    private fun docSpec(path: String, root: String) =
        FolderSpec(path, MediaFolderType.CUSTOM, BackupPaths.remotePath(root, path), excludeHidden = true)

    private fun MediaFolderInfo.key() = BackupKeys.of(type, path)

    /** The working state of one planning run for one account. */
    private class Planning(allRows: List<FolderRow>, private val snapshot: BackupSnapshot, account: String, root: String) {
        private val rows = allRows.filter { it.account == account }
        private val byKey = rows.groupBy { it.key }.mapValues { it.value.first() }
        private val owned = snapshot.owned.intersect(rows.map { it.id }.toSet()).toMutableSet()
        private val enabledPaths = rows.filter { it.enabled }.map { it.type to it.path }.toMutableList()
        private val ops = mutableListOf<BackupOp>()
        private val rootPath = root

        fun reenableOwned(types: Set<MediaFolderType>) {
            rows.filter { it.id in owned && it.type in types && !it.enabled }.forEach {
                ops += BackupOp.Enable(it.id)
                enabledPaths += it.type to it.path
            }
        }

        fun disableOwned(types: Set<MediaFolderType>) {
            rows.filter { it.id in owned && it.type in types && it.enabled }.forEach { ops += BackupOp.Disable(it.id) }
        }

        fun place(spec: FolderSpec, skipUnderParent: Boolean) {
            val row = byKey[spec.key]
            if (row != null && (row.enabled || row.id in owned)) return
            if (skipUnderParent && isUnderEnabledParent(spec)) return
            if (row == null) {
                ops += BackupOp.Create(spec)
            } else {
                ops += BackupOp.Adopt(row.id, spec)
                owned += row.id
            }
            enabledPaths += spec.type to spec.path
        }

        fun result(base: BackupSnapshot, seenKeys: List<String>) = BackupPlan(
            ops.toList(),
            base.copy(root = rootPath.ifEmpty { null }, owned = owned.toSet(), seen = snapshot.seen + seenKeys)
        )

        private fun isUnderEnabledParent(spec: FolderSpec) = enabledPaths.any { (type, path) ->
            type == spec.type && path != spec.path && spec.path.startsWith(path.trimEnd('/') + "/")
        }
    }
}
