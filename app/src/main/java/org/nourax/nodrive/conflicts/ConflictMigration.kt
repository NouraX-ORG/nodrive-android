/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy

/** What the one-time migration changes: policy columns of rows (by id) and the account choices that are still missing. */
data class MigrationPlan(val policyChanges: Map<Long, Int>, val choices: Map<String, ConflictChoice>)

/**
 * The one-time migration of the owned backup folders (round 3 contract, A): "Ask" and "Skip" become Smart, "Rename" and
 * "Overwrite" keep their choice. Pure, so it can be tested with a table. It only ever touches rows the backup owns.
 */
object ConflictMigration {
    private val ask = NameCollisionPolicy.ASK_USER.serialize()
    private val skip = NameCollisionPolicy.SKIP.serialize()
    private val rename = NameCollisionPolicy.RENAME.serialize()
    private val overwrite = ConflictChoice.KEEP_PHONE.upstreamPolicy().serialize()

    /**
     * @param owned the ids of the rows the backup created, per account (BackupState.owned)
     * @param existing the account choices that are already saved
     *
     * Smart keeps ASK_USER in the upstream column (the carrier, see [ConflictChoice]), so "Skip" becomes ASK_USER and
     * "Ask" stays as it is. An account whose owned rows all still say Rename (or all Overwrite) keeps that as its
     * choice, so Settings tells the truth; any other mix is Smart.
     */
    fun plan(rows: List<PolicyRow>, owned: Map<String, Set<Long>>, existing: Map<String, ConflictChoice?>): MigrationPlan {
        // An account that already has a saved choice was migrated before (or chose by hand): its rows are left alone.
        val ownedRows = rows.filter { it.id in owned[it.account].orEmpty() }
        val changes = ownedRows.filter { it.policy == skip && existing[it.account] == null }.associate { it.id to ask }
        val choices = ownedRows.groupBy { it.account }
            .filterKeys { existing[it] == null }
            .mapValues { (_, accountRows) ->
                val after = accountRows.map { changes[it.id] ?: it.policy }
                when {
                    after.all { it == rename } -> ConflictChoice.KEEP_BOTH
                    after.all { it == overwrite } -> ConflictChoice.KEEP_PHONE
                    else -> ConflictChoice.SMART
                }
            }
        return MigrationPlan(changes, choices)
    }
}
