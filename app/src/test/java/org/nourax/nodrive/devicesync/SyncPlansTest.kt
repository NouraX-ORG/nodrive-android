/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first sync only adds, the conflicts are decided as plan 4.5 says, and the delete guard has its thresholds (plan 4.9). */
class SyncPlansTest {
    private fun server(vararg pairs: Pair<String, String>) = pairs.map { ServerRef("/c/${it.first}.ics", "\"e\"", it.second) }

    private fun row(id: Long, syncId: String?, uid: String, dirty: Boolean = false, deleted: Boolean = false) =
        PhoneRowRef(id, syncId, uid, dirty, deleted)

    // ---- the first sync

    @Test
    fun serverObjectsThatAreNotOnThePhoneAreAddedToThePhone() {
        val plan = FirstSyncPlan.plan(server("a" to "ua", "b" to "ub"), emptyList())
        assertEquals(2, plan.filterIsInstance<FirstSyncAction.AddToPhone>().size)
        assertEquals(2, plan.size)
    }

    @Test
    fun phoneRowsWithoutAServerObjectAreAddedToTheServer() {
        val plan = FirstSyncPlan.plan(emptyList(), listOf(row(1, null, "x"), row(2, "/c/old.ics", "y")))
        assertEquals(listOf(1L, 2L), plan.filterIsInstance<FirstSyncAction.AddToServer>().map { it.row.rowId })
    }

    @Test
    fun anObjectOnBothSidesIsLinkedByUidAndNothingIsOverwritten() {
        val plan = FirstSyncPlan.plan(server("a" to "same"), listOf(row(1, null, "same", dirty = true)))
        assertEquals(1, plan.size)
        val link = plan.single() as FirstSyncAction.Link
        assertEquals(1L, link.row.rowId)
        assertEquals("/c/a.ics", link.server.href)
    }

    @Test
    fun anObjectAlreadyLinkedByAddressIsLeftAlone() {
        val plan = FirstSyncPlan.plan(server("a" to "ua"), listOf(row(1, "/c/a.ics", "ua")))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun aDeletedPhoneRowWaitsAndNothingIsEverDeleted() {
        val plan = FirstSyncPlan.plan(server("a" to "ua"), listOf(row(1, "/c/gone.ics", "ug", deleted = true)))
        assertEquals(1, plan.filterIsInstance<FirstSyncAction.Wait>().size)
        // the plan has no way to say "delete": the only actions are add, link and wait
        assertTrue(plan.all { it is FirstSyncAction.AddToPhone || it is FirstSyncAction.AddToServer || it is FirstSyncAction.Link || it is FirstSyncAction.Wait })
    }

    @Test
    fun aMissingServerObjectForARowWithAnOldAddressIsAnAddNeverADelete() {
        val plan = FirstSyncPlan.plan(emptyList(), listOf(row(7, "/c/was-here.ics", "u7")))
        assertEquals(1, plan.filterIsInstance<FirstSyncAction.AddToServer>().size)
    }

    // ---- conflicts

    @Test
    fun theServerWinsWhenBothChangedAndThePhoneVersionIsKept() {
        assertEquals(Decision.SERVER_WINS_KEEP_PHONE_VERSION, ConflictPlan.decide(true, false, ServerState.CHANGED))
    }

    @Test
    fun anEditBeatsADeleteInBothDirections() {
        assertEquals(Decision.UPLOAD_AS_NEW, ConflictPlan.decide(true, false, ServerState.DELETED))
        assertEquals(Decision.KEEP_SERVER_VERSION, ConflictPlan.decide(false, true, ServerState.CHANGED))
    }

    @Test
    fun aCleanChangeGoesTheNormalWay() {
        assertEquals(Decision.UPLOAD_PATCH, ConflictPlan.decide(true, false, ServerState.UNCHANGED))
        assertEquals(Decision.UPLOAD_DELETE, ConflictPlan.decide(false, true, ServerState.UNCHANGED))
        assertEquals(Decision.APPLY_SERVER, ConflictPlan.decide(false, false, ServerState.CHANGED))
        assertEquals(Decision.REMOVE_FROM_PHONE, ConflictPlan.decide(false, false, ServerState.DELETED))
        assertEquals(Decision.NONE, ConflictPlan.decide(false, false, ServerState.UNCHANGED))
        assertEquals(Decision.FORGET_ROW, ConflictPlan.decide(false, true, ServerState.DELETED))
    }

    // ---- the delete guard

    @Test
    fun theGuardNeedsMoreThanTwentyDeletesAndMoreThanTwentyPercent() {
        assertFalse("20 deletes", DeleteGuard.tooMany(20, 30))
        assertTrue("21 of 30", DeleteGuard.tooMany(21, 30))
        assertFalse("21 of 200 is 10 percent", DeleteGuard.tooMany(21, 200))
        assertTrue("25 of 100", DeleteGuard.tooMany(25, 100))
        assertFalse("20 percent exactly is not more", DeleteGuard.tooMany(21, 105))
        assertFalse("a few deletes in a small book", DeleteGuard.tooMany(3, 3))
    }

    @Test
    fun anAnswerOfThePersonLetsTheDeletesGoAhead() {
        assertFalse(DeleteGuard.allow(25, 30, overrideTooMany = false))
        assertTrue(DeleteGuard.allow(25, 30, overrideTooMany = true))
        assertTrue(DeleteGuard.allow(5, 30, overrideTooMany = false))
    }
}
