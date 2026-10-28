/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.backup.BackupSnapshot
import org.nourax.nodrive.backup.BackupState
import org.nourax.nodrive.backup.BackupStateTest.MemoryData

class ConflictMigrationTest {
    private val rename = NameCollisionPolicy.RENAME.serialize()
    private val overwrite = NameCollisionPolicy.OVERWRITE.serialize()
    private val skip = NameCollisionPolicy.SKIP.serialize()
    private val ask = NameCollisionPolicy.ASK_USER.serialize()

    private class FakeRows(var rows: List<PolicyRow>) : ConflictRowStore {
        var writes = 0
        override fun rows() = rows
        override fun setPolicy(rowId: Long, policy: Int) {
            writes++
            rows = rows.map { if (it.id == rowId) it.copy(policy = policy) else it }
        }
    }

    private fun row(id: Long, account: String, policy: Int) =
        PolicyRow(id, account, "/storage/emulated/0/DCIM/F$id", "/Phone backup/F$id", policy)

    private fun policyOf(store: FakeRows, id: Long) = store.rows.first { it.id == id }.policy

    private fun own(data: MemoryData, account: String, vararg ids: Long) {
        BackupState(account, data).write(BackupSnapshot(mediaOn = true, owned = ids.toSet()))
    }

    // ---- the pure plan: every owned/not owned x policy case, for two accounts

    @Test
    fun theTableOfOwnedAndNotOwnedRowsByPolicy() {
        val policies = listOf(rename, overwrite, skip, ask)
        val rows = mutableListOf<PolicyRow>()
        val owned = mutableMapOf<String, MutableSet<Long>>()
        var id = 1L
        for (account in listOf("a@h", "b@h")) {
            for (policy in policies) {
                rows += row(id, account, policy)
                owned.getOrPut(account) { mutableSetOf() }.add(id)
                id++
                // the same policy as a row of the user: never touched
                rows += row(id, account, policy)
                id++
            }
        }
        val plan = ConflictMigration.plan(rows, owned, emptyMap())
        rows.forEach { r ->
            val isOwned = r.id in owned[r.account].orEmpty()
            val expected = if (isOwned && r.policy == skip) ask else r.policy
            assertEquals("row ${r.id}", expected, plan.policyChanges[r.id] ?: r.policy)
            if (!isOwned) assertTrue("not owned row ${r.id} untouched", r.id !in plan.policyChanges)
        }
        assertEquals(setOf(5L, 13L), plan.policyChanges.keys)
        assertEquals(mapOf("a@h" to ConflictChoice.SMART, "b@h" to ConflictChoice.SMART), plan.choices)
    }

    @Test
    fun anAccountWhoseOwnedRowsAllKeepRenameOrAllOverwriteKeepsThatAsItsChoice() {
        val rows = listOf(row(1, "a@h", rename), row(2, "a@h", rename), row(3, "b@h", overwrite), row(4, "c@h", skip))
        val owned = mapOf("a@h" to setOf(1L, 2L), "b@h" to setOf(3L), "c@h" to setOf(4L))
        val plan = ConflictMigration.plan(rows, owned, emptyMap())
        assertEquals(ConflictChoice.KEEP_BOTH, plan.choices["a@h"])
        assertEquals(ConflictChoice.KEEP_PHONE, plan.choices["b@h"])
        assertEquals(ConflictChoice.SMART, plan.choices["c@h"])
    }

    @Test
    fun aSavedChoiceIsNeverReplaced() {
        val rows = listOf(row(1, "a@h", skip))
        val plan = ConflictMigration.plan(rows, mapOf("a@h" to setOf(1L)), mapOf("a@h" to ConflictChoice.KEEP_BOTH))
        assertTrue(plan.choices.isEmpty())
        assertTrue("the rows of an account with a saved choice are left alone", plan.policyChanges.isEmpty())
    }

    // ---- ensureMigrated: the flag, idempotence, fresh install

    @Test
    fun theMigrationChangesOwnedAskAndSkipToSmartAndKeepsRenameAndOverwrite() {
        val data = MemoryData()
        own(data, "a@h", 1, 2, 3, 4)
        val store = FakeRows(
            listOf(
                row(1, "a@h", ask),
                row(2, "a@h", skip),
                row(3, "a@h", rename),
                row(4, "a@h", overwrite),
                row(5, "a@h", skip) // the user's own row
            )
        )
        val settings = ConflictSettings(data, store, Any())
        settings.ensureMigrated()
        assertEquals(ask, policyOf(store, 1))
        assertEquals(ask, policyOf(store, 2))
        assertEquals(rename, policyOf(store, 3))
        assertEquals(overwrite, policyOf(store, 4))
        assertEquals(skip, policyOf(store, 5))
        assertEquals(ConflictChoice.SMART, settings.choice("a@h"))
    }

    @Test
    fun aSecondRunChangesNothing() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val store = FakeRows(listOf(row(1, "a@h", skip), row(2, "a@h", rename)))
        val settings = ConflictSettings(data, store, Any())
        settings.ensureMigrated()
        val after = store.rows
        val writes = store.writes
        settings.ensureMigrated()
        ConflictSettings(data, store, Any()).ensureMigrated()
        assertEquals(after, store.rows)
        assertEquals(writes, store.writes)
    }

    @Test
    fun withTheFlagSetNothingIsChangedEvenIfRowsLookOld() {
        val data = MemoryData()
        own(data, "a@h", 1)
        val store = FakeRows(listOf(row(1, "a@h", skip)))
        data.storeOrUpdateKeyValue("global", ConflictSettings.KEY_MIGRATED, "1")
        ConflictSettings(data, store, Any()).ensureMigrated()
        assertEquals(skip, policyOf(store, 1))
        assertEquals(0, store.writes)
    }

    @Test
    fun aFreshInstallChangesNothingAndSetsTheFlag() {
        val data = MemoryData()
        val store = FakeRows(emptyList())
        ConflictSettings(data, store, Any()).ensureMigrated()
        assertEquals(0, store.writes)
        assertEquals("1", data.getValue("global", ConflictSettings.KEY_MIGRATED))
    }

    @Test
    fun aChoiceMadeAfterTheMigrationIsNeverUndone() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val store = FakeRows(listOf(row(1, "a@h", skip), row(2, "a@h", ask)))
        val settings = ConflictSettings(data, store, Any())
        settings.ensureMigrated()
        settings.setChoice("a@h", ConflictChoice.KEEP_NODRIVE)
        assertEquals(skip, policyOf(store, 1))
        assertEquals(skip, policyOf(store, 2))
        // A later start runs the migration again: it must not turn the user's Skip back into Smart.
        ConflictSettings(data, store, Any()).ensureMigrated()
        assertEquals(skip, policyOf(store, 1))
        assertEquals(skip, policyOf(store, 2))
        assertEquals(ConflictChoice.KEEP_NODRIVE, settings.choice("a@h"))
    }

    // ---- the settings

    @Test
    fun theDefaultIsSmartAndNewRowsGetItsCarrier() {
        val data = MemoryData()
        val settings = ConflictSettings(data, FakeRows(emptyList()), Any())
        assertEquals(ConflictChoice.SMART, settings.choice("a@h"))
        assertEquals(ask, settings.newRowPolicy("a@h"))
        settings.setChoice("a@h", ConflictChoice.KEEP_BOTH)
        assertEquals(rename, settings.newRowPolicy("a@h"))
        assertEquals(ask, settings.newRowPolicy("b@h"))
    }

    @Test
    fun settingAChoiceRewritesOnlyTheOwnedRowsOfThatAccount() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        own(data, "b@h", 3)
        val store = FakeRows(
            listOf(row(1, "a@h", ask), row(2, "a@h", rename), row(3, "b@h", ask), row(4, "a@h", ask), row(5, "b@h", skip))
        )
        val settings = ConflictSettings(data, store, Any())
        settings.setChoice("a@h", ConflictChoice.KEEP_PHONE)
        assertEquals(overwrite, policyOf(store, 1))
        assertEquals(overwrite, policyOf(store, 2))
        assertEquals("the user's row of a@h", ask, policyOf(store, 4))
        assertEquals("another account", ask, policyOf(store, 3))
        assertEquals("another account, user's row", skip, policyOf(store, 5))
    }

    // ---- healing: a row put back by upstream's Auto upload (a run that began earlier) is made right again

    @Test
    fun smartWithAStaleOverwriteRowPutsTheRowBackToAsk() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val store = FakeRows(listOf(row(1, "a@h", ask), row(2, "a@h", ask), row(3, "a@h", overwrite)))
        val settings = ConflictSettings(data, store, Any())
        settings.setChoice("a@h", ConflictChoice.KEEP_PHONE)
        settings.setChoice("a@h", ConflictChoice.SMART)
        // the run that was going while the user switched writes its old row back
        store.rows = store.rows.map { if (it.id == 1L) it.copy(policy = overwrite) else it }
        assertEquals(ask, settings.healRow(store.rows.first { it.id == 1L }))
        assertEquals(ask, policyOf(store, 1))
        settings.healAll()
        assertEquals(ask, policyOf(store, 2))
        assertEquals("a row of the user is never touched", overwrite, policyOf(store, 3))
    }

    @Test
    fun everyChoiceHealsAStaleRowToItsOwnValue() {
        val values = mapOf(
            ConflictChoice.SMART to ask,
            ConflictChoice.ASK to ask,
            ConflictChoice.KEEP_NODRIVE to skip,
            ConflictChoice.KEEP_PHONE to overwrite,
            ConflictChoice.KEEP_BOTH to rename
        )
        for ((choice, value) in values) {
            for (stale in listOf(ask, skip, overwrite, rename)) {
                val data = MemoryData()
                own(data, "a@h", 1)
                val store = FakeRows(listOf(row(1, "a@h", ask)))
                val settings = ConflictSettings(data, store, Any())
                settings.setChoice("a@h", choice)
                store.rows = store.rows.map { it.copy(policy = stale) }
                settings.healAll()
                assertEquals("$choice, stale $stale", value, policyOf(store, 1))
            }
        }
    }

    @Test
    fun aRowTheMigrationKeptOnRenameIsLeftAlone() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val store = FakeRows(listOf(row(1, "a@h", rename), row(2, "a@h", skip)))
        val settings = ConflictSettings(data, store, Any())
        settings.ensureMigrated()
        assertEquals(ConflictChoice.SMART, settings.choice("a@h"))
        assertEquals(rename, settings.expectedPolicy("a@h", 1))
        assertEquals(ask, settings.expectedPolicy("a@h", 2))
        // a stale write of the old Skip on row 2 is healed, the kept Rename of row 1 is not touched
        store.rows = store.rows.map { if (it.id == 2L) it.copy(policy = skip) else it }
        val writes = store.writes
        settings.healAll()
        assertEquals(rename, policyOf(store, 1))
        assertEquals(ask, policyOf(store, 2))
        assertEquals(writes + 1, store.writes)
    }

    @Test
    fun aChoiceMadeByHandEndsTheKeptRows() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val store = FakeRows(listOf(row(1, "a@h", rename), row(2, "a@h", ask)))
        val settings = ConflictSettings(data, store, Any())
        settings.ensureMigrated()
        settings.setChoice("a@h", ConflictChoice.KEEP_PHONE)
        settings.healAll()
        assertEquals(overwrite, policyOf(store, 1))
        assertEquals(overwrite, policyOf(store, 2))
    }

    @Test
    fun theKeptRowsOfAnotherAccountAndGoneRowsDoNotMatter() {
        val plan = ConflictMigration.plan(
            listOf(row(1, "a@h", rename), row(2, "a@h", ask), row(3, "b@h", overwrite)),
            mapOf("a@h" to setOf(1L, 2L), "b@h" to setOf(3L)),
            emptyMap()
        )
        assertEquals(mapOf("a@h" to mapOf(1L to rename)), plan.kept)
        assertEquals(mapOf(1L to rename, 5L to ask), KeptPolicies.parse(KeptPolicies.format(mapOf(1L to rename, 5L to ask))))
        assertEquals(emptyMap<Long, Int>(), KeptPolicies.parse("x:1,2,,3:y"))
        assertEquals(emptyMap<Long, Int>(), KeptPolicies.parse(null))
    }

    @Test
    fun healingARowThatIsNotOwnedDoesNothing() {
        val data = MemoryData()
        own(data, "a@h", 1)
        val store = FakeRows(listOf(row(1, "a@h", ask), row(9, "a@h", skip)))
        val settings = ConflictSettings(data, store, Any())
        assertEquals(null, settings.healRow(store.rows.first { it.id == 9L }))
        assertEquals(skip, policyOf(store, 9))
    }

    @Test
    fun removingTheAccountClearsTheChoiceAndTheKeptRows() {
        val data = MemoryData()
        own(data, "a@h", 1, 2)
        val settings = ConflictSettings(data, FakeRows(listOf(row(1, "a@h", rename), row(2, "a@h", ask))), Any())
        settings.ensureMigrated()
        assertEquals("1:$rename", data.getValue("a@h", ConflictSettings.KEY_KEPT))
        BackupState.forget("a@h", data)
        assertEquals(ConflictChoice.SMART, settings.choice("a@h"))
        assertTrue(data.values.keys.none { it.startsWith("a@h|") })
    }
}
