/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.devicesync.calendar.CalendarSyncEngine
import org.nourax.nodrive.devicesync.calendar.ParsedObject
import org.nourax.nodrive.devicesync.calendar.PhoneEvent
import org.nourax.nodrive.devicesync.dav.CardDavClient
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.CollectionEntity
import java.time.Instant
import java.time.ZoneOffset

/**
 * The calendar engine against a server in memory and a phone calendar in memory: the paths that protect the person's data (plan 4.5,
 * 4.6). The first sync only adds, an edit beats a delete in both directions, `SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS` puts a deleted
 * event back, the delete guard holds deletes back, a read-only copy is put back, and a phone copy that was made again starts over.
 */
class CalendarEngineTest {
    private val account = "ali@drive.test"
    private val work = "/remote.php/dav/calendars/ali/work/"
    private val server = FakeDavServer()
    private val dao = FakeDao()
    private val store = FakeCalendarStore()
    private var flags = SyncFlags()

    private fun env() = SyncEnv(account, server, CardDavClient(server, "ali"), dao, ZoneOffset.UTC, { Instant.parse("2026-11-01T10:00:00Z") }, flags)

    private fun choose(readOnly: Boolean = false) {
        dao.putCollection(CollectionEntity(account, work, RemoteCollection.CALENDAR, "Work", "#2563EB", readOnly, "", true, "", 0L, false, 0))
    }

    private fun sync(): SyncOutcome {
        if (dao.collection(account, work) == null) choose()
        return CalendarSyncEngine(env(), store).sync(checkNotNull(dao.collection(account, work)))
    }

    private fun ics(uid: String, title: String, hour: Int = 10, vararg extra: String): String =
        (
            listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN", "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261030T100000Z",
                "SUMMARY:$title", "DTSTART:20261105T%02d0000Z".format(hour), "DTEND:20261105T%02d0000Z".format(hour + 1)
            ) + extra + listOf("END:VEVENT", "END:VCALENDAR")
            ).joinToString("\r\n") + "\r\n"

    private fun putOnServer(name: String, uid: String, title: String, vararg extra: String) = server.serverPut(work + name, ics(uid, title, 10, *extra))

    private fun phoneEvent(uid: String, title: String) = PhoneEvent(uid = uid, title = title, startMs = START_MS, endMs = START_MS + HOUR_MS)

    // ---- the first sync only adds

    @Test
    fun theFirstSyncOnlyAddsAndNothingIsDeletedOrOverwritten() {
        putOnServer("a.ics", "ua", "Server event")
        val cal = store.ensureCalendar(work, "Work", 0, false)
        store.appInsert(cal, phoneEvent("up1", "Phone only"))
        // a row with an address that the server does not have (it was deleted there), and a row the person deleted before the first sync
        val oldId = store.insertObject(cal, work + "old.ics", "\"v1\"", ParsedObject(phoneEvent("uold", "Old address"), emptyList()))
        store.appEdit(oldId) { it }
        val deletedId = store.insertObject(cal, work + "was.ics", "\"v1\"", ParsedObject(phoneEvent("uwas", "Deleted before"), emptyList()))
        store.appDelete(deletedId)
        val rowsBefore = store.rowList.size

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("nothing is deleted on the server", server.deletes().isEmpty())
        assertTrue("the server's event is on the phone", store.byTitle("Server event") != null)
        assertTrue("the phone's event went to the server", server.hrefs(work).any { server.text(it)!!.contains("SUMMARY:Phone only") })
        assertTrue("an event with an old address is added again, not dropped", server.text(work + "old.ics")!!.contains("SUMMARY:Old address"))
        assertTrue("a delete waits for the first sync to be over: the row is still there", store.rowList.any { it.id == deletedId && it.deleted })
        assertTrue("no phone row was lost: ${store.rowList.size} of $rowsBefore", store.rowList.size >= rowsBefore)
        assertTrue("the server lost nothing", server.has(work + "a.ics"))
    }

    // ---- an edit beats a delete, in both directions

    @Test
    fun aPhoneEditAgainstAServerDeleteUploadsTheEventAgain() {
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(sync().ok)
        val row = checkNotNull(store.byTitle("Meeting"))
        store.appEdit(row.id) { it.copy(title = "Meeting (phone)") }
        server.serverDelete(work + "a.ics")

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("the edit wins: the event is on the server again at its old address", server.has(work + "a.ics"))
        assertTrue(server.text(work + "a.ics")!!.contains("SUMMARY:Meeting (phone)"))
        assertTrue("it was made with If-None-Match: *", server.requests.last { it.method == "PUT" }.headers["If-None-Match"] == "*")
        assertTrue("no delete was sent", server.deletes().isEmpty())
        assertFalse("the row is clean", checkNotNull(store.byTitle("Meeting (phone)")).dirty)
    }

    @Test
    fun aPhoneDeleteAgainstAServerEditKeepsTheServerVersion() {
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(sync().ok)
        store.appDelete(checkNotNull(store.byTitle("Meeting")).id)
        putOnServer("a.ics", "ua", "Meeting (server)")

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("the edit wins: the event is back on the phone with the server's title", store.byTitle("Meeting (server)") != null)
        assertTrue("nothing is deleted on the phone for good", store.rowList.none { it.deleted })
        assertTrue("no delete was sent", server.deletes().isEmpty())
        assertTrue(server.has(work + "a.ics"))
    }

    // ---- SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS

    @Test
    fun discardingTheLocalDeletionsPutsTheEventsBackAndSendsNoDelete() {
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(sync().ok)
        store.appDelete(checkNotNull(store.byTitle("Meeting")).id)
        flags = SyncFlags(discardLocalDeletions = true)

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("the event is back", store.byTitle("Meeting") != null)
        assertTrue(store.rowList.none { it.deleted })
        assertTrue("no delete was sent", server.deletes().isEmpty())
        assertTrue(server.has(work + "a.ics"))
    }

    @Test
    fun anOrdinaryPhoneDeleteIsSentWithIfMatch() {
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(sync().ok)
        val etag = server.etagOf(work + "a.ics")
        store.appDelete(checkNotNull(store.byTitle("Meeting")).id)

        assertTrue(sync().ok)

        val delete = server.deletes().single()
        assertEquals(etag, delete.headers["If-Match"])
        assertFalse("the server object is gone (the real server keeps it in its trash bin)", server.has(work + "a.ics"))
        assertTrue("the phone row is gone", store.rowList.isEmpty())
    }

    // ---- the delete guard

    @Test
    fun twentyFiveDeletesOfThirtyAreHeldBackAndCountedUntilThePersonSaysGoAhead() {
        for (i in 0 until 30) putOnServer("e$i.ics", "u$i", "Event $i")
        assertTrue(sync().ok)
        for (i in 0 until 25) store.appDelete(checkNotNull(store.byTitle("Event $i")).id)

        val held = sync()

        assertTrue(held.tooManyDeletions)
        assertEquals("the number that Android's screen shows", 25, held.heldBack)
        assertEquals(0, held.removed)
        assertTrue("nothing was deleted", server.deletes().isEmpty())
        assertEquals(30, server.count(work))

        flags = SyncFlags(overrideTooManyDeletions = true)
        val go = sync()

        assertTrue(go.message, go.ok)
        assertEquals(25, server.deletes().size)
        assertEquals(5, server.count(work))
    }

    // ---- a read-only copy

    @Test
    fun aChangeInAReadOnlyCopyIsPutBackAndKeptInNotSaved() {
        choose(readOnly = true)
        putOnServer("a.ics", "ua", "Shared meeting")
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byTitle("Shared meeting")).id) { it.copy(title = "Shared meeting (phone)") }
        assertEquals(
            "a change that can never be sent does not wait", 0,
            WaitingCount.of(store, FakeContactsStore(), dao.collections(account))
        )

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertNull(store.byTitle("Shared meeting (phone)"))
        val back = checkNotNull(store.byTitle("Shared meeting"))
        assertFalse("the row is clean again", back.dirty)
        assertTrue("nothing was sent", server.writes().isEmpty())
        assertEquals("the phone's edit is not lost silently", 1, dao.notSaved(account).size)
        assertTrue(dao.notSaved(account).single().phoneText.contains("SUMMARY:Shared meeting (phone)"))
    }

    // ---- a phone copy that was made again

    @Test
    fun aCalendarThatThePhoneMadeAgainStartsOverAndTheEventsComeBack() {
        putOnServer("a.ics", "ua", "First")
        putOnServer("b.ics", "ub", "Second")
        assertTrue(sync().ok)
        val oldId = checkNotNull(dao.collection(account, work)).androidId
        assertTrue(oldId > 0)
        store.clearStorage() // the Calendar Storage was cleared, or a sync made the calendar again after Stop

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertEquals("both events are on the phone again", 2, outcome.added)
        assertEquals(2, store.live().size)
        assertTrue("nothing was written to the server", server.writes().isEmpty())
        val col = checkNotNull(dao.collection(account, work))
        assertTrue(col.androidId != oldId)
        assertTrue(col.firstSyncDone)
        // and the next sync is quiet
        val again = sync()
        assertTrue(again.ok)
        assertEquals(0, again.added)
    }

    // ---- the phone says no

    @Test
    fun aRevokedPermissionIsToldApartFromMissingStorage() {
        store.ensureFails = SecurityException("no")
        assertEquals("no_permission", sync().message)
        store.ensureFails = IllegalStateException("no storage")
        assertEquals("no_calendar_storage", sync().message)
        store.ensureFails = null
        assertTrue(sync().ok)
    }

    // ---- guests that were added on the phone

    @Test
    fun theGuestsOfTheLastServerVersionAreHandedToTheStoreSoThatPhoneGuestsSurvive() {
        putOnServer("a.ics", "ua", "Meeting", "ATTENDEE;CN=Bob:mailto:Bob@Example.test")
        assertTrue(sync().ok)
        putOnServer("a.ics", "ua", "Meeting (server)", "ATTENDEE;CN=Bob:mailto:Bob@Example.test")

        assertTrue(sync().ok)

        assertEquals(setOf("bob@example.test"), store.lastPreviousGuests)
        assertNotNull(store.byTitle("Meeting (server)"))
    }

    // ---- a phone that can not be read

    @Test
    fun aContactsStoreThatCanNotBeReadIsNotNothingWaitingAndStopIsRefusedAndTheEditStays() {
        server.calendarList = listOf(FakeCollectionSpec(work, "Work"))
        val books = "/remote.php/dav/addressbooks/users/ali/contacts/"
        server.bookList = listOf(FakeCollectionSpec(books, "Contacts"))
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(CollectionRefresh.run(env()).ok)
        dao.putCollection(checkNotNull(dao.collection(account, work)).with(chosen = true))
        dao.putCollection(checkNotNull(dao.collection(account, books)).with(chosen = true))
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byTitle("Meeting")).id) { it.copy(title = "Meeting (phone)") }
        val contacts = FakeContactsStore()
        assertEquals("readable: the edit waits", 1, WaitingCount.read(store, contacts, dao.collections(account)))

        contacts.rowsFail = SecurityException("the contacts permission was taken away")
        assertNull("not readable: unknown, not 0", WaitingCount.read(store, contacts, dao.collections(account)))

        // the final sync of Stop fails (offline) and reports nothing waiting; the unreadable phone is a refusal of its own
        val refusal = checkNotNull(StopRule.refusal(SyncOutcome.failed("offline"), null, 0))
        assertTrue(refusal.unreadable)
        assertNull("a readable phone with nothing waiting is not refused", StopRule.refusal(SyncOutcome.failed("offline"), 0, 0))
        assertEquals("the edit is still on the phone", "Meeting (phone)", checkNotNull(store.byTitle("Meeting (phone)")).event.title)
    }

    // ---- a calendar that the server no longer has

    @Test
    fun aWaitingChangeInACalendarThatTheServerDeletedDoesNotHoldAnythingUp() {
        server.calendarList = listOf(FakeCollectionSpec(work, "Work"))
        server.bookList = emptyList()
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(CollectionRefresh.run(env()).ok)
        dao.putCollection(checkNotNull(dao.collection(account, work)).with(chosen = true))
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byTitle("Meeting")).id) { it.copy(title = "Meeting (phone)") }
        assertEquals(1, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))

        server.calendarList = emptyList() // the calendar was deleted on the web
        server.serverDelete(work + "a.ics")
        assertTrue(CollectionRefresh.run(env()).ok)

        val gone = checkNotNull(dao.collection(account, work))
        assertEquals(RemoteCollection.OFF_GONE, gone.offReason)
        assertTrue("it stays chosen until the person lets go of it", gone.chosen)
        assertEquals("it can not be sent, so it does not wait", 0, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))
        assertTrue("it is not synced any more", CollectionRefresh.live(dao.collections(account)).isEmpty())
        // but taking it away would lose the edit: the Stop question and the untick question say so
        assertEquals("the lost edit is counted", 1, WaitingCount.lost(store, FakeContactsStore(), dao.collections(account)))
        assertEquals(1, WaitingCount.lost(store, FakeContactsStore(), dao.collections(account), setOf(work)))
        assertEquals(0, WaitingCount.lost(store, FakeContactsStore(), dao.collections(account), setOf("/other/")))

        CollectionRemoval.takeAway(dao, account, gone, store, FakeContactsStore())
        assertTrue("its phone copy is gone", store.calendars.isEmpty())
        assertNull("and it is forgotten for good", dao.collection(account, work))
    }

    // ---- a conflict and Stop syncing

    @Test
    fun aConflictKeepsAnEntryInNotSavedAndStopIsRefusedUntilThePersonDealtWithIt() {
        putOnServer("a.ics", "ua", "Meeting")
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byTitle("Meeting")).id) { it.copy(title = "Meeting (phone)") }
        putOnServer("a.ics", "ua", "Meeting (server)")

        val outcome = sync()

        assertTrue("a conflict is not a failed sync", outcome.ok)
        assertEquals(1, outcome.notSaved)
        assertEquals("the phone is clean again, nothing waits", 0, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))
        // this is what Stop looks at: the final sync's outcome, the waiting count and the entries of "Not saved"
        val refusal = checkNotNull(StopRule.refusal(outcome, 0, dao.notSaved(account).size))
        assertEquals(1, refusal.notSaved)
        assertNotNull("still refused on the next Stop, the final sync has nothing new", StopRule.refusal(SyncOutcome.EMPTY, 0, dao.notSaved(account).size))
        // the person decides ("Keep the NoDrive version"): Stop may go on
        dao.deleteNotSaved(dao.notSaved(account).single().id)
        assertNull(StopRule.refusal(SyncOutcome.EMPTY, 0, dao.notSaved(account).size))
    }

    // ---- a changed occurrence

    @Test
    fun aChangedOccurrenceAloneCountsAsOneWaitingChangeOfItsEvent() {
        choose()
        val cal = store.ensureCalendar(work, "Work", 0, false)
        store.insertObject(cal, work + "r.ics", "\"v1\"", ParsedObject(phoneEvent("ur", "Weekly"), listOf(phoneEvent("ur", "Weekly moved"))))
        assertEquals(0, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))

        store.appEdit(store.rowList.first { it.isException }.id) { it }

        assertEquals("the master is clean, but its occurrence was changed", 1, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))
        store.appEdit(store.rowList.first { !it.isException }.id) { it }
        assertEquals("the event counts once", 1, WaitingCount.of(store, FakeContactsStore(), dao.collections(account)))
    }

    private companion object {
        const val START_MS = 1_793_872_800_000L
        const val HOUR_MS = 3_600_000L
    }
}
