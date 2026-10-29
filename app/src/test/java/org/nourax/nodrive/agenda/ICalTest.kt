/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.ical.ICalText
import org.nourax.nodrive.agenda.ical.ICalTime
import org.nourax.nodrive.agenda.ical.ZoneResolver
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ICalTest {
    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:u1\r\nSUMMARY:Lunch\\, then a walk\\; maybe\r\n" +
        "DESCRIPTION:Line one\\nLine two with a very long text that is folded\r\n  here\r\n" +
        "CATEGORIES:work,agent:AG-0001\r\nCATEGORIES:Other\\,name\r\nATTENDEE;PARTSTAT=ACCEPTED;CN=\"Ali, A\":mailto:ali@example.test\r\n" +
        "BEGIN:VALARM\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\nBEGIN:VTIMEZONE\r\nTZID:Custom\r\nEND:VTIMEZONE\r\nEND:VCALENDAR\r\n"

    @Test
    fun unfoldsAndUnescapes() {
        val components = ICalText.parse(ics)
        assertEquals(listOf("VEVENT", "VTIMEZONE"), components.map { it.type })
        val event = components.first()
        assertEquals("Lunch, then a walk; maybe", event.text("SUMMARY"))
        assertEquals("Line one\nLine two with a very long text that is folded here", event.text("DESCRIPTION"))
        assertEquals(listOf("VALARM"), event.children.map { it.type })
    }

    @Test
    fun readsParametersAndCategories() {
        val event = ICalText.parse(ics).first()
        val attendee = event.first("ATTENDEE")!!
        assertEquals("ACCEPTED", attendee.param("partstat"))
        assertEquals("Ali, A", attendee.param("CN"))
        assertEquals("mailto:ali@example.test", attendee.raw)
        // CATEGORIES as a list and as repeated properties; an escaped comma stays inside one value
        val categories = event.all("CATEGORIES").flatMap { it.textList() }
        assertEquals(listOf("work", "agent:AG-0001", "Other,name"), categories)
    }

    @Test
    fun readsDatesAndTimes() {
        val utc = ICalTime.parseValue("20261028T100000Z", "", false, UTC)
        assertEquals(Instant.parse("2026-10-28T10:00:00Z"), utc.utc)
        val london = ICalTime.parseValue("20260715T100000", "Europe/London", false, UTC)
        assertEquals(Instant.parse("2026-07-15T09:00:00Z"), london.utc) // summer time
        val newYork = ICalTime.parseValue("20261228T100000", "America/New_York", false, UTC)
        assertEquals(Instant.parse("2026-12-28T15:00:00Z"), newYork.utc)
        val windows = ICalTime.parseValue("20261028T100000", "W. Europe Standard Time", false, UTC)
        assertEquals(Instant.parse("2026-10-28T09:00:00Z"), windows.utc)
        assertFalse(windows.approximated)
        val floating = ICalTime.parseValue("20261028T100000", "", false, ZoneId.of("Asia/Karachi"))
        assertTrue(floating.floating)
        assertEquals(Instant.parse("2026-10-28T05:00:00Z"), floating.utc)
        val date = ICalTime.parseValue("20261030", "", true, UTC)
        assertTrue(date.isDate)
        assertEquals(LocalDate.of(2026, 10, 30), date.date)
    }

    @Test
    fun anUnknownTimeZoneIsFlaggedNotGuessed() {
        val resolver = ZoneResolver { null }
        val t = ICalTime.parseValue("20261028T100000", "Mars/Olympus", false, UTC, resolver)
        assertTrue(t.valid)
        assertTrue(t.approximated)
    }

    @Test
    fun badValuesAreNotTimes() {
        assertFalse(ICalTime.parseValue("", "", false, UTC).valid)
        assertFalse(ICalTime.parseValue("20261340", "", true, UTC).valid)
        assertFalse(ICalTime.parseValue("20261028T256100", "", false, UTC).valid)
    }

    @Test
    fun readsDurations() {
        assertEquals(3600L, ICalTime.parseDurationSeconds("PT1H"))
        assertEquals(86400L * 2 + 3600 * 3 + 60 * 5, ICalTime.parseDurationSeconds("P2DT3H5M"))
        assertEquals(7L * 86400, ICalTime.parseDurationSeconds("P1W"))
        assertEquals(-900L, ICalTime.parseDurationSeconds("-PT15M"))
        assertEquals(-1L, ICalTime.parseDurationSeconds("nonsense"))
    }
}
