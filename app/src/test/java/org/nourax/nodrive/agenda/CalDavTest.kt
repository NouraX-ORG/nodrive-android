/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.dav.CalendarReadOptions
import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.dav.CalendarSnapshot
import org.nourax.nodrive.agenda.dav.Dav
import org.nourax.nodrive.agenda.dav.DavXml
import java.time.Instant
import java.time.LocalDate

class CalDavTest {
    private val aliHome = "remote.php/dav/calendars/ali"

    private fun read(http: FakeHttp, previous: CalendarSnapshot = CalendarSnapshot(), force: Boolean = false, wanted: (org.nourax.nodrive.agenda.dav.CalendarInfo) -> Boolean = { true }): org.nourax.nodrive.agenda.dav.CalendarReadResult {
        val (from, to) = CalendarReader.syncWindow(TODAY)
        return CalendarReader.read(http, CalendarReadOptions("ali", from, to, UTC, NOW, forceFull = force, wanted = wanted), previous)
    }

    private fun aliHttp(): FakeHttp {
        val http = FakeHttp()
        routeScenario(http, "ali")
        return http
    }

    @Test
    fun propfindBodyNamesEveryProperty() {
        val body = String(DavXml.propfindBody(CalendarReader.calendarProps), Charsets.UTF_8)
        for (name in listOf("resourcetype", "supported-calendar-component-set", "sync-token", "getctag", "owner-principal", "schedule-calendar-transp")) {
            assertTrue(name, body.contains(name))
        }
        // it is well-formed XML
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        assertEquals("propfind", factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(body.toByteArray())).documentElement.localName)
    }

    @Test
    fun eventQueryIsExpandedAndTimeRanged() {
        val body = String(DavXml.eventQueryBody(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2027-10-01T00:00:00Z")), Charsets.UTF_8)
        assertTrue(body.contains("expand"))
        assertTrue(body.contains("start=\"20260901T000000Z\""))
        assertTrue(body.contains("end=\"20271001T000000Z\""))
        assertTrue(body.contains("VEVENT"))
        assertFalse(body.contains("VTODO"))
    }

    @Test
    fun todoQueryIsNeverExpanded() {
        val body = String(DavXml.todoQueryBody(), Charsets.UTF_8)
        assertTrue(body.contains("VTODO"))
        assertTrue(body.contains("COMPLETED"))
        assertTrue(body.contains("is-not-defined"))
        assertFalse("the server drops every VTODO on expand", body.contains("expand"))
    }

    @Test
    fun parsesAMultistatus() {
        val ms = DavXml.parseMultistatus(Fx.bytes("caldav/home-ali.xml"))
        assertTrue(ms.valid)
        val personal = ms.responses.first { it.href == "/remote.php/dav/calendars/ali/personal/" }
        assertEquals("Personal", personal.prop(Dav.NS_DAV, "displayname").textValue())
        assertEquals(listOf("VEVENT", "VTODO"), personal.prop(Dav.NS_CALDAV, "supported-calendar-component-set").compNames)
        assertTrue(personal.prop(Dav.NS_DAV, "resourcetype").has("calendar"))
        assertEquals("http://sabre.io/ns/sync/t-personal-1", personal.prop(Dav.NS_DAV, "sync-token").textValue())
    }

    @Test
    fun aRemovedHrefHasAStatusOfItsOwn() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/remote.php/dav/calendars/ali/work/gone.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
            <d:sync-token>http://sabre.io/ns/sync/9</d:sync-token></d:multistatus>"""
        val ms = DavXml.parseMultistatus(xml.toByteArray())
        assertTrue(ms.valid)
        assertEquals(1, ms.responses.size)
        assertEquals(404, ms.responses.first().status)
        assertEquals("http://sabre.io/ns/sync/9", ms.syncToken)
    }

    @Test
    fun anErrorBodyIsNotAMultistatus() {
        val xml = """<?xml version="1.0"?><d:error xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns"><d:valid-sync-token/></d:error>"""
        val ms = DavXml.parseMultistatus(xml.toByteArray())
        assertFalse(ms.valid)
        assertEquals(listOf("valid-sync-token"), ms.errorConditions)
        assertFalse(DavXml.parseMultistatus("not xml at all".toByteArray()).valid)
    }

    @Test
    fun aDoctypeIsRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>"""
        assertFalse(DavXml.parseMultistatus(xml.toByteArray()).valid)
    }

    @Test
    fun propertiesAnsweredWith404AreMissing() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:response><d:href>/x/</d:href>
            <d:propstat><d:prop><d:displayname>X</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><oc:read-only/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response></d:multistatus>"""
        val response = DavXml.parseMultistatus(xml.toByteArray()).responses.first()
        assertEquals(1, response.props.size)
        assertFalse(response.props.containsKey(Dav.key(Dav.NS_OC, "read-only")))
    }

    @Test
    fun classifiesTheCalendarsOfTheScenario() {
        val snapshot = read(aliHttp()).snapshot
        val byUri = snapshot.calendars.associateBy { it.uri }
        assertTrue(byUri.getValue("personal").mine)
        assertTrue(byUri.getValue("personal").hasEvents && byUri.getValue("personal").hasTodos)
        assertFalse(byUri.getValue("personal").readOnly)
        assertEquals("#795AAF", byUri.getValue("personal").color) // the alpha part is dropped
        assertEquals("#2563EB", byUri.getValue("work").color) // the server's default blue is shown as cobalt
        assertFalse(byUri.getValue("mara-team_shared_by_mara").mine)
        assertEquals("Mara", byUri.getValue("mara-team_shared_by_mara").ownerName)
        assertTrue(byUri.getValue("holidays").subscribed)
        assertTrue(byUri.getValue("holidays").readOnly)
        assertTrue(byUri.getValue("contact_birthdays").birthdays)
        assertTrue(byUri.getValue("app-generated--deck--board-10").deckBoard)
        // the inbox, the trash bin and the calendar home itself are not calendars
        assertFalse(byUri.containsKey("inbox"))
        assertFalse(byUri.containsKey("trashbin"))
        assertFalse(byUri.containsKey("ali"))
    }

    @Test
    fun theWindowIsPreviousMonthToTwelveMonthsAhead() {
        val (from, to) = CalendarReader.syncWindow(LocalDate.of(2026, 10, 28))
        assertEquals(LocalDate.of(2026, 9, 1), from)
        assertEquals(LocalDate.of(2027, 10, 31), to)
    }

    @Test
    fun readsTheWholeScenarioAndAsksForTodosOnlyWhereTheyBelong() {
        val http = aliHttp()
        val result = read(http)
        assertTrue(result.ok)
        val snapshot = result.snapshot
        assertTrue(snapshot.loaded)
        assertEquals("Ali", snapshot.displayName)
        assertTrue("ali@example.test" in snapshot.addresses)
        assertEquals("personal", snapshot.defaultCalendarUri)
        assertEquals(7, snapshot.calendars.size)
        // the board's own calendar is never read
        assertEquals(0, http.count("REPORT", "$aliHome/app-generated--deck--board-10/"))
        for (request in http.requests.filter { it.method == "REPORT" }) {
            if (request.body.contains("VTODO")) assertFalse(request.body.contains("expand")) else assertTrue(request.body.contains("expand"))
        }
        assertEquals(2, http.count("REPORT", "$aliHome/personal/"))
        assertEquals(1, http.count("REPORT", "$aliHome/work/"))
        assertEquals(6, snapshot.records.count { it.isTodo })
        assertTrue(snapshot.failedCalendars.isEmpty())
        assertTrue("personal" in snapshot.readUris)
        assertFalse("app-generated--deck--board-10" in snapshot.readUris)
    }

    @Test
    fun anUnchangedSyncTokenReusesTheCachedObjects() {
        val http = aliHttp()
        val first = read(http)
        val reportsAfterFirst = http.requests.size
        val second = read(http, first.snapshot)
        assertTrue(second.ok)
        // only the principal and the calendar list are asked again
        assertEquals(2, http.requests.size - reportsAfterFirst)
        assertEquals(first.snapshot.records.size, second.snapshot.records.size)
        // a manual full read asks everything again
        read(http, first.snapshot, force = true)
        assertTrue(http.requests.size - reportsAfterFirst > 2)
    }

    @Test
    fun aCalendarThatWasHiddenIsReadWhenItIsShownAgain() {
        val http = aliHttp()
        val hiddenWork = read(http, wanted = { it.uri != "work" })
        assertTrue(hiddenWork.snapshot.records.none { it.calendarUri == "work" })
        assertFalse("work" in hiddenWork.snapshot.readUris)
        // same tokens as before, but "work" was never read: it must not be taken as unchanged
        val shown = read(http, hiddenWork.snapshot)
        assertTrue(shown.snapshot.records.any { it.calendarUri == "work" })
        assertTrue("work" in shown.snapshot.readUris)
    }

    @Test
    fun aFailingCalendarKeepsItsOldObjects() {
        val http = aliHttp()
        val first = read(http)
        val workBefore = first.snapshot.records.count { it.calendarUri == "work" }
        assertTrue(workBefore > 0)
        // a new token for "work" makes it be read again, and that read fails
        val changed = Fx.text("caldav/home-ali.xml").replace("t-work-1", "t-work-2")
        http.route("PROPFIND", "$aliHome/", 207, changed)
        http.route("REPORT", "$aliHome/work/", 500, "", "VEVENT")
        val second = read(http, first.snapshot)
        assertTrue(second.ok)
        assertTrue("work" in second.snapshot.failedCalendars)
        assertEquals(workBefore, second.snapshot.records.count { it.calendarUri == "work" })
        // the other calendars are unaffected
        assertEquals(first.snapshot.records.count { it.calendarUri == "personal" }, second.snapshot.records.count { it.calendarUri == "personal" })
    }

    @Test
    fun aCalendarWhereOnlyTheTodoQueryFailsHasNoDuplicates() {
        val http = aliHttp()
        val first = read(http)
        val eventsBefore = first.snapshot.records.count { it.calendarUri == "personal" && !it.isTodo }
        val todosBefore = first.snapshot.records.count { it.calendarUri == "personal" && it.isTodo }
        val changed = Fx.text("caldav/home-ali.xml").replace("t-personal-1", "t-personal-2")
        http.route("PROPFIND", "$aliHome/", 207, changed)
        http.route("REPORT", "$aliHome/personal/", 500, "", "VTODO")
        val next = read(http, first.snapshot)
        assertEquals(listOf("personal"), next.snapshot.failedCalendars)
        assertEquals(eventsBefore, next.snapshot.records.count { it.calendarUri == "personal" && !it.isTodo })
        assertEquals(todosBefore, next.snapshot.records.count { it.calendarUri == "personal" && it.isTodo })
        assertEquals(first.snapshot.records.size, next.snapshot.records.size)
    }

    @Test
    fun aFailingPrincipalFailsTheWholeReadAndKeepsThePrevious() {
        val http = aliHttp()
        val before = CalendarSnapshot(displayName = "Before", loaded = true)
        http.offline = true
        val result = read(http, before)
        assertFalse(result.ok)
        assertTrue(result.networkError)
        assertEquals("Before", result.snapshot.displayName)
    }

    @Test
    fun theSnapshotSurvivesItsCacheForm() {
        val snapshot = read(aliHttp()).snapshot
        val gson = com.google.gson.GsonBuilder().serializeNulls().create()
        val copy = gson.fromJson(gson.toJson(snapshot), CalendarSnapshot::class.java)
        assertEquals(snapshot, copy)
        assertNotNull(copy.records.first().summary)
    }

    @Test
    fun anEventParsesItsTimesAndPeople() {
        val snapshot = read(aliHttp()).snapshot
        val call = snapshot.records.first { it.href.endsWith("call.ics") }
        assertEquals("Client call", call.summary)
        assertEquals(Instant.parse("2026-10-28T10:00:00Z").toEpochMilli(), call.startMs)
        assertEquals("work", call.calendarUri)
        val invite = snapshot.records.first { it.calendarUri == "work" && it.href.endsWith("invite.ics") }
        assertTrue(invite.organizer.isNotEmpty())
        assertTrue(invite.attendees.isNotEmpty())
    }
}
