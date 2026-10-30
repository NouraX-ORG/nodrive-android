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
import org.nourax.nodrive.devicesync.calendar.EventDocument
import org.nourax.nodrive.devicesync.calendar.EventMapper
import org.nourax.nodrive.devicesync.calendar.PhoneEvent
import org.nourax.nodrive.devicesync.calendar.PhoneObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Events both ways (plan 4.9): every field, all day, time zones, repeats, an exception, reminders, and what a phone edit keeps. */
class EventMapperTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun ics(vararg body: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN") + body + listOf("END:VCALENDAR")).joinToString("\r\n") + "\r\n"

    private fun event(vararg lines: String): String =
        ics(*(listOf("BEGIN:VEVENT") + lines + listOf("END:VEVENT")).toTypedArray())

    private val tagged = event(
        "UID:ci-agent", "DTSTAMP:20261030T080000Z", "SUMMARY:Writer-Bot review", "DTSTART:20261030T140000Z", "DTEND:20261030T150000Z",
        "X-NOURAX-AGENT-ID:AG-0001", "CATEGORIES:agent:AG-0001,review", "ATTENDEE;CN=Mara;PARTSTAT=ACCEPTED:mailto:mara@example.test",
        "ORGANIZER:mailto:mara@example.test", "X-UNKNOWN;FOO=bar:keep me",
        "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM"
    )

    private fun parsed(text: String) = checkNotNull(EventMapper.parse(text, utc))

    @Test
    fun aTimedEventIsReadWithEveryField() {
        val e = parsed(
            event(
                "UID:u1", "SUMMARY:Dentist", "LOCATION:Main street 1", "DESCRIPTION:Bring the card\\nand the form", "DTSTART:20261031T100000Z",
                "DTEND:20261031T110000Z", "STATUS:CONFIRMED", "TRANSP:TRANSPARENT", "CLASS:PRIVATE"
            )
        ).master
        assertEquals("u1", e.uid)
        assertEquals("Dentist", e.title)
        assertEquals("Main street 1", e.location)
        assertEquals("Bring the card\nand the form", e.description)
        assertEquals(Instant.parse("2026-10-31T10:00:00Z").toEpochMilli(), e.startMs)
        assertEquals(Instant.parse("2026-10-31T11:00:00Z").toEpochMilli(), e.endMs)
        assertEquals("UTC", e.timeZone)
        assertEquals(1, e.status)
        assertEquals(1, e.availability)
        assertEquals(2, e.accessLevel)
        assertFalse(e.allDay)
    }

    @Test
    fun anAllDayEventStartsAtUtcMidnightAndEndsTheNextDay() {
        val e = parsed(event("UID:u2", "SUMMARY:Day off", "DTSTART;VALUE=DATE:20261101", "DTEND;VALUE=DATE:20261102")).master
        assertTrue(e.allDay)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), e.startMs)
        assertEquals(Instant.parse("2026-11-02T00:00:00Z").toEpochMilli(), e.endMs)
        assertEquals("UTC", e.timeZone)
        // no DTEND: one day
        val one = parsed(event("UID:u3", "DTSTART;VALUE=DATE:20261101")).master
        assertEquals(one.startMs + 86_400_000L, one.endMs)
    }

    @Test
    fun aTimeZoneIsKeptAsTheEventsZone() {
        val e = parsed(event("UID:u4", "SUMMARY:Call", "DTSTART;TZID=Europe/Berlin:20261105T100000", "DTEND;TZID=Europe/Berlin:20261105T110000")).master
        assertEquals("Europe/Berlin", e.timeZone)
        assertEquals(Instant.parse("2026-11-05T09:00:00Z").toEpochMilli(), e.startMs)
    }

    @Test
    fun aRepeatWithExceptionsAndADurationIsRead() {
        val e = parsed(
            event("UID:u5", "SUMMARY:Stand-up", "DTSTART:20261027T093000Z", "DURATION:PT15M", "RRULE:FREQ=DAILY;COUNT=10", "EXDATE:20261029T093000Z,20261028T093000Z")
        ).master
        assertTrue(e.recurring)
        assertEquals("FREQ=DAILY;COUNT=10", e.rrule)
        assertEquals(15 * 60 * 1000L, e.endMs - e.startMs)
        assertEquals("20261028T093000Z,20261029T093000Z", e.exdate)
    }

    @Test
    fun aChangedOccurrenceIsAnExceptionOfTheEvent() {
        val text = ics(
            "BEGIN:VEVENT", "UID:u6", "SUMMARY:Weekly", "DTSTART:20261026T090000Z", "DTEND:20261026T093000Z", "RRULE:FREQ=WEEKLY", "END:VEVENT",
            "BEGIN:VEVENT", "UID:u6", "RECURRENCE-ID:20261102T090000Z", "SUMMARY:Weekly (moved)", "DTSTART:20261102T100000Z", "DTEND:20261102T103000Z", "END:VEVENT"
        )
        val p = parsed(text)
        assertEquals("Weekly", p.master.title)
        assertEquals(1, p.exceptions.size)
        assertEquals("Weekly (moved)", p.exceptions[0].title)
        assertEquals(Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(), p.exceptions[0].recurrenceIdMs)
    }

    @Test
    fun remindersBecomeMinutesBeforeTheStart() {
        val e = parsed(
            event(
                "UID:u7", "DTSTART:20261030T140000Z", "DTEND:20261030T150000Z",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-P1D", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:PT0S", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:PT30M", "END:VALARM"
            )
        ).master
        assertEquals(listOf(0, 15, 1440), e.reminders)
    }

    @Test
    fun aTodoIsNeverAnEvent() {
        val todo = ics("BEGIN:VTODO", "UID:t1", "SUMMARY:Write report", "DUE:20261031T100000Z", "END:VTODO")
        assertNull(EventMapper.parse(todo, utc))
    }

    @Test
    fun aPhoneEditOfTheTitleKeepsTheAgentIdCategoriesAttendeesAndUnknownLinesByteForByte() {
        val base = parsed(tagged).master
        val doc = EventDocument(tagged)
        val after = EventDocument.upload(tagged, "ci-agent", PhoneObject(base.copy(title = "Writer-Bot review (moved)"), emptyList()), utc, now)
        assertTrue(after.contains("SUMMARY:Writer-Bot review (moved)"))
        for (kept in listOf(
            "X-NOURAX-AGENT-ID:AG-0001", "CATEGORIES:agent:AG-0001,review", "ATTENDEE;CN=Mara;PARTSTAT=ACCEPTED:mailto:mara@example.test",
            "ORGANIZER:mailto:mara@example.test", "X-UNKNOWN;FOO=bar:keep me", "DTSTART:20261030T140000Z", "DTEND:20261030T150000Z",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT15M\r\nEND:VALARM"
        )) assertTrue("lost: $kept", after.contains(kept))
        assertNotNull(doc.master())
        assertTrue(after.contains("SEQUENCE:1"))
    }

    @Test
    fun anEditWithoutAnyChangeGivesTheSameTextBack() {
        val base = parsed(tagged).master
        val after = EventDocument.upload(tagged, "ci-agent", PhoneObject(base, emptyList()), utc, now)
        assertEquals(tagged, after)
    }

    @Test
    fun aNewTimeIsWrittenAsUtcOrWithItsZone() {
        val base = parsed(tagged).master
        val moved = base.copy(startMs = base.startMs + 3_600_000L, endMs = base.endMs + 3_600_000L)
        val utcText = EventDocument.upload(tagged, "ci-agent", PhoneObject(moved, emptyList()), utc, now)
        assertTrue(utcText.contains("DTSTART:20261030T150000Z"))
        assertTrue(utcText.contains("DTEND:20261030T160000Z"))
        val berlin = EventDocument.upload(tagged, "ci-agent", PhoneObject(moved.copy(timeZone = "Europe/Berlin"), emptyList()), utc, now)
        assertTrue(berlin.contains("DTSTART;TZID=Europe/Berlin:20261030T160000"))
    }

    @Test
    fun remindersAreReplacedOnlyWhenTheyChanged() {
        val base = parsed(tagged).master
        val text = EventDocument.upload(tagged, "ci-agent", PhoneObject(base.copy(reminders = listOf(5, 60)), emptyList()), utc, now)
        assertFalse(text.contains("TRIGGER:-PT15M"))
        assertTrue(text.contains("TRIGGER:-PT5M"))
        assertTrue(text.contains("TRIGGER:-PT60M"))
        assertEquals(listOf(5, 60), parsed(text).master.reminders)
    }

    @Test
    fun aNewEventMadeOnThePhoneIsBuiltFromNothing() {
        val e = PhoneEvent(
            title = "Lunch, with Sara; at noon", location = "Cafe", startMs = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(),
            endMs = Instant.parse("2026-11-03T13:00:00Z").toEpochMilli(), reminders = listOf(10)
        )
        val text = EventDocument.upload(null, "new-uid", PhoneObject(e, emptyList()), utc, now)
        val back = parsed(text).master
        assertEquals("new-uid", back.uid)
        assertEquals("Lunch, with Sara; at noon", back.title)
        assertEquals("Cafe", back.location)
        assertEquals(e.startMs, back.startMs)
        assertEquals(e.endMs, back.endMs)
        assertEquals(listOf(10), back.reminders)
        assertTrue(text.startsWith("BEGIN:VCALENDAR"))
    }

    @Test
    fun aChangeOfOneOccurrenceIsARecurrenceIdBlockAndTheMasterStaysAsItWas() {
        val weekly = ics(
            "BEGIN:VEVENT", "UID:u8", "DTSTAMP:20261001T000000Z", "SUMMARY:Stand-up", "DTSTART:20261026T090000Z", "DTEND:20261026T091500Z",
            "RRULE:FREQ=WEEKLY", "X-NOURAX-AGENT-ID:AG-0002", "END:VEVENT"
        )
        val master = parsed(weekly).master
        val instance = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli()
        val exception = PhoneEvent(
            uid = "u8", title = "Stand-up (moved)", startMs = instance + 3_600_000L, endMs = instance + 4_500_000L,
            recurrenceIdMs = instance
        )
        val text = EventDocument.upload(weekly, "u8", PhoneObject(master, listOf(exception)), utc, now)
        val p = parsed(text)
        assertEquals("Stand-up", p.master.title)
        assertTrue(text.contains("X-NOURAX-AGENT-ID:AG-0002"))
        assertTrue(text.contains("RECURRENCE-ID:20261102T090000Z"))
        assertEquals(1, p.exceptions.size)
        assertEquals("Stand-up (moved)", p.exceptions[0].title)
        assertEquals(instance + 3_600_000L, p.exceptions[0].startMs)
    }

    @Test
    fun aCancelledOccurrenceBecomesAnExdate() {
        val weekly = ics(
            "BEGIN:VEVENT", "UID:u9", "SUMMARY:Stand-up", "DTSTART:20261026T090000Z", "DTEND:20261026T091500Z", "RRULE:FREQ=WEEKLY", "END:VEVENT"
        )
        val master = parsed(weekly).master
        val instance = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli()
        val cancelled = master.copy(startMs = instance, endMs = instance + 900_000L, recurrenceIdMs = instance, status = 2, rrule = "")
        val text = EventDocument.upload(weekly, "u9", PhoneObject(master, listOf(cancelled)), utc, now)
        assertTrue(text, text.contains("EXDATE:20261102T090000Z"))
        assertFalse(text.contains("RECURRENCE-ID"))
    }

    @Test
    fun rebaseKeepsTheServersNewerFieldsAndPutsOnlyThePhonesChangeOnTop() {
        val base = parsed(tagged).master
        val phone = base.copy(title = "Phone title")
        val server = base.copy(location = "Server room")
        val merged = EventMapper.rebase(base, phone, server)
        assertEquals("Phone title", merged.title)
        assertEquals("Server room", merged.location)
    }
}
