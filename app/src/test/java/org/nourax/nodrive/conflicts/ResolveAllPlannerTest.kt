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

class ResolveAllPlannerTest {
    private fun conflict(id: Long, bytes: Long = 100, exists: Boolean = true, uploading: Boolean = false) =
        WaitingConflict(id, "/storage/emulated/0/DCIM/Camera/$id.jpg", "/Phone backup/$id.jpg", exists, bytes, uploading)

    private val noLookup: (WaitingConflict) -> RemoteSize<String> = { error("no size lookup expected") }

    @Test
    fun keepTheNoDriveCopyMarksEveryConflictDoneEvenWithoutThePhoneFile() {
        val actions = ResolveAllPlanner.plan(ConflictChoice.KEEP_NODRIVE, listOf(conflict(1), conflict(2, exists = false)), noLookup)
        assertEquals(listOf(ResolveAction.MarkDone(1), ResolveAction.MarkDone(2)), actions)
    }

    @Test
    fun keepThePhoneCopyRequeuesWithTheOverwriteValue() {
        val actions = ResolveAllPlanner.plan(ConflictChoice.KEEP_PHONE, listOf(conflict(1)), noLookup)
        assertEquals(listOf(ResolveAction.Requeue(1, NameCollisionPolicy.OVERWRITE)), actions)
    }

    @Test
    fun keepBothRequeuesWithRename() {
        val actions = ResolveAllPlanner.plan(ConflictChoice.KEEP_BOTH, listOf(conflict(1), conflict(2)), noLookup)
        assertEquals(
            listOf(ResolveAction.Requeue(1, NameCollisionPolicy.RENAME), ResolveAction.Requeue(2, NameCollisionPolicy.RENAME)),
            actions
        )
    }

    @Test
    fun smartMarksSameSizeDoneAndKeepsBothForTheRest() {
        val sizes = mapOf(1L to RemoteSize.Known(100), 2L to RemoteSize.Known(7), 3L to RemoteSize.Missing)
        val actions = ResolveAllPlanner.plan(
            ConflictChoice.SMART,
            listOf(conflict(1, 100), conflict(2, 100), conflict(3, 100)),
        ) { sizes.getValue(it.uploadId) }
        assertEquals(
            listOf(
                ResolveAction.MarkDone(1),
                ResolveAction.Requeue(2, NameCollisionPolicy.RENAME),
                ResolveAction.Requeue(3, NameCollisionPolicy.RENAME)
            ),
            actions
        )
    }

    @Test
    fun smartLeavesAConflictWaitingWhenTheSizeCannotBeRead() {
        val actions = ResolveAllPlanner.plan(ConflictChoice.SMART, listOf(conflict(1))) { RemoteSize.Unreadable("offline") }
        assertTrue(actions.single() is ResolveAction.Leave)
    }

    @Test
    fun anUploadThatRunsNowIsLeftAloneWhateverTheChoice() {
        ConflictChoice.RESOLVE_ALL_CHOICES.forEach { choice ->
            val actions = ResolveAllPlanner.plan(choice, listOf(conflict(1, uploading = true))) { RemoteSize.Known(100) }
            assertTrue("$choice", actions.single() is ResolveAction.Leave)
        }
    }

    @Test
    fun aMissingPhoneFileIsLeftWaitingExceptForKeepTheNoDriveCopy() {
        listOf(ConflictChoice.SMART, ConflictChoice.KEEP_PHONE, ConflictChoice.KEEP_BOTH).forEach { choice ->
            val actions = ResolveAllPlanner.plan(choice, listOf(conflict(1, exists = false))) { RemoteSize.Known(100) }
            assertTrue("$choice", actions.single() is ResolveAction.Leave)
        }
    }

    @Test
    fun theSizeIsLookedUpOnlyForSmart() {
        var lookups = 0
        val count: (WaitingConflict) -> RemoteSize<String> = { lookups++; RemoteSize.Known(1) }
        listOf(ConflictChoice.KEEP_NODRIVE, ConflictChoice.KEEP_PHONE, ConflictChoice.KEEP_BOTH).forEach {
            ResolveAllPlanner.plan(it, listOf(conflict(1)), count)
        }
        assertEquals(0, lookups)
        ResolveAllPlanner.plan(ConflictChoice.SMART, listOf(conflict(1)), count)
        assertEquals(1, lookups)
    }

    @Test
    fun everyRequeueComesFromAKnownPolicyAndThereIsNoDeleteAction() {
        val all = ConflictChoice.RESOLVE_ALL_CHOICES.flatMap {
            ResolveAllPlanner.plan(it, listOf(conflict(1), conflict(2, 5)), { RemoteSize.Known(5) })
        }
        assertTrue(all.all { it is ResolveAction.MarkDone || it is ResolveAction.Requeue || it is ResolveAction.Leave })
        assertTrue(all.filterIsInstance<ResolveAction.Requeue>().all {
            it.policy == NameCollisionPolicy.OVERWRITE || it.policy == NameCollisionPolicy.RENAME
        })
    }
}
