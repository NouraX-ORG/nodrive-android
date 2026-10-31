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

/**
 * The conflicts are decided as plan 4.5 says, and the delete guard has its thresholds (plan 4.9). That the first sync only adds is
 * tested on the engines themselves (`CalendarEngineTest`, `ContactsEngineTest`).
 */
class SyncPlansTest {
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
