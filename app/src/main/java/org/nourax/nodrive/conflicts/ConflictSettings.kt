/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.content.Context
import com.owncloud.android.datamodel.ArbitraryDataProvider
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.utils.Log_OC
import org.nourax.nodrive.backup.BackupController
import org.nourax.nodrive.backup.BackupState

/**
 * The "If a file is already in NoDrive" choice of each account, and the upstream column of the backup rows that follows it.
 * Everything that reads or writes it first makes sure the one-time migration has run ([ensureMigrated]), inside the
 * backup's own lock, so no decision is ever made on a row that still has the old value.
 *
 * What an owned row must hold is always known from NoDrive's own data: the account's choice, except for the rows the
 * migration deliberately kept on their own value (see [expectedPolicy]). That is what lets [healRow] and [healAll] put a
 * row right again after upstream's Auto upload wrote an old value back at the end of a run it had started earlier.
 */
class ConflictSettings(
    private val data: ArbitraryDataProvider,
    private val rows: ConflictRowStore,
    private val lock: Any = BackupController.LOCK
) {
    fun choice(account: String): ConflictChoice = ConflictChoice.fromKey(data.getValue(account, KEY_CHOICE))

    /** The column value for a row the backup is about to create. */
    fun newRowPolicy(account: String): Int {
        ensureMigrated()
        return choice(account).upstreamPolicy().serialize()
    }

    /** Saves the choice and gives every owned row of the account its column value. Rows of the user are never touched. */
    fun setChoice(account: String, choice: ConflictChoice) {
        ensureMigrated()
        synchronized(lock) {
            data.storeOrUpdateKeyValue(account, KEY_CHOICE, choice.key)
            // A choice made by hand covers every owned row: no row is kept on its own value any more.
            data.storeOrUpdateKeyValue(account, KEY_KEPT, "")
            val owned = BackupState(account, data).read().owned
            val policy = choice.upstreamPolicy().serialize()
            rows.rows().filter { it.account == account && it.id in owned && it.policy != policy }
                .forEach { rows.setPolicy(it.id, policy) }
        }
    }

    /**
     * The column value an owned row must hold: the value it was kept on by the migration (Rename or Overwrite in an
     * account with mixed folders), otherwise the account's choice.
     */
    fun expectedPolicy(account: String, rowId: Long): Int =
        KeptPolicies.parse(data.getValue(account, KEY_KEPT))[rowId] ?: choice(account).upstreamPolicy().serialize()

    /**
     * Puts one owned row back to [expectedPolicy] when it holds something else, and returns the value it must hold (null
     * when the row is not owned by the backup: a row of the user is never touched).
     */
    fun healRow(row: PolicyRow): Int? {
        ensureMigrated()
        return synchronized(lock) {
            if (row.id !in BackupState(row.account, data).read().owned) {
                null
            } else {
                val wanted = expectedPolicy(row.account, row.id)
                if (row.policy != wanted) {
                    rows.setPolicy(row.id, wanted)
                    Log_OC.d(TAG, "backup folder ${row.id} put back to its saved choice")
                }
                wanted
            }
        }
    }

    /** [healRow] for every owned row of every account, and forgets kept values of rows that no longer exist. */
    fun healAll() {
        ensureMigrated()
        synchronized(lock) {
            val all = rows.rows()
            all.forEach { healRow(it) }
            val ids = all.map { it.id }.toSet()
            all.map { it.account }.distinct().forEach { account ->
                val kept = KeptPolicies.parse(data.getValue(account, KEY_KEPT))
                val pruned = kept.filterKeys { it in ids }
                if (pruned.size != kept.size) data.storeOrUpdateKeyValue(account, KEY_KEPT, KeptPolicies.format(pruned))
            }
        }
    }

    /** Runs the migration once (idempotent). Cheap afterwards: one key read. */
    fun ensureMigrated() {
        synchronized(lock) {
            if (data.getValue(GLOBAL, KEY_MIGRATED) == MIGRATED) return
            val all = rows.rows()
            val accounts = all.map { it.account }.distinct()
            val owned = accounts.associateWith { BackupState(it, data).read().owned }
            val existing = accounts.associateWith { account ->
                data.getValue(account, KEY_CHOICE).takeUnless { it.isNullOrEmpty() }?.let { ConflictChoice.fromKey(it) }
            }
            val plan = ConflictMigration.plan(all, owned, existing)
            plan.policyChanges.forEach { (id, policy) -> rows.setPolicy(id, policy) }
            plan.choices.forEach { (account, choice) -> data.storeOrUpdateKeyValue(account, KEY_CHOICE, choice.key) }
            plan.kept.forEach { (account, kept) -> data.storeOrUpdateKeyValue(account, KEY_KEPT, KeptPolicies.format(kept)) }
            data.storeOrUpdateKeyValue(GLOBAL, KEY_MIGRATED, MIGRATED)
            Log_OC.d(TAG, "migration done: ${plan.policyChanges.size} backup folder(s) now Smart")
        }
    }

    companion object {
        const val KEY_CHOICE = "nodrive_conflict_choice"

        /** Rows the migration kept on their own value: "id:policy,id:policy". Removed with the account (BackupState). */
        const val KEY_KEPT = "nodrive_conflict_kept"
        const val KEY_MIGRATED = "nodrive_conflict_migration"
        private const val GLOBAL = "global"
        private const val MIGRATED = "1"
        private const val TAG = "ConflictSettings"

        fun create(context: Context): ConflictSettings =
            ConflictSettings(ArbitraryDataProviderImpl(context), SyncedFolderPolicyStore(context))
    }
}
