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
import android.provider.ContactsContract
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import org.nourax.nodrive.devicesync.ui.DeviceSyncActivity

/**
 * Switching on, through the screens, against the throwaway server (plan 4.9): the backup comes first, the first sync only adds, nothing
 * on either side goes down, another account's rows are not touched, no to-do reaches the phone's calendar.
 */
class DeviceSyncFirstSyncIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = DeviceSyncTestSupport()
    private var otherCalendarId = 0L

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.agenda.hasServer)
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
    }

    @After
    fun tearDown() {
        support.cleanUp()
        if (otherCalendarId != 0L) {
            support.context.contentResolver.delete(
                CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, OTHER_NAME)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build(),
                CalendarContract.Calendars._ID + " = ?", arrayOf(otherCalendarId.toString())
            )
        }
        support.agenda.reset()
    }

    /** A local calendar of another source with an event, and a local contact: what the first sync must never touch. */
    private fun makeOtherAccountData(id: String): Pair<Long, Long> {
        val resolver = support.context.contentResolver
        fun asAdapter(base: android.net.Uri) = base.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, OTHER_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendarUri = checkNotNull(resolver.insert(asAdapter(CalendarContract.Calendars.CONTENT_URI), ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, OTHER_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "ds-other-$id")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Other calendar $id")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF336699.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, OTHER_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }))
        val calendarId = ContentUris.parseId(calendarUri)
        val start = System.currentTimeMillis() + DAY_MS
        resolver.insert(asAdapter(CalendarContract.Events.CONTENT_URI), ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "Other account event $id")
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + HOUR_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        })
        return calendarId to support.insertLocalContact("Other account person $id")
    }

    /** Everything of the other source's calendar and contact as text, to compare before and after. */
    private fun snapshotOther(calendarId: Long, contactId: Long): List<String> {
        val out = ArrayList<String>()
        val eventColumns = arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED, CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.ALL_DAY
        )
        support.context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, eventColumns, CalendarContract.Events.CALENDAR_ID + " = ?", arrayOf(calendarId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                out += (0 until c.columnCount).joinToString("|") { i -> c.getColumnName(i) + "=" + (if (c.isNull(i)) "null" else c.getString(i)) }
            }
        }
        val rawColumns = arrayOf(
            ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.DIRTY, ContactsContract.RawContacts.DELETED,
            ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.SYNC2
        )
        support.context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, rawColumns, ContactsContract.RawContacts._ID + " = ?", arrayOf(contactId.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                out += (0 until c.columnCount).joinToString("|") { i -> c.getColumnName(i) + "=" + (if (c.isNull(i)) "null" else c.getString(i)) }
            }
        }
        // the name of the contact is a data row
        support.context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DATA1), ContactsContract.Data.RAW_CONTACT_ID + " = ?",
            arrayOf(contactId.toString()), null
        )?.use { c -> while (c.moveToNext()) out += "data1=" + c.getString(0) }
        return out
    }

    private fun waitForTag(tag: String, millis: Long = UI_MILLIS) {
        compose.waitUntil(millis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        support.agenda.assertNoServerSoftwareName(compose)
        support.agenda.backup.sleepForAnimation()
        support.screenshot(name)
    }

    @Test
    fun theBackupComesFirstAndTheFirstSyncOnlyAdds() {
        val id = support.newId()
        val other = makeOtherAccountData(id)
        otherCalendarId = other.first
        val otherBefore = snapshotOther(other.first, other.second)
        assertTrue(otherBefore.isNotEmpty())

        // A phone event in a NoDrive calendar that was never linked: the first sync must add it to the server, and keep it.
        DeviceSyncSettings.update(support.context, support.account.name, enabled = false)
        check(DeviceSyncRunner.discover(support.context, support.account.name))
        val workHref = DeviceSyncDatabase.get(support.context).dao().collections(support.account.name).first { it.href.endsWith("/work/") }.href
        val workCalendar = support.calendarStore().ensureCalendar(workHref, "Work", 0xFF2563EB.toInt(), false)
        val phoneOnly = "Phone only event $id"
        val start = System.currentTimeMillis() + 2 * DAY_MS
        support.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, workCalendar)
            put(CalendarContract.Events.TITLE, phoneOnly)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + HOUR_MS)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        })
        val serverBefore = support.serverEventCount("work")
        val backupBeforeCalendar = support.listing("remote.php/dav/files/ali/.Calendar-Backup/").size
        val backupBeforeContacts = support.listing("remote.php/dav/files/ali/.Contacts-Backup/").size

        // Switching on without the backup does nothing on the phone.
        DeviceSyncSettings.update(support.context, support.account.name, enabled = true, backupDoneUtc = 0)
        val early = support.sync()
        assertFalse(early.ok)
        assertEquals("backup_pending", early.message)
        assertTrue("nothing of the server was added before the backup", support.events(workCalendar).none { it.title == "Writer-Bot review" })
        DeviceSyncSettings.update(support.context, support.account.name, enabled = false)

        ActivityScenario.launch(DeviceSyncActivity::class.java).use {
            waitForTag("ds_intro_continue")
            shot("intro")
            compose.onNodeWithTag("ds_intro_continue").performClick()

            // choose: the calendars and address books with their defaults
            waitForTag("ds_choose_continue")
            waitForTag("ds_item_work")
            compose.onNode(isToggleable().and(hasAnyAncestor(hasTestTag("ds_item_work")))).assertIsOn()
            compose.onNode(isToggleable().and(hasAnyAncestor(hasTestTag("ds_item_holidays")))).assertIsOff()
            shot("choose")
            compose.onNodeWithTag("ds_choose_continue").performClick()

            // the backup, then the first sync
            compose.waitUntil(BACKUP_MILLIS) {
                compose.onAllNodesWithTag("ds_backup_done").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("ds_first_sync_text").fetchSemanticsNodes().isNotEmpty()
            }
            val backupDoneAt = DeviceSyncSettings.get(support.context, support.account.name).backupDoneUtc
            assertTrue("the backup is recorded before the first sync", backupDoneAt > 0)
            assertTrue("the calendar backup is on the server", support.listing("remote.php/dav/files/ali/.Calendar-Backup/").size > backupBeforeCalendar)
            assertTrue("the contacts backup is on the server", support.listing("remote.php/dav/files/ali/.Contacts-Backup/").size > backupBeforeContacts)
            support.screenshot("backup")
            waitForTag("ds_first_sync_done", FIRST_SYNC_MILLIS)
            shot("first-sync")
            compose.onNodeWithTag("ds_first_sync_continue").performClick()
            waitForTag("ds_screen_status")
            compose.waitUntil(UI_MILLIS) {
                compose.onAllNodesWithTag("ds_last_sync").fetchSemanticsNodes().isNotEmpty()
            }
            shot("status")
        }

        // The server's events are on the phone and nothing was deleted on either side.
        val phoneWork = support.calendarIdEndingWith("/work/")
        val titles = support.events(phoneWork).map { it.title }
        for (t in listOf("Client call", "Writer-Bot review", "Planning meeting", "Stand-up", "Day off", phoneOnly)) assertTrue("$t on the phone: $titles", t in titles)
        assertEquals("the server holds the phone's own event too and lost none", serverBefore + 1, support.serverEventCount("work"))
        assertTrue("the phone event is on the server", support.raw.export("work").contains("SUMMARY:$phoneOnly"))
        val phoneOnlyRow = checkNotNull(support.event(phoneWork, phoneOnly))
        assertFalse("it is linked to its server object now", phoneOnlyRow.syncId.isNullOrEmpty())

        // No to-do reached the phone's calendar, and the board app's and NoGantt's copy calendars are not there.
        val allTitles = support.calendarStore().calendarIds().keys.flatMap { support.events(it) }.map { it.title }
        for (todo in listOf("Renew passport", "Late bill", "Someday", "Draft report", "Completed already")) {
            assertFalse("to-do \"$todo\" must not be in the phone's calendar", todo in allTitles)
        }
        assertTrue("an event of the mixed calendar is there", "Dentist" in allTitles)
        val hrefs = support.calendarStore().calendarIds().values
        assertTrue(hrefs.toString(), hrefs.none { it.contains("app-generated") || it.contains("contact_birthdays") || it.contains("holidays") })

        // The contacts book was added too; the other source is byte for byte as it was.
        assertTrue(support.contactNames().values.contains("Sara Contact"))
        assertEquals(otherBefore, snapshotOther(other.first, other.second))
    }

    private companion object {
        const val OTHER_NAME = "ds-other-source"
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
        const val UI_MILLIS = 60_000L
        const val BACKUP_MILLIS = 8 * 60_000L
        const val FIRST_SYNC_MILLIS = 5 * 60_000L
    }
}
