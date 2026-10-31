/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import com.nextcloud.test.GrantTestPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * "Stop syncing" (plan 4.6, 4.9), in three steps that the CI script runs one after the other with the phone's network turned off and on
 * in between (like the offline tests of NoAgenda):
 *  a1  sync is on and a phone change is waiting;
 *  a2  no network: stopping is refused, nothing is removed from the phone;
 *  a3  network back: a final sync sends the change, then only our calendars and contacts are removed; the server still has everything.
 * and, on its own:
 *  b1  a phone edit and a server edit of one event meet during Stop's final sync: Stop is refused, nothing is removed, and the phone
 *      version waits in "Not saved" (it can still be reached). Only after the person decides does Stop go through.
 */
class DeviceSyncStopIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val name = "ds-stop-test.ics"
    private val path = "remote.php/dav/calendars/ali/work/$name"

    @Before
    fun requireServer() {
        assumeTrue("needs the throwaway server", support.agenda.hasServer)
    }

    @Test
    fun a1SyncIsOnAndAPhoneChangeIsWaiting() {
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
        val start = Instant.now().plus(60, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        support.putServerEvent("work", name, support.icsOf("ds-stop-uid", "Stop test event", start, start.plus(1, ChronoUnit.HOURS)))
        support.switchOn("/work/", "/contacts/")
        assertTrue(support.sync().ok)
        val calendar = support.calendarIdEndingWith("/work/")
        val row = checkNotNull(support.event(calendar, "Stop test event"))
        support.appSetTitle(row.id, "Stop test event (phone edit)")
        assertTrue(DeviceSyncRunner.waitingCount(support.context, support.account.name) >= 1)
        assertTrue(support.contactNames().isNotEmpty())
    }

    @Test
    fun a2WithoutANetworkStoppingIsRefusedAndNothingIsRemoved() {
        support.useExistingAccount()
        val result = DeviceSyncControl.stop(support.context, support.account.name)
        assertTrue("refused: $result", result is StopResult.Refused)
        assertTrue("the phone calendars are still there", support.calendarStore().calendarIds().isNotEmpty())
        assertTrue("the contacts are still there", support.contactNames().isNotEmpty())
        assertTrue("sync is still on", DeviceSyncSettings.get(support.context, support.account.name).enabled)
        assertTrue("the change still waits", DeviceSyncRunner.waitingCount(support.context, support.account.name) >= 1)
    }

    @Test
    fun a3WithTheNetworkBackAFinalSyncSendsTheChangeAndThenOnlyOurCopiesAreRemoved() {
        support.useExistingAccount()
        val serverBefore = support.serverEventCount("work")
        val result = DeviceSyncControl.stop(support.context, support.account.name)
        assertEquals(StopResult.Done, result)
        // the change reached the server before anything was removed
        val server = support.serverText(path)
        assertTrue(server, server.contains("SUMMARY:Stop test event (phone edit)"))
        // only our copies are gone
        assertTrue("no phone calendar of ours is left", support.calendarStore().calendarIds().isEmpty())
        assertTrue("no contact of ours is left", support.contactNames().isEmpty())
        assertFalse(DeviceSyncSettings.get(support.context, support.account.name).enabled)
        assertEquals(0, DeviceSyncControl.isSyncable(support.context, support.account.name, DeviceSyncConstants.AUTHORITY_CALENDAR))
        // the server still has everything
        assertEquals(serverBefore, support.serverEventCount("work"))
        assertTrue(support.serverText("remote.php/dav/addressbooks/users/ali/contacts/ci-contact-1.vcf").contains("Sara Contact"))
        runCatching { support.serverDelete(path) }
        support.cleanUp()
        support.agenda.reset()
    }

    @Test
    fun b1AConflictRefusesStopAndTheNotSavedEntryStaysListed() {
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
        val conflictName = "ds-stop-conflict.ics"
        val conflictPath = "remote.php/dav/calendars/ali/work/$conflictName"
        val account = support.account.name
        try {
            val start = Instant.now().plus(70, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
            support.putServerEvent("work", conflictName, support.icsOf("ds-stop-conflict-uid", "Stop conflict event", start, start.plus(1, ChronoUnit.HOURS)))
            support.switchOn("/work/")
            assertTrue(support.sync(setOf("calendar")).ok)
            val calendar = support.calendarIdEndingWith("/work/")
            support.appSetTitle(checkNotNull(support.event(calendar, "Stop conflict event")).id, "Stop conflict event (phone)")
            // the server changes the same event before the phone has sent its edit
            val old = support.serverText(conflictPath)
            support.putServerEvent("work", conflictName, old.replace("SUMMARY:Stop conflict event", "SUMMARY:Stop conflict event\r\nLOCATION:Server room"))

            val result = DeviceSyncControl.stop(support.context, account)

            assertTrue("refused: $result", result is StopResult.Refused)
            assertTrue("the refusal points to Not saved", (result as StopResult.Refused).notSaved >= 1)
            assertTrue("the phone calendars are still there", support.calendarStore().calendarIds().isNotEmpty())
            assertTrue("sync is still on", DeviceSyncSettings.get(support.context, account).enabled)
            val saved = DeviceSyncDatabase.get(support.context).dao().notSaved(account)
            assertEquals("the phone version is still listed", 1, saved.size)
            assertEquals("Stop conflict event (phone)", saved[0].title)
            // a second Stop is refused the same way: the entry is still there
            assertTrue(DeviceSyncControl.stop(support.context, account) is StopResult.Refused)

            // the person keeps the NoDrive version: nothing stands in the way any more
            DeviceSyncRunner.keepServerVersion(support.context, saved[0].id)
            assertEquals(StopResult.Done, DeviceSyncControl.stop(support.context, account))
            assertTrue("no phone calendar of ours is left", support.calendarStore().calendarIds().isEmpty())
            assertFalse(DeviceSyncSettings.get(support.context, account).enabled)
            assertTrue("the server still has the event", support.serverText(conflictPath).contains("LOCATION:Server room"))
        } finally {
            runCatching { support.serverDelete(conflictPath) }
            support.cleanUp()
            support.agenda.reset()
        }
    }
}
