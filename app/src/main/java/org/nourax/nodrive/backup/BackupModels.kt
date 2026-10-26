/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.owncloud.android.datamodel.MediaFolderType

/** One media or document folder on the phone. */
data class MediaFolderInfo(val path: String, val type: MediaFolderType, val qualifies: Boolean = true)

/** The part of an Auto upload (synced folder) row the planner needs. */
data class FolderRow(
    val id: Long,
    val path: String,
    val type: MediaFolderType,
    val account: String,
    val enabled: Boolean
) {
    val key: String get() = BackupKeys.of(type, path)
}

/** What a row written by the backup switch looks like (the fixed part; Wi-Fi and charging come from [BackupDefaults]). */
data class FolderSpec(
    val path: String,
    val type: MediaFolderType,
    val remotePath: String,
    val excludeHidden: Boolean = false
) {
    val key: String get() = BackupKeys.of(type, path)
}

data class BackupDefaults(val wifiOnly: Boolean = true, val chargingOnly: Boolean = false)

/**
 * There is no delete operation: switching the backup off or on never removes a row, a file or an upload. There is no
 * "adopt" either: an Auto upload row made by the user is never rewritten (review S3).
 */
sealed interface BackupOp {
    data class Create(val spec: FolderSpec) : BackupOp
    data class Enable(val rowId: Long) : BackupOp
    data class Disable(val rowId: Long) : BackupOp
}

object BackupKeys {
    fun of(type: MediaFolderType, path: String): String = "${type.id}:$path"
}
