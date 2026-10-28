/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRuleTest {
    private fun decide(local: Long, remote: RemoteSize<String>) = SmartRule.decide(local, remote)

    @Test
    fun sameSizeIsAlreadyBackedUp() {
        assertEquals(SmartDecision.AlreadyBackedUp, decide(1234, RemoteSize.Known(1234)))
    }

    @Test
    fun differentSizeKeepsBoth() {
        assertEquals(SmartDecision.KeepBoth, decide(1234, RemoteSize.Known(1235)))
        assertEquals(SmartDecision.KeepBoth, decide(1234, RemoteSize.Known(0)))
    }

    @Test
    fun zeroByteFilesCompareLikeAnyOther() {
        assertEquals(SmartDecision.AlreadyBackedUp, decide(0, RemoteSize.Known(0)))
        assertEquals(SmartDecision.KeepBoth, decide(0, RemoteSize.Known(1)))
    }

    @Test
    fun sizesAboveTwoGigabytesAreNotTruncated() {
        val big = 3L * 1024 * 1024 * 1024
        assertEquals(SmartDecision.AlreadyBackedUp, decide(big, RemoteSize.Known(big)))
        // 3 GB and 3 GB + 2^32 would be equal if the sizes were cut to 32 bits.
        assertEquals(SmartDecision.KeepBoth, decide(big, RemoteSize.Known(big + (1L shl 32))))
    }

    @Test
    fun aFileThatVanishedIsUploadedUnderANewName() {
        assertEquals(SmartDecision.KeepBoth, decide(10, RemoteSize.Missing))
    }

    @Test
    fun everyUnreadableAnswerIsARetryWithTheOriginalFailure() {
        listOf("network", "401", "403", "unknown").forEach { failure ->
            assertEquals(SmartDecision.Retry(failure), decide(10, RemoteSize.Unreadable(failure)))
        }
    }

    @Test
    fun noInputGivesAnOutcomeOtherThanTheThreeKnownOnes() {
        // The rule has no overwrite and no delete outcome: every answer maps to one of these three.
        val known = setOf(SmartDecision.AlreadyBackedUp, SmartDecision.KeepBoth)
        listOf(0L, 1L, 10L, Long.MAX_VALUE).forEach { local ->
            listOf(RemoteSize.Known(0), RemoteSize.Known(local), RemoteSize.Known(Long.MAX_VALUE), RemoteSize.Missing)
                .forEach { assertEquals(true, decide(local, it) in known) }
            assertEquals(SmartDecision.Retry("x"), decide(local, RemoteSize.Unreadable("x")))
        }
    }
}
