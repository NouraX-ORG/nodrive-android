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
import org.nourax.nodrive.agenda.android.AgendaSendWorker

/** One unique send job per account (the changes that wait), named without the account's name or address, and apart from the refresh. */
class AgendaSendWorkerNameTest {
    @Test
    fun theNameIsStableDifferentPerAccountAndHidesTheAccount() {
        val ali = AgendaSendWorker.uniqueName("ali@https://drive.example.test")
        val sara = AgendaSendWorker.uniqueName("sara@https://drive.example.test")
        assertEquals(ali, AgendaSendWorker.uniqueName("ali@https://drive.example.test"))
        assertNotEquals(ali, sara)
        assertTrue(ali.startsWith("nodrive-agenda-send-"))
        assertFalse(ali.contains("ali"))
        assertFalse(ali.contains("example"))
        assertEquals("nodrive-agenda-send-".length + 16, ali.length)
        assertNotEquals(ali, AgendaRefreshWorker.uniqueName("ali@https://drive.example.test"))
    }
}
