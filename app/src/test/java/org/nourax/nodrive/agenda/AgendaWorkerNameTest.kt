/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.android.AgendaRefreshWorker

/** One unique periodic work per account, named without the account's name or address. */
class AgendaWorkerNameTest {
    @Test
    fun theNameIsStableDifferentPerAccountAndHidesTheAccount() {
        val ali = AgendaRefreshWorker.uniqueName("ali@https://drive.example.test")
        val sara = AgendaRefreshWorker.uniqueName("sara@https://drive.example.test")
        assertEquals(ali, AgendaRefreshWorker.uniqueName("ali@https://drive.example.test"))
        assertNotEquals(ali, sara)
        assertTrue(ali.startsWith("nodrive-agenda-refresh-"))
        assertFalse(ali.contains("ali"))
        assertFalse(ali.contains("example"))
        assertEquals("nodrive-agenda-refresh-".length + 16, ali.length)
    }
}
