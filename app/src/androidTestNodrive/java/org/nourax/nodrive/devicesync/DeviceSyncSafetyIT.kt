/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import com.google.gson.JsonObject
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * The safety fixes of review round 2, on the emulator against the throwaway server:
 *  - a calendar that the server deleted does not hold "Stop syncing" up, even with a change waiting in it;
 *  - a photo set on the phone survives an edit of the card on the server, and reaches the server;
 *  - NoGantt's copy calendar of an account that has it switched on is never offered, and one that was chosen leaves the phone.
 */
class DeviceSyncSafetyIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val book = "remote.php/dav/addressbooks/users/ali/contacts/"
    private val taskSync = "ocs/v2.php/apps/nc_gantt/api/v1/task-sync?format=json"

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
        support.agenda.reset()
    }

    private fun dao() = DeviceSyncDatabase.get(support.context).dao()

    @Test
    fun aCalendarThatTheServerDeletedDoesNotHoldStopSyncingUp() {
        val id = support.newId()
        val calendar = "ds-gone-$id"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:mkcalendar xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:set><d:prop>" +
            "<d:displayname>Gone $id</d:displayname><c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>" +
            "</d:prop></d:set></c:mkcalendar>"
        val made = support.dav("MKCALENDAR", "remote.php/dav/calendars/ali/$calendar/", body, "application/xml; charset=utf-8")
        assertTrue("MKCALENDAR answered $made", made in 200..299)
        val start = Instant.now().plus(80, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES)
        support.putServerEvent(calendar, "gone.ics", support.icsOf("gone-uid-$id", "Gone event", start, start.plus(1, ChronoUnit.HOURS)))
        support.switchOn("/$calendar/")
        val first = support.sync(setOf("calendar"))
        assertTrue(first.message, first.ok)
        val phoneCalendar = support.calendarIdEndingWith("/$calendar/")
        val row = checkNotNull(support.event(phoneCalendar, "Gone event"))
        support.appSetTitle(row.id, "Gone event (phone edit)")
        assertTrue("a change is waiting", DeviceSyncRunner.waitingCount(support.context, support.account.name) >= 1)

        // the calendar is deleted on the web
        val deleted = support.dav("DELETE", "remote.php/dav/calendars/ali/$calendar/")
        assertTrue("DELETE answered $deleted", deleted in 200..299)
        val after = support.sync(setOf("calendar"))
        assertTrue("a sync after the deletion is not an error: ${after.message}", after.ok)
        val gone = dao().collections(support.account.name).first { it.href.contains("/$calendar/") }
        assertEquals(RemoteCollection.OFF_GONE, gone.offReason)
        assertEquals("a change that can never be sent does not wait", 0, DeviceSyncRunner.waitingCount(support.context, support.account.name))

        val result = DeviceSyncControl.stop(support.context, support.account.name)

        assertEquals("Stop is not refused", StopResult.Done, result)
        assertTrue("no phone calendar of ours is left", support.calendarStore().calendarIds().isEmpty())
        assertFalse(DeviceSyncSettings.get(support.context, support.account.name).enabled)
        assertTrue("the collection is forgotten", dao().collections(support.account.name).none { it.href.contains("/$calendar/") })
    }

    private fun dataRows(rawId: Long, mime: String): Int {
        var n = 0
        support.context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            ContactsContract.Data.RAW_CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?", arrayOf(rawId.toString(), mime), null
        )?.use { n = it.count }
        return n
    }

    @Test
    fun aPhotoSetOnThePhoneSurvivesAServerEditAndReachesTheServer() {
        val name = "Round2 Photo ${support.newId()}"
        val cardPath = book + "ds-r2-photo.vcf"
        fun card(note: String) = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:ds-r2-photo\r\nFN:$name\r\nN:Photo;Round2;;;\r\nTEL;TYPE=CELL:+49 30 5550001\r\n" +
            (if (note.isEmpty()) "" else "NOTE:$note\r\n") + "END:VCARD\r\n"
        val vcard = mapOf("Content-Type" to "text/vcard; charset=utf-8")
        try {
            assertTrue(support.raw.request("PUT", cardPath, card(""), vcard).status in 200..299)
            support.switchOn("/contacts/")
            assertTrue(support.sync(setOf("contacts")).ok)
            val rawId = support.contactsStore().rows().first { it.contact.displayName == name }.id
            assertEquals("the card has no photo, the phone has none", 0, dataRows(rawId, CommonDataKinds.Photo.CONTENT_ITEM_TYPE))

            // a photo set in the Contacts app (an ordinary app: the row becomes dirty) ...
            support.context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                put(CommonDataKinds.Photo.PHOTO, Base64.getDecoder().decode(PNG))
            })
            assertTrue("the row is dirty", support.contactsStore().rows().first { it.id == rawId }.dirty)
            // ... and the card is edited on the server before the phone has sent it
            assertTrue(support.raw.request("PUT", cardPath, card("edited on the web"), vcard).status in 200..299)

            val outcome = support.sync(setOf("contacts"))
            assertTrue(outcome.message, outcome.ok)
            assertEquals("the phone's version was kept", 1, outcome.notSaved)
            assertEquals("the server's edit is on the phone", "edited on the web", support.contactsStore().rows().first { it.id == rawId }.contact.note)
            assertTrue("the phone's photo is still there", dataRows(rawId, CommonDataKinds.Photo.CONTENT_ITEM_TYPE) >= 1)

            // "Apply my change again" sends the photo
            val entry = dao().notSaved(support.account.name).single()
            assertTrue(DeviceSyncRunner.applyAgain(support.context, support.account.name, entry.id))
            val onServer = support.serverText(cardPath)
            assertTrue("the photo reached the card: $onServer", onServer.contains("PHOTO;ENCODING=b"))
            assertTrue("the server's edit is still there", onServer.contains("NOTE:edited on the web"))

            // the card is edited again on the server: the photo is still on the phone
            assertTrue(support.raw.request("PUT", cardPath, onServer.replace("NOTE:edited on the web", "NOTE:edited again"), vcard).status in 200..299)
            val again = support.sync(setOf("contacts"))
            assertTrue(again.message, again.ok)
            assertEquals("edited again", support.contactsStore().rows().first { it.id == rawId }.contact.note)
            assertTrue("the photo survives a second server edit", dataRows(rawId, CommonDataKinds.Photo.CONTENT_ITEM_TYPE) >= 1)
        } finally {
            runCatching { support.serverDelete(cardPath) }
        }
    }

    @Test
    fun aTaskCopyCalendarIsNeverOfferedAndOneThatWasChosenLeavesThePhone() {
        val on = support.raw.json("PUT", taskSync, JsonObject().apply { addProperty("enabled", true); addProperty("create_calendar", true) })
        try {
            assertTrue("switching the copy on answered ${on.status}: ${on.body.take(300)}", on.status in 200..299)
            val uri = support.raw.request("GET", taskSync).ocsData().asJsonObject.get("calendar_uri")
                ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            assertTrue("the copy calendar has a name: ${on.body.take(300)}", uri.isNotEmpty())
            assertEquals("the copy calendar is on the server", 200, support.serverStatus("remote.php/dav/calendars/ali/$uri/?export"))

            support.switchOn("/work/")
            val rows = dao().collections(support.account.name)
            assertTrue("it is not offered: ${rows.map { it.href }}", rows.none { it.href.contains("/$uri/") })
            assertTrue("the real calendar is", rows.any { it.href.contains("/work/") && it.chosen })

            // a copy that was chosen before (the old code let it in)
            val href = "/remote.php/dav/calendars/ali/$uri/"
            dao().putCollection(CollectionEntity(support.account.name, href, RemoteCollection.CALENDAR, "Task copies", "", false, "", true, "", 0L, false, 0))
            support.calendarStore().ensureCalendar(href, "Task copies", 0, false)
            assertTrue(support.calendarStore().calendarIds().values.contains(href))

            val outcome = support.sync(setOf("calendar"))

            assertTrue(outcome.message, outcome.ok)
            assertFalse("its copy left the phone", support.calendarStore().calendarIds().values.contains(href))
            assertFalse(checkNotNull(dao().collection(support.account.name, href)).chosen)
            assertTrue("the real calendar is still on the phone", support.calendarStore().calendarIds().values.any { it.contains("/work/") })
        } finally {
            runCatching { support.raw.json("PUT", taskSync, JsonObject().apply { addProperty("enabled", false) }) }
        }
    }

    private companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
