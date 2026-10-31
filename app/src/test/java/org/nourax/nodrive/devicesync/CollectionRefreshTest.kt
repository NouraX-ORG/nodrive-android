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
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.CollectionEntity
import java.time.ZoneOffset

/**
 * The list of calendars and address books against a server in memory: NoGantt's copy calendar is read the way NoAgenda reads it and
 * fails closed, a collection that the server no longer lists is dropped or marked, and what was chosen stays chosen.
 */
class CollectionRefreshTest {
    private val account = "ali@drive.test"
    private val home = "/remote.php/dav/calendars/ali/"
    private val server = FakeDavServer()
    private val dao = FakeDao()

    private fun env() = SyncEnv(account, server, CardDavClient(server, "ali"), dao, ZoneOffset.UTC)

    private fun taskSync(enabled: String, uri: String? = null): String {
        val data = "{\"enabled\":$enabled" + (if (uri != null) ",\"calendar_uri\":\"$uri\"" else "") + "}"
        return "{\"ocs\":{\"meta\":{\"status\":\"ok\",\"statuscode\":200,\"message\":\"OK\"},\"data\":$data}}"
    }

    private fun work() = FakeCollectionSpec(home + "work/", "Work")

    private fun copy() = FakeCollectionSpec(home + "gantt-copy/", "Task copies")

    private fun chosen(href: String, name: String = "Work", kind: String = RemoteCollection.CALENDAR) =
        CollectionEntity(account, href, kind, name, "", false, "", true, "tok", 7L, true, 3)

    // ---- the copy calendar is read the way NoAgenda reads it

    @Test
    fun aCopyCalendarThatIsOnIsKnownAndNeverOffered() {
        server.taskSyncStatus = 200
        server.taskSyncBody = taskSync("true", "gantt-copy")
        server.calendarList = listOf(work(), copy())

        val result = CollectionRefresh.run(env())

        assertTrue(result.ok)
        assertTrue(result.calendarsKnown)
        assertEquals(listOf(home + "work/"), dao.collections(account).map { it.href })
        assertEquals("gantt-copy", CardDavClient(server, "ali").ganttCopy().calendarUri)
    }

    @Test
    fun noGanttOnTheServerMeansThereIsNoCopyCalendar() {
        server.taskSyncStatus = 404
        server.calendarList = listOf(work())

        val result = CollectionRefresh.run(env())

        assertTrue(result.calendarsKnown)
        assertEquals(1, dao.collections(account).size)
    }

    @Test
    fun aCopyThatIsOffMeansThereIsNoCopyCalendarEither() {
        server.taskSyncStatus = 200
        server.taskSyncBody = taskSync("false")
        server.calendarList = listOf(work())

        assertTrue(CollectionRefresh.run(env()).calendarsKnown)
    }

    @Test
    fun anythingThatCanNotBeReadFailsClosedNoCalendarIsOfferedOrSynced() {
        server.calendarList = listOf(work(), copy())
        val bad = listOf(
            500 to "", // the server has a problem
            200 to "not json at all",
            200 to taskSync("1", "gantt-copy"), // not the shape of NoGantt 2.4.1: "enabled" is no boolean
            200 to "{\"ocs\":{\"meta\":{},\"data\":{\"something\":true}}}",
            200 to taskSync("true") // it says on, and names no calendar
        )
        for ((status, body) in bad) {
            server.taskSyncStatus = status
            server.taskSyncBody = body
            val result = CollectionRefresh.run(env())
            assertFalse("$status $body", result.calendarsKnown)
            assertTrue("no calendar was offered for $status $body", dao.collections(account).none { it.kind == RemoteCollection.CALENDAR })
        }
    }

    @Test
    fun theAddressBooksAreStillOfferedWhileTheCopyCalendarIsUnknown() {
        server.taskSyncStatus = 500
        server.bookList = listOf(FakeCollectionSpec("/remote.php/dav/addressbooks/users/ali/contacts/", "Contacts"))

        val result = CollectionRefresh.run(env())

        assertTrue(result.ok)
        assertFalse(result.calendarsKnown)
        assertEquals(listOf(RemoteCollection.CONTACTS), dao.collections(account).map { it.kind })
    }

    // ---- the copy calendar that was chosen before

    @Test
    fun aCopyCalendarThatWasChosenBeforeIsMarkedSoItLeavesThePhone() {
        server.taskSyncStatus = 200
        server.taskSyncBody = taskSync("true", "gantt-copy")
        server.calendarList = listOf(work(), copy())
        dao.putCollection(chosen(home + "work/"))
        dao.putCollection(chosen(home + "gantt-copy/", "Task copies"))

        val result = CollectionRefresh.run(env())

        assertTrue(result.calendarsKnown)
        assertEquals(RemoteCollection.OFF_TASK_COPY, checkNotNull(dao.collection(account, home + "gantt-copy/")).offReason)
        assertTrue(checkNotNull(dao.collection(account, home + "gantt-copy/")).chosen)
        assertEquals("only the real calendar is synced", listOf(home + "work/"), CollectionRefresh.live(dao.collections(account)).map { it.href })

        val phone = FakeCalendarStore()
        phone.ensureCalendar(home + "gantt-copy/", "Task copies", 0, false)
        phone.ensureCalendar(home + "work/", "Work", 0, false)
        CollectionRemoval.takeAway(dao, account, checkNotNull(dao.collection(account, home + "gantt-copy/")), phone, FakeContactsStore())
        assertEquals("the copy calendar left the phone, the other stayed", listOf(home + "work/"), phone.calendarIds().values.toList())
        assertFalse(checkNotNull(dao.collection(account, home + "gantt-copy/")).chosen)
    }

    // ---- gone

    @Test
    fun aChosenCollectionThatTheServerNoLongerListsIsMarkedAndAnUnchosenOneIsDropped() {
        server.taskSyncStatus = 404
        server.calendarList = listOf(work())
        dao.putCollection(chosen(home + "work/"))
        dao.putCollection(chosen(home + "deleted/", "Deleted"))
        dao.putCollection(chosen(home + "never/", "Never chosen").with(chosen = false))

        assertTrue(CollectionRefresh.run(env()).ok)

        assertEquals(RemoteCollection.OFF_GONE, checkNotNull(dao.collection(account, home + "deleted/")).offReason)
        assertTrue("it stays chosen until the person lets go of it", checkNotNull(dao.collection(account, home + "deleted/")).chosen)
        assertNull("an unchosen one is simply dropped", dao.collection(account, home + "never/"))
        val kept = checkNotNull(dao.collection(account, home + "work/"))
        assertEquals("what is chosen stays chosen, with its token", "tok", kept.syncToken)
        assertTrue(kept.chosen)
        assertEquals(listOf(home + "work/"), CollectionRefresh.live(dao.collections(account)).map { it.href })
    }

    @Test
    fun aCollectionThatComesBackIsSyncedAgain() {
        server.taskSyncStatus = 404
        server.calendarList = listOf(work())
        dao.putCollection(chosen(home + "work/").with(offReason = RemoteCollection.OFF_GONE))

        assertTrue(CollectionRefresh.run(env()).ok)

        assertEquals("", checkNotNull(dao.collection(account, home + "work/")).offReason)
        assertEquals(1, CollectionRefresh.live(dao.collections(account)).size)
    }

    @Test
    fun aListThatCouldNotBeReadChangesNothing() {
        server.taskSyncStatus = 404
        server.listStatus = 503
        dao.putCollection(chosen(home + "work/"))

        val result = CollectionRefresh.run(env())

        assertFalse(result.ok)
        assertEquals("", checkNotNull(dao.collection(account, home + "work/")).offReason)
    }
}
