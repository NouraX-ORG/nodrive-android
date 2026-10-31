/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The two-way sync of events against the throwaway server (plan 4.9), run through the app's own sync code. The phone changes are made the
 * way any app makes them (so the rows become dirty). Sync is not made "syncable" for the phone's own scheduler here, so each step is run
 * on purpose and the steps never race with a sync that the phone starts by itself.
 */
class DeviceSyncTwoWayIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val created = ArrayList<String>()
    private lateinit var workCalendar: Pair<Long, String>

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.agenda.hasServer)
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
        support.switchOn("/work/")
        val first = support.sync(setOf("calendar"))
        assertTrue("the first sync of the work calendar", first.ok)
        workCalendar = support.calendarIdEndingWith("/work/") to "work"
    }

    @After
    fun tearDown() {
        for (name in created) runCatching { support.serverDelete("remote.php/dav/calendars/ali/work/$name") }
        support.cleanUp()
        support.agenda.reset()
    }

    private fun serverEvent(name: String, summary: String, hoursAhead: Long = 30, vararg extra: String): String {
        val uid = "ds-" + support.newId()
        val start = Instant.now().plus(hoursAhead, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        support.putServerEvent("work", name, support.icsOf(uid, summary, start, start.plus(1, ChronoUnit.HOURS), *extra))
        created += name
        return uid
    }

    private fun phoneEvent(title: String) = support.event(workCalendar.first, title)

    @Test
    fun aPhoneEditOfTheTaggedEventKeepsItsAgentIdAndCategoryOnTheServer() {
        val row = checkNotNull(phoneEvent("Writer-Bot review"))
        support.appSetTitle(row.id, "Writer-Bot review (edited on the phone)")
        assertTrue(phoneEvent("Writer-Bot review (edited on the phone)")!!.dirty)
        val outcome = support.sync(setOf("calendar"))
        assertTrue(outcome.message, outcome.ok)
        val text = support.raw.export("work")
        assertTrue(text, text.contains("SUMMARY:Writer-Bot review (edited on the phone)"))
        val whole = checkNotNull(text.split("BEGIN:VEVENT").firstOrNull { it.contains("SUMMARY:Writer-Bot review (edited on the phone)") })
        assertTrue("the agent id survived: $whole", whole.contains("X-NOURAX-AGENT-ID:AG-0001"))
        assertTrue("the category survived: $whole", whole.contains("CATEGORIES:agent:AG-0001,review"))
        assertFalse("clean again", phoneEvent("Writer-Bot review (edited on the phone)")!!.dirty)
        // and back to its old title, so the other tests find the seeded one
        support.appSetTitle(phoneEvent("Writer-Bot review (edited on the phone)")!!.id, "Writer-Bot review")
        assertTrue(support.sync(setOf("calendar")).ok)
        assertTrue(support.raw.export("work").contains("SUMMARY:Writer-Bot review"))
    }

    @Test
    fun aServerEditReachesThePhone() {
        val name = "ds-server-edit-${support.newId()}.ics"
        serverEvent(name, "Server edit before")
        assertTrue(support.sync(setOf("calendar")).ok)
        assertNotNull(phoneEvent("Server edit before"))
        // the server's copy changes (another app, the web)
        val old = support.serverText("remote.php/dav/calendars/ali/work/$name")
        support.putServerEvent("work", name, old.replace("SUMMARY:Server edit before", "SUMMARY:Server edit after"))
        val outcome = support.sync(setOf("calendar"))
        assertTrue(outcome.message, outcome.ok)
        assertEquals(1, outcome.changed)
        assertNotNull(phoneEvent("Server edit after"))
        assertEquals(null, phoneEvent("Server edit before"))
    }

    @Test
    fun oneChangedOccurrenceOfTheStandUpIsARecurrenceIdOnTheServer() {
        val master = checkNotNull(phoneEvent("Stand-up"))
        // the daily stand-up of 09:30 UTC, three days ago: the occurrence of today
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES).toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, today)
            put(CalendarContract.Events.TITLE, "Stand-up (moved by the phone)")
            put(CalendarContract.Events.DTSTART, today + 3_600_000L)
        }
        support.context.contentResolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, master.id), values)
        assertTrue(support.sync(setOf("calendar")).ok)
        val text = support.raw.export("work")
        assertTrue(text, text.contains("RECURRENCE-ID"))
        assertTrue(text, text.contains("SUMMARY:Stand-up (moved by the phone)"))
        assertTrue("the series is still there", text.contains("RRULE:FREQ=DAILY"))
        // back on the phone nothing was lost or doubled: the master is one row, the exception another
        assertEquals(1, support.events(workCalendar.first).count { it.title == "Stand-up" })
    }

    @Test
    fun aConflictLetsTheServerWinKeepsThePhoneVersionAndApplyMyChangeAgainGivesBoth() {
        val name = "ds-conflict-${support.newId()}.ics"
        serverEvent(name, "Conflict original", hoursAhead = 40)
        assertTrue(support.sync(setOf("calendar")).ok)
        val row = checkNotNull(phoneEvent("Conflict original"))
        // both change before the next sync: the phone the title, the server the place
        support.appSetTitle(row.id, "Conflict title from the phone")
        val old = support.serverText("remote.php/dav/calendars/ali/work/$name")
        support.putServerEvent("work", name, old.replace("SUMMARY:Conflict original", "SUMMARY:Conflict original\r\nLOCATION:Server room"))
        val outcome = support.sync(setOf("calendar"))
        assertTrue(outcome.message, outcome.ok)
        assertEquals("the phone version is kept", 1, outcome.notSaved)
        assertNotNull("the server version is on the phone", phoneEvent("Conflict original"))
        assertEquals(null, phoneEvent("Conflict title from the phone"))
        val saved = DeviceSyncDatabase.get(support.context).dao().notSaved(support.account.name)
        assertEquals(1, saved.size)
        assertEquals("Conflict title from the phone", saved[0].title)
        // "Apply my change again": the phone's title goes on top of the server's version, which keeps its place
        assertTrue(DeviceSyncRunner.applyAgain(support.context, support.account.name, saved[0].id))
        val server = support.serverText("remote.php/dav/calendars/ali/work/$name")
        assertTrue(server, server.contains("SUMMARY:Conflict title from the phone"))
        assertTrue(server, server.contains("LOCATION:Server room"))
        assertTrue(DeviceSyncDatabase.get(support.context).dao().notSaved(support.account.name).isEmpty())
        assertTrue(support.sync(setOf("calendar")).ok)
        assertNotNull(phoneEvent("Conflict title from the phone"))
    }

    @Test
    fun aPhoneDeleteLandsInTheServersCalendarTrashBin() {
        val id = support.newId()
        val name = "ds-delete-$id.ics"
        serverEvent(name, "Delete me $id")
        assertTrue(support.sync(setOf("calendar")).ok)
        val row = checkNotNull(phoneEvent("Delete me $id"))
        support.appDeleteEvent(row.id)
        assertEquals(null, phoneEvent("Delete me $id"))
        val outcome = support.sync(setOf("calendar"))
        assertTrue(outcome.message, outcome.ok)
        assertEquals(404, support.serverStatus("remote.php/dav/calendars/ali/work/$name"))
        val trash = support.trashed()
        assertTrue("the object is in the trash bin: ${support.lastListing}", trash.contains("SUMMARY:Delete me $id"))
    }

    @Test
    fun manyPhoneDeletesAreHeldBackByTheGuardUntilThePersonSaysGoAhead() {
        val id = support.newId()
        val names = (1..30).map { "ds-bulk-$id-$it.ics" }
        for ((i, n) in names.withIndex()) serverEvent(n, "Bulk $id $i", hoursAhead = 50L + i)
        assertTrue(support.sync(setOf("calendar")).ok)
        val rows = support.events(workCalendar.first).filter { it.title.startsWith("Bulk $id ") }
        assertEquals(30, rows.size)
        for (row in rows.take(25)) support.appDeleteEvent(row.id)
        val held = support.sync(setOf("calendar"))
        assertTrue("the guard says so", held.tooManyDeletions)
        for (n in names) assertEquals("nothing deleted on the server: $n", 200, support.serverStatus("remote.php/dav/calendars/ali/work/$n"))
        // the person answers "go ahead": the system's next sync carries the extra
        val go = support.sync(setOf("calendar"), SyncFlags(overrideTooManyDeletions = true))
        assertTrue(go.ok)
        assertFalse(go.tooManyDeletions)
        val gone = names.count { support.serverStatus("remote.php/dav/calendars/ali/work/$it") == 404 }
        assertEquals(25, gone)
    }
}
