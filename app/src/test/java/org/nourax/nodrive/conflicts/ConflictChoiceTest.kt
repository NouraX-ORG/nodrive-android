/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConflictChoiceTest {
    @Test
    fun everyChoiceMapsToItsUpstreamValue() {
        assertEquals(NameCollisionPolicy.ASK_USER, ConflictChoice.SMART.upstreamPolicy())
        assertEquals(NameCollisionPolicy.SKIP, ConflictChoice.KEEP_NODRIVE.upstreamPolicy())
        assertEquals(NameCollisionPolicy.OVERWRITE, ConflictChoice.KEEP_PHONE.upstreamPolicy())
        assertEquals(NameCollisionPolicy.RENAME, ConflictChoice.KEEP_BOTH.upstreamPolicy())
        assertEquals(NameCollisionPolicy.ASK_USER, ConflictChoice.ASK.upstreamPolicy())
    }

    @Test
    fun smartNeverMapsToOverwrite() {
        assertNotEquals(NameCollisionPolicy.OVERWRITE, ConflictChoice.SMART.upstreamPolicy())
    }

    @Test
    fun upstreamOrdinalsAreNotShifted() {
        // The values are stored in the database: a reordered enum upstream would silently change every row.
        assertEquals(0, NameCollisionPolicy.RENAME.serialize())
        assertEquals(1, NameCollisionPolicy.OVERWRITE.serialize())
        assertEquals(2, NameCollisionPolicy.SKIP.serialize())
        assertEquals(3, NameCollisionPolicy.ASK_USER.serialize())
    }

    @Test
    fun keysRoundTripAndMissingMeansSmart() {
        ConflictChoice.entries.forEach { assertEquals(it, ConflictChoice.fromKey(it.key)) }
        assertEquals(ConflictChoice.SMART, ConflictChoice.fromKey(null))
        assertEquals(ConflictChoice.SMART, ConflictChoice.fromKey(""))
        assertEquals(ConflictChoice.SMART, ConflictChoice.fromKey("something-new"))
    }

    @Test
    fun resolveAllOffersExactlyTheFourChoices() {
        assertEquals(
            listOf(
                ConflictChoice.SMART,
                ConflictChoice.KEEP_NODRIVE,
                ConflictChoice.KEEP_PHONE,
                ConflictChoice.KEEP_BOTH
            ),
            ConflictChoice.RESOLVE_ALL_CHOICES
        )
        assertFalse(ConflictChoice.ASK in ConflictChoice.RESOLVE_ALL_CHOICES)
    }

    @Test
    fun anOwnedRowShowsItsOwnValueAndAskUserMeansTheCarrierUnlessTheAccountAsks() {
        val smart = ConflictChoice.SMART
        assertEquals(ConflictChoice.KEEP_NODRIVE, ConflictChoice.forOwnedRow(NameCollisionPolicy.SKIP, smart))
        assertEquals(ConflictChoice.KEEP_PHONE, ConflictChoice.forOwnedRow(NameCollisionPolicy.OVERWRITE, smart))
        assertEquals(ConflictChoice.KEEP_BOTH, ConflictChoice.forOwnedRow(NameCollisionPolicy.RENAME, smart))
        assertEquals(ConflictChoice.SMART, ConflictChoice.forOwnedRow(NameCollisionPolicy.ASK_USER, smart))
        assertEquals(ConflictChoice.ASK, ConflictChoice.forOwnedRow(NameCollisionPolicy.ASK_USER, ConflictChoice.ASK))
        assertEquals(ConflictChoice.KEEP_BOTH, ConflictChoice.forOwnedRow(null, ConflictChoice.KEEP_BOTH))
    }
}
