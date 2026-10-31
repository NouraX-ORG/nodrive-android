/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.accounts.Account
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.owncloud.android.MainApp
import org.nourax.nodrive.agenda.AgendaTestSupport
import okhttp3.OkHttpClient
import org.nourax.nodrive.agenda.LiveRaw
import org.nourax.nodrive.agenda.dav.Dav
import org.nourax.nodrive.agenda.dav.DavXml
import org.nourax.nodrive.agenda.net.AccountAgendaHttp
import org.nourax.nodrive.devicesync.calendar.CalendarProviderStore
import org.nourax.nodrive.devicesync.contacts.ContactsProviderStore
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.DeviceSyncDatabase
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/** One event as the phone shows it (read with the app's own rights, so a deleted row is not in it). */
class EventSnap(val id: Long, val calendarId: Long, val title: String, val dirty: Boolean, val syncId: String?, val startMs: Long)

/** Accounts, the throwaway server, the phone's providers and screenshots for the device sync tests. */
class DeviceSyncTestSupport {
    val agenda = AgendaTestSupport()
    val context: Context get() = agenda.context
    val look: String get() = agenda.look
    lateinit var account: Account
    lateinit var raw: LiveRaw

    /** What the last [listing] was answered (for the message of a failed check). */
    var lastListing: String = ""

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun grantPermissions() {
        for (p in listOf("READ_CALENDAR", "WRITE_CALENDAR", "READ_CONTACTS", "WRITE_CONTACTS", "POST_NOTIFICATIONS")) {
            agenda.backup.shell("pm grant org.nourax.nodrive android.permission.$p")
        }
    }

    /** Ali's account in the app (a throwaway app password), and plain HTTP as Ali for the test data. */
    fun signInAsAli(): Account {
        account = agenda.addServerAccount("ali")
        raw = LiveRaw(checkNotNull(agenda.serverUrl), "ali", checkNotNull(agenda.token("ali")))
        return account
    }

    /** The account a former test method of the same run made (the tests that span a change of the network use it). */
    fun useExistingAccount(): Account {
        account = android.accounts.AccountManager.get(context).getAccountsByType(MainApp.getAccountType(context)).first()
        raw = LiveRaw(checkNotNull(agenda.serverUrl), "ali", checkNotNull(agenda.token("ali")))
        return account
    }

    val accountType: String get() = MainApp.getAccountType(context)

    fun scope() = ProviderScope(account.name, accountType)

    fun calendarStore() = CalendarProviderStore(context.contentResolver, scope())

    fun contactsStore() = ContactsProviderStore(context.contentResolver, scope())

    // ---- the account in the sync's own database -----------------------------------------------------------------------------------

    /** On, the backup counted as done (the tests of the engine start from here), and these collections chosen. */
    fun switchOn(vararg hrefParts: String) {
        DeviceSyncSettings.update(context, account.name, enabled = true, backupDoneUtc = System.currentTimeMillis())
        check(DeviceSyncRunner.discover(context, account.name)) { "the server's calendars and address books were not found" }
        for (c in DeviceSyncDatabase.get(context).dao().collections(account.name)) {
            DeviceSyncSettings.setChosen(context, account.name, c.href, hrefParts.any { c.href.contains(it) })
        }
    }

    fun sync(kinds: Set<String> = setOf("calendar", "contacts"), flags: SyncFlags = SyncFlags()): SyncOutcome =
        DeviceSyncRunner.run(context, account.name, kinds, flags)

    /** CI emulator only: takes everything of ours off the phone and forgets the account's rows, whatever the state. */
    fun cleanUp() {
        runCatching {
            if (::account.isInitialized) {
                val calendars = calendarStore()
                for (id in calendars.calendarIds().keys) calendars.deleteCalendar(id)
                contactsStore().deleteAll()
                DeviceSyncControl.switchOff(context, account.name)
                DeviceSyncAccounts.forget(context, account.name)
            }
        }
    }

    // ---- the phone ------------------------------------------------------------------------------------------------------------------

    fun calendarIdEndingWith(part: String): Long = calendarStore().calendarIds().entries.first { it.value.contains(part) }.key

    fun events(calendarId: Long): List<EventSnap> {
        val out = ArrayList<EventSnap>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DIRTY,
                CalendarContract.Events._SYNC_ID, CalendarContract.Events.DTSTART
            ),
            CalendarContract.Events.CALENDAR_ID + " = ? AND " + CalendarContract.Events.DELETED + " = 0", arrayOf(calendarId.toString()), null
        )?.use {
            while (it.moveToNext()) out += EventSnap(it.getLong(0), it.getLong(1), it.getString(2).orEmpty(), it.getInt(3) == 1, it.getString(4), it.getLong(5))
        }
        return out
    }

    fun event(calendarId: Long, title: String): EventSnap? = events(calendarId).firstOrNull { it.title == title }

    /** An edit as an ordinary app makes it: the row becomes dirty. */
    fun appSetTitle(eventId: Long, title: String) {
        val v = ContentValues().apply { put(CalendarContract.Events.TITLE, title) }
        check(context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), v, null, null) == 1)
    }

    fun appDeleteEvent(eventId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    fun reminders(eventId: Long): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            CalendarContract.Reminders.EVENT_ID + " = ?", arrayOf(eventId.toString()), null
        )?.use { while (it.moveToNext()) out += it.getInt(0) to it.getInt(1) }
        return out
    }

    /** Every contact of our account: raw id to display name. */
    fun contactNames(): Map<Long, String> = contactsStore().rows().associate { it.id to it.contact.displayName }

    // ---- the server -----------------------------------------------------------------------------------------------------------------

    fun icsOf(uid: String, summary: String, start: Instant, end: Instant, vararg extra: String): String =
        (listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//nodrive ci//EN", "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:" + stamp.format(Instant.now()),
            "SUMMARY:$summary", "DTSTART:" + stamp.format(start), "DTEND:" + stamp.format(end)
        ) + extra + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

    fun putServerEvent(calendar: String, name: String, ics: String) {
        val reply = raw.request(
            "PUT", "remote.php/dav/calendars/ali/$calendar/$name", ics, mapOf("Content-Type" to "text/calendar; charset=utf-8")
        )
        check(reply.status in 200..299) { "putting $name answered ${reply.status}" }
    }

    fun serverText(path: String): String = raw.request("GET", path, null, mapOf("Accept" to "*/*")).body

    fun serverStatus(path: String): Int = raw.request("GET", path, null, mapOf("Accept" to "*/*")).status

    fun serverDelete(path: String) {
        raw.request("DELETE", path)
    }

    /**
     * What the server's calendar trash bin holds, with the text of every deleted object. The trash bin does not list its objects with a plain
     * PROPFIND (that answers 501); they are found with a CalDAV `calendar-query` REPORT on `trashbin/objects/`.
     */
    fun trashed(): String {
        val http = AccountAgendaHttp(
            checkNotNull(agenda.serverUrl), "Basic " + Base64.getEncoder().encodeToString("ali:${agenda.token("ali")}".toByteArray()), OkHttpClient()
        )
        val query = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" " +
            "xmlns:nc=\"http://nextcloud.com/ns\"><d:prop><d:getetag/><nc:deleted-at/><c:calendar-data/></d:prop>" +
            "<c:filter><c:comp-filter name=\"VCALENDAR\"/></c:filter></c:calendar-query>").toByteArray()
        val reply = http.send("REPORT", "remote.php/dav/calendars/ali/trashbin/objects/", mapOf("Depth" to "1"), query, "application/xml; charset=utf-8")
        lastListing = "REPORT answered ${reply.status}: " + reply.text().take(LISTING_SAMPLE)
        return if (reply.multistatus) reply.text() else ""
    }

    /** The names in a folder of Ali's Drive or a collection (PROPFIND, depth 1, with the app's own HTTP code); empty when it is not there. */
    fun listing(path: String): List<String> {
        val http = AccountAgendaHttp(
            checkNotNull(agenda.serverUrl), "Basic " + Base64.getEncoder().encodeToString("ali:${agenda.token("ali")}".toByteArray()), OkHttpClient()
        )
        val reply = http.send(
            "PROPFIND", path, mapOf("Depth" to "1"), DavXml.propfindBody(listOf(Dav.NS_DAV to "getetag")), "application/xml; charset=utf-8"
        )
        lastListing = "$path answered ${reply.status}: " + reply.text().take(LISTING_SAMPLE)
        if (!reply.multistatus) return emptyList()
        return DavXml.parseMultistatus(reply.body).responses.map { it.href }.filter { !it.endsWith("/") }
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        FileOutputStream(File(folder, "devicesync-$name-$look.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    /** Counts the lines `BEGIN:VEVENT` of a calendar on the server. */
    fun serverEventCount(calendar: String): Int = Regex("BEGIN:VEVENT").findAll(raw.export(calendar)).count()

    /** A raw contact on the phone that belongs to no account (another source), for the "other accounts stay untouched" check. */
    fun insertLocalContact(name: String): Long {
        val uri = context.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
        })
        val id = ContentUris.parseId(checkNotNull(uri))
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, id)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        })
        return id
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val LISTING_SAMPLE = 600
    }
}
