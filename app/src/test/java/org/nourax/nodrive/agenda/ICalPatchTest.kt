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
import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.edit.CalendarEdit
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.ical.ICalPatch
import org.nourax.nodrive.agenda.ical.ICalText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The patch changes the named properties and keeps every other line byte for byte (plan 2.8). */
class ICalPatchTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun lines(text: String) = text.split("\r\n")

    /** All lines of the text that are not one of the [names] properties (not even in a different place). */
    private fun without(text: String, vararg names: String): List<String> {
        val skip = names.map { it.uppercase() }
        return lines(text).filter { line -> skip.none { line.uppercase().startsWith("$it:") || line.uppercase().startsWith("$it;") } }
    }

    @Test
    fun aTitleChangeKeepsAlarmAttendeeUnknownPropertiesAndTimeZones() {
        val before = sampleEvent()
        val after = CalendarEdit.patch(before, false, mapOf(EditKeys.SUMMARY to "Dentist (moved)"), now)
        assertTrue(after.contains("SUMMARY:Dentist (moved)"))
        // Every other line is still there, in the same order, untouched (the time stamps and the sequence are the only new lines).
        val kept = without(before, "SUMMARY", "DTSTAMP")
        val got = without(after, "SUMMARY", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE")
        assertEquals(kept, got)
        assertTrue(after.contains("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT15M\r\nEND:VALARM"))
        assertTrue(after.contains("X-CUSTOM-THING;FOO=bar:keep me"))
        assertTrue(after.contains("ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test"))
        assertTrue(after.contains("TZID:Europe/Berlin"))
        // the folded description is as it was, fold and all
        assertTrue(after.contains("and on and on and on and on and on and\r\n on until it is folded by the program that saved it") || after.contains("\r\n on until it is folded"))
    }

    @Test
    fun theAlarmDescriptionIsNeverTouchedWhenTheEventDescriptionChanges() {
        val after = CalendarEdit.patch(sampleEvent(), false, mapOf(EditKeys.DESCRIPTION to "New text, with; marks"), now)
        assertTrue(after.contains("DESCRIPTION:New text\\, with\\; marks"))
        assertTrue("the alarm text stays", after.contains("DESCRIPTION:Reminder"))
        assertEquals(1, lines(after).count { it == "DESCRIPTION:Reminder" })
    }

    @Test
    fun stampingBumpsDtstampLastModifiedAndSequence() {
        val after = CalendarEdit.patch(sampleEvent(), false, mapOf(EditKeys.SUMMARY to "x"), now)
        assertTrue(after.contains("DTSTAMP:20261028T120000Z"))
        assertTrue(after.contains("LAST-MODIFIED:20261028T120000Z"))
        assertTrue(after.contains("SEQUENCE:1"))
        val again = CalendarEdit.patch(after, false, mapOf(EditKeys.SUMMARY to "y"), now.plusSeconds(60))
        assertTrue(again.contains("SEQUENCE:2"))
        assertEquals(1, lines(again).count { it.startsWith("DTSTAMP") })
    }

    @Test
    fun aNewTimeIsWrittenInUtcAndTheOldDefinitionIsReplaced() {
        val start = Instant.parse("2026-10-29T09:00:00Z")
        val after = CalendarEdit.patch(
            sampleEvent(), false, mapOf(EditKeys.START to EditKeys.moment(start), EditKeys.END to EditKeys.moment(start.plusSeconds(1800))), now
        )
        assertTrue(after.contains("DTSTART:20261029T090000Z"))
        assertTrue(after.contains("DTEND:20261029T093000Z"))
        assertFalse(after.contains("DTSTART;TZID"))
        // it reads back to the same moment
        val record = CalendarReader.parseObject("work", "/x.ics", "\"e\"", after, false, ZoneId.of("UTC")).single()
        assertEquals(start.toEpochMilli(), record.startMs)
        assertEquals(start.plusSeconds(1800).toEpochMilli(), record.endMs)
    }

    @Test
    fun anAllDayEventEndsOnTheStartOfTheDayAfterItsLastDay() {
        val after = CalendarEdit.patch(
            sampleEvent(), false,
            mapOf(EditKeys.START to EditKeys.day(LocalDate.of(2026, 11, 2)), EditKeys.END to EditKeys.day(LocalDate.of(2026, 11, 3))), now
        )
        assertTrue(after.contains("DTSTART;VALUE=DATE:20261102"))
        assertTrue(after.contains("DTEND;VALUE=DATE:20261104"))
        val record = CalendarReader.parseObject("work", "/x.ics", "\"e\"", after, false, ZoneId.of("UTC")).single()
        assertTrue(record.allDay)
        assertEquals("2026-11-02", record.startDate)
        assertEquals("2026-11-03", record.endDate)
    }

    @Test
    fun durationIsRemovedWhenTheEndIsWritten() {
        val withDuration = sampleEvent().replace("DTEND;TZID=Europe/Berlin:20261028T120000", "DURATION:PT1H")
        val after = CalendarEdit.patch(
            withDuration, false,
            mapOf(EditKeys.START to EditKeys.moment(Instant.parse("2026-10-29T09:00:00Z")), EditKeys.END to EditKeys.moment(Instant.parse("2026-10-29T10:00:00Z"))), now
        )
        assertFalse(after.contains("DURATION"))
        assertTrue(after.contains("DTEND:20261029T100000Z"))
    }

    @Test
    fun anAgentIsWrittenAsAPropertyAndAsACategoryAndOtherCategoriesStay() {
        val change = mapOf(EditKeys.AGENT to "AG-0001", EditKeys.AGENT_TAG to "agent:AG-0001", EditKeys.ALL_TAGS to "agent:AG-0001" + EditKeys.SEP + "agent:AG-0003")
        val after = CalendarEdit.patch(sampleEvent(), false, change, now)
        assertTrue(after.contains("X-NOURAX-AGENT-ID:AG-0001"))
        assertTrue(after.contains("CATEGORIES:health,family,agent:AG-0001"))
        // the record reads the agent back, from either place
        val record = CalendarReader.parseObject("work", "/x.ics", "\"e\"", after, false, ZoneId.of("UTC")).single()
        assertEquals("AG-0001", record.agentId)
        assertTrue("agent:AG-0001" in record.categories)
        assertTrue("health" in record.categories)
    }

    @Test
    fun changingTheAgentTakesTheOldTagOffAndPutsTheNewOneOn() {
        val first = CalendarEdit.patch(
            sampleEvent(), false,
            mapOf(EditKeys.AGENT to "AG-0001", EditKeys.AGENT_TAG to "agent:AG-0001", EditKeys.ALL_TAGS to "agent:AG-0001" + EditKeys.SEP + "agent:AG-0003"), now
        )
        val second = CalendarEdit.patch(
            first, false,
            mapOf(EditKeys.AGENT to "AG-0003", EditKeys.AGENT_TAG to "agent:AG-0003", EditKeys.ALL_TAGS to "agent:AG-0001" + EditKeys.SEP + "agent:AG-0003"), now
        )
        assertTrue(second.contains("X-NOURAX-AGENT-ID:AG-0003"))
        assertFalse(second.contains("agent:AG-0001"))
        assertTrue(second.contains("CATEGORIES:health,family,agent:AG-0003"))
    }

    @Test
    fun selfTakesBothAgentMarksAway() {
        val withAgent = CalendarEdit.patch(
            sampleEvent(), false, mapOf(EditKeys.AGENT to "AG-0001", EditKeys.AGENT_TAG to "agent:AG-0001", EditKeys.ALL_TAGS to "agent:AG-0001"), now
        )
        val self = CalendarEdit.patch(withAgent, false, mapOf(EditKeys.AGENT to EditKeys.SELF, EditKeys.ALL_TAGS to "agent:AG-0001"), now)
        assertFalse(self.contains("X-NOURAX-AGENT-ID"))
        assertFalse(self.contains("agent:AG-0001"))
        assertTrue(self.contains("CATEGORIES:health,family"))
    }

    @Test
    fun aCategoriesLineThatOnlyHeldTheTagIsRemoved() {
        val only = sampleEvent().replace("CATEGORIES:health,family", "CATEGORIES:agent:AG-0001")
        val after = CalendarEdit.patch(only, false, mapOf(EditKeys.AGENT to EditKeys.SELF, EditKeys.ALL_TAGS to "agent:AG-0001"), now)
        assertFalse(after.contains("CATEGORIES"))
    }

    @Test
    fun aTodoIsTickedDoneAndNotDone() {
        val done = CalendarEdit.patch(sampleTodo(), true, mapOf(EditKeys.DONE to "true", CalendarEdit.COMPLETED_AT to now.toEpochMilli().toString()), now)
        assertTrue(done.contains("STATUS:COMPLETED"))
        assertTrue(done.contains("COMPLETED:20261028T120000Z"))
        assertTrue(done.contains("PERCENT-COMPLETE:100"))
        val back = CalendarEdit.patch(done, true, mapOf(EditKeys.DONE to "false"), now)
        assertTrue(back.contains("STATUS:NEEDS-ACTION"))
        assertFalse(back.contains("COMPLETED:"))
        assertFalse(back.contains("PERCENT-COMPLETE"))
        assertTrue(back.contains("X-CUSTOM-THING:keep me"))
        assertTrue(back.contains("DUE;VALUE=DATE:20261030"))
    }

    @Test
    fun aTodoDueDateCanBeChangedOrTakenAway() {
        val moved = CalendarEdit.patch(sampleTodo(), true, mapOf(EditKeys.DUE to EditKeys.day(LocalDate.of(2026, 11, 5))), now)
        assertTrue(moved.contains("DUE;VALUE=DATE:20261105"))
        assertEquals(1, lines(moved).count { it.startsWith("DUE") })
        val none = CalendarEdit.patch(moved, true, mapOf(EditKeys.DUE to ""), now)
        assertFalse(none.contains("DUE"))
    }

    @Test
    fun theLineEndingOfTheSourceIsKept() {
        val lf = sampleEvent().replace("\r\n", "\n")
        val after = CalendarEdit.patch(lf, false, mapOf(EditKeys.SUMMARY to "Changed"), now)
        assertFalse(after.contains("\r"))
        assertTrue(after.endsWith("END:VCALENDAR\n"))
    }

    @Test
    fun longLinesAreFoldedAtSeventyFiveOctetsWithoutCuttingACharacter() {
        val title = "Ünïcödé ".repeat(30)
        val after = CalendarEdit.patch(sampleEvent(), false, mapOf(EditKeys.SUMMARY to title), now)
        for (line in lines(after)) assertTrue("line too long: ${line.toByteArray().size}", line.toByteArray(Charsets.UTF_8).size <= 75)
        val summary = ICalText.parse(after).first { it.type == "VEVENT" }.text("SUMMARY")
        assertEquals(title, summary)
    }

    @Test
    fun aNewEventParsesBackWithEverythingThatWasTyped() {
        val start = Instant.parse("2026-11-03T14:00:00Z")
        val text = CalendarEdit.create(
            false, "uid-123",
            mapOf(
                EditKeys.SUMMARY to "Review, with; marks", EditKeys.DESCRIPTION to "Line one\nline two",
                EditKeys.START to EditKeys.moment(start), EditKeys.END to EditKeys.moment(start.plusSeconds(3600)),
                EditKeys.AGENT to "AG-0001", EditKeys.AGENT_TAG to "agent:AG-0001"
            ),
            now
        )
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nourax//NoAgenda//EN"))
        val record = CalendarReader.parseObject("work", "/uid-123.ics", "\"e\"", text, false, ZoneId.of("UTC")).single()
        assertEquals("uid-123", record.uid)
        assertEquals("Review, with; marks", record.summary)
        assertEquals("Line one\nline two", record.description)
        assertEquals(start.toEpochMilli(), record.startMs)
        assertEquals("AG-0001", record.agentId)
        assertEquals(listOf("agent:AG-0001"), record.categories)
        assertFalse(record.isTodo)
    }

    @Test
    fun aNewTodoParsesBack() {
        val text = CalendarEdit.create(
            true, "uid-9",
            mapOf(EditKeys.SUMMARY to "Call mum", EditKeys.DUE to EditKeys.day(LocalDate.of(2026, 11, 9)), EditKeys.START to EditKeys.day(LocalDate.of(2026, 11, 8))),
            now
        )
        val record = CalendarReader.parseObject("personal", "/uid-9.ics", "\"e\"", text, true, ZoneId.of("UTC")).single()
        assertTrue(record.isTodo)
        assertEquals("2026-11-09", record.dueDate)
        assertEquals("2026-11-08", record.startDate)
        assertFalse(record.completed)
        assertFalse(text.contains("TRANSP"))
    }

    @Test
    fun theTextIsUnchangedWhenNothingIsPatched() {
        val patch = ICalPatch(sampleEvent(), "VEVENT")
        assertEquals(sampleEvent(), patch.build())
        assertTrue(patch.found)
    }

    @Test
    fun aMissingComponentIsReported() {
        assertFalse(ICalPatch(sampleTodo(), "VEVENT").found)
    }
}
