/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The branch decisions of the upload hook (round 3 contract, A), as tables. */
class SmartUploadsMatchTest {
    private class Store : ConflictRowStore {
        val writes = mutableListOf<Pair<Long, Int>>()
        override fun rows() = emptyList<PolicyRow>()
        override fun setPolicy(rowId: Long, policy: Int) {
            writes += rowId to policy
        }
    }

    private val ask = NameCollisionPolicy.ASK_USER
    private fun step(
        choice: ConflictChoice,
        policy: NameCollisionPolicy,
        auto: Boolean = true,
        encrypted: Boolean = false
    ) = SmartUploads.step(auto, choice, policy, encrypted)

    private fun use(policy: NameCollisionPolicy) = UploadStep.Use(policy)

    @Test
    fun aFileTheUserPickedByHandKeepsUpstreamsQuestion() {
        ConflictChoice.entries.forEach { choice ->
            NameCollisionPolicy.entries.forEach { policy ->
                assertEquals("$choice $policy", UploadStep.NoChange, step(choice, policy, auto = false))
            }
        }
    }

    @Test
    fun anExplicitPolicyOnTheUploadIsNeverReplaced() {
        // Resolve all puts a file back with RENAME or OVERWRITE; a per-file choice can be SKIP. The account choice never wins.
        val explicit = NameCollisionPolicy.entries.filter { it != ask }
        ConflictChoice.entries.forEach { choice ->
            explicit.forEach { policy ->
                assertEquals("$choice $policy", UploadStep.NoChange, step(choice, policy))
                assertEquals("$choice $policy encrypted", UploadStep.NoChange, step(choice, policy, encrypted = true))
            }
        }
    }

    @Test
    fun keepThePhoneCopyDoesNotTurnAResolveAllRenameIntoAnOverwrite() {
        assertEquals(UploadStep.NoChange, step(ConflictChoice.KEEP_PHONE, NameCollisionPolicy.RENAME))
    }

    @Test
    fun keepTheNoDriveCopyDoesNotTurnAResolveAllOverwriteIntoASkip() {
        assertEquals(UploadStep.NoChange, step(ConflictChoice.KEEP_NODRIVE, ConflictChoice.KEEP_PHONE.upstreamPolicy()))
    }

    @Test
    fun aStaleAskRowGetsTheExplicitKeepChoiceForThisRun() {
        assertEquals(use(NameCollisionPolicy.RENAME), step(ConflictChoice.KEEP_BOTH, ask))
        assertEquals(use(NameCollisionPolicy.SKIP), step(ConflictChoice.KEEP_NODRIVE, ask))
        assertEquals(use(ConflictChoice.KEEP_PHONE.upstreamPolicy()), step(ConflictChoice.KEEP_PHONE, ask))
    }

    @Test
    fun askMeEveryTimeKeepsUpstreamsQuestion() {
        assertEquals(UploadStep.NoChange, step(ConflictChoice.ASK, ask))
        assertEquals(UploadStep.NoChange, step(ConflictChoice.ASK, ask, encrypted = true))
    }

    @Test
    fun smartComparesSizesAndKeepsBothInAnEncryptedFolder() {
        assertEquals(UploadStep.CompareSizes, step(ConflictChoice.SMART, ask))
        assertEquals(use(NameCollisionPolicy.RENAME), step(ConflictChoice.SMART, ask, encrypted = true))
    }

    @Test
    fun smartNeverAnswersWithAnOverwrite() {
        val overwrite = ConflictChoice.KEEP_PHONE.upstreamPolicy()
        listOf(false, true).forEach { encrypted ->
            assertEquals(false, step(ConflictChoice.SMART, ask, encrypted = encrypted) == use(overwrite))
        }
    }

    @Test
    fun healingPutsTheChoiceBackIntoTheFolderRowOnlyWhenItDiffers() {
        val store = Store()
        val row = PolicyRow(7, "a@h", "/l", "/r", ask.serialize())
        SmartUploads.heal(ConflictChoice.KEEP_BOTH, row, store)
        assertEquals(listOf(7L to NameCollisionPolicy.RENAME.serialize()), store.writes)
        SmartUploads.heal(ConflictChoice.KEEP_BOTH, row.copy(policy = NameCollisionPolicy.RENAME.serialize()), store)
        assertEquals(1, store.writes.size)
    }

    @Test
    fun theSizeAnswerMapsToSkipRenameOrTheOriginalFailure() {
        assertEquals(
            SmartOutcome(NameCollisionPolicy.SKIP, null),
            SmartUploads.sizeOutcome(10, RemoteSize.Known(10))
        )
        assertEquals(
            SmartOutcome(NameCollisionPolicy.RENAME, null),
            SmartUploads.sizeOutcome(10, RemoteSize.Known(11))
        )
        assertEquals(SmartOutcome(NameCollisionPolicy.RENAME, null), SmartUploads.sizeOutcome(10, RemoteSize.Missing))
    }

    @Test
    fun aSizeThatCannotBeReadEndsTheRunWithThatFailureAndChangesNoPolicy() {
        val failure = RemoteOperationResult<Any>(ResultCode.TIMEOUT)
        val outcome = SmartUploads.sizeOutcome(10, RemoteSize.Unreadable(failure))
        assertNull(outcome.policy)
        assertSame(failure, outcome.failure)
    }
}
