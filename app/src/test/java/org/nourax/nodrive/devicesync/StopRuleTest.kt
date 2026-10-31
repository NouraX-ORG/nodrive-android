/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.devicesync.dav.CardDavClient
import org.nourax.nodrive.devicesync.model.SyncOutcome
import java.time.ZoneOffset

/**
 * "Stop syncing" (plan 4.6) refuses only for the person's own data: phone changes that were not sent and phone versions that wait in
 * "Not saved". A sync that fails for a lasting reason of its own, with nothing waiting, never holds the removal up.
 */
class StopRuleTest {
    @Test
    fun nothingWaitingAndNothingNotSavedMeansStopMayRemove() {
        assertNull(StopRule.refusal(SyncOutcome.EMPTY, 0, 0))
    }

    @Test
    fun aChangeThatWasNotSentRefusesStop() {
        val refusal = checkNotNull(StopRule.refusal(SyncOutcome.EMPTY, 2, 0))
        assertEquals(2, refusal.waiting)
        assertEquals(0, refusal.notSaved)
        // the sync itself reports an unsent change (the count of the phone says none: it still counts)
        val reported = SyncOutcome(false, waiting = 3, message = "not_sent_500")
        assertEquals(3, checkNotNull(StopRule.refusal(reported, 0, 0)).waiting)
    }

    @Test
    fun aPhoneVersionInNotSavedRefusesStopAndPointsToIt() {
        val refusal = checkNotNull(StopRule.refusal(SyncOutcome.EMPTY, 0, 1))
        assertEquals(0, refusal.waiting)
        assertEquals(1, refusal.notSaved)
    }

    @Test
    fun aSyncThatFailsForALastingReasonWithNothingWaitingDoesNotHoldStopUp() {
        // NoGantt's task-sync answers an error for ever (or 412 "App is not enabled" for a person outside its group): the calendar list
        // is unknown, every sync fails with this message, but the person has no unsent change
        for (message in listOf("calendars_unknown", "offline", "server_500", "server_403", "safety_copy_not_saved_500", "multiget_500", "error")) {
            assertNull(message, StopRule.refusal(SyncOutcome.failed(message), 0, 0))
        }
    }

    // ---- what NoGantt's task-sync can answer (the decision: only 404 and a well-formed answer are known, nothing else is assumed)

    private val server = FakeDavServer()

    private fun refresh(status: Int, body: String = ""): RefreshResult {
        server.taskSyncStatus = status
        server.taskSyncBody = body
        server.calendarList = listOf(FakeCollectionSpec("/remote.php/dav/calendars/ali/work/", "Work"))
        server.bookList = listOf(FakeCollectionSpec("/remote.php/dav/addressbooks/users/ali/contacts/", "Contacts"))
        return CollectionRefresh.run(SyncEnv("ali@drive.test", server, CardDavClient(server, "ali"), FakeDao(), ZoneOffset.UTC))
    }

    @Test
    fun a412AppIsNotEnabledAnswerKeepsTheCalendarsOffAndTheContactsOn() {
        // The server's app check: 412 for a person outside a group-restricted NoGantt. Such a person may still have the copy
        // calendar from before, so it is not known that there is none: no calendar is offered or synced. Contacts are not affected.
        val result = refresh(412, "{\"ocs\":{\"meta\":{\"status\":\"failure\",\"statuscode\":412,\"message\":\"App is not enabled\"},\"data\":[]}}")
        assertTrue(result.ok)
        assertFalse(result.calendarsKnown)
    }

    @Test
    fun aLastingServerErrorOfTaskSyncKeepsTheCalendarsOffAndTheContactsOn() {
        val result = refresh(500)
        assertTrue(result.ok)
        assertFalse(result.calendarsKnown)
    }
}
