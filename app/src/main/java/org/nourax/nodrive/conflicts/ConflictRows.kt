/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.content.ContentValues
import android.content.Context
import com.owncloud.android.db.ProviderMeta.ProviderTableMeta

/** The part of an Auto upload (synced folder) row that conflicts need. [policy] is upstream's NameCollisionPolicy ordinal. */
data class PolicyRow(
    val id: Long,
    val account: String,
    val localPath: String,
    val remotePath: String,
    val policy: Int
)

/** Auto upload rows as the conflict code sees them. The real one is [SyncedFolderPolicyStore]; tests use a fake. */
interface ConflictRowStore {
    fun rows(): List<PolicyRow>

    /** Changes only the name collision column of one row. */
    fun setPolicy(rowId: Long, policy: Int)
}

/**
 * Reads the rows with a narrow query and writes only the policy column, so a running scan's own columns (lastScan,
 * enabled) are never rewritten by a full-row update.
 */
class SyncedFolderPolicyStore(context: Context) : ConflictRowStore {
    private val resolver = context.applicationContext.contentResolver

    override fun rows(): List<PolicyRow> {
        val columns = arrayOf(
            ProviderTableMeta._ID,
            ProviderTableMeta.SYNCED_FOLDER_ACCOUNT,
            ProviderTableMeta.SYNCED_FOLDER_LOCAL_PATH,
            ProviderTableMeta.SYNCED_FOLDER_REMOTE_PATH,
            ProviderTableMeta.SYNCED_FOLDER_NAME_COLLISION_POLICY
        )
        val found = mutableListOf<PolicyRow>()
        resolver.query(ProviderTableMeta.CONTENT_URI_SYNCED_FOLDERS, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                found += PolicyRow(
                    cursor.getLong(0),
                    cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(),
                    cursor.getInt(4)
                )
            }
        }
        return found
    }

    override fun setPolicy(rowId: Long, policy: Int) {
        val values = ContentValues().apply { put(ProviderTableMeta.SYNCED_FOLDER_NAME_COLLISION_POLICY, policy) }
        resolver.update(
            ProviderTableMeta.CONTENT_URI_SYNCED_FOLDERS,
            values,
            "${ProviderTableMeta._ID}=?",
            arrayOf(rowId.toString())
        )
    }
}
