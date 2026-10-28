/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartUploadsKeepTest {
    private class Store : ConflictRowStore {
        val writes = mutableListOf<Pair<Long, Int>>()
        override fun rows() = emptyList<PolicyRow>()
        override fun setPolicy(rowId: Long, policy: Int) {
            writes += rowId to policy
        }
    }

    private val ask = NameCollisionPolicy.ASK_USER
    private fun row(policy: NameCollisionPolicy) = PolicyRow(7, "a@h", "/l", "/r", policy.serialize())

    @Test
    fun aStaleAskRowIsHealedAndTheChoiceIsUsedForThisRun() {
        val store = Store()
        val outcome = SmartUploads.keep(ConflictChoice.KEEP_BOTH, ask, row(ask), store)
        assertEquals(NameCollisionPolicy.RENAME, outcome.policy)
        assertNull(outcome.failure)
        assertEquals(listOf(7L to NameCollisionPolicy.RENAME.serialize()), store.writes)
    }

    @Test
    fun whenTheRunAlreadyHasTheChoiceNothingChanges() {
        val store = Store()
        val outcome = SmartUploads.keep(ConflictChoice.KEEP_NODRIVE, NameCollisionPolicy.SKIP, row(NameCollisionPolicy.SKIP), store)
        assertEquals(SmartOutcome.NO_CHANGE, outcome)
        assertEquals(emptyList<Pair<Long, Int>>(), store.writes)
    }

    @Test
    fun theColumnIsHealedEvenWhenThisRunAlreadyHasTheChoice() {
        val store = Store()
        val outcome = SmartUploads.keep(ConflictChoice.KEEP_BOTH, NameCollisionPolicy.RENAME, row(ask), store)
        assertEquals(SmartOutcome.NO_CHANGE, outcome)
        assertEquals(1, store.writes.size)
    }
}
