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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CalendarEdit
import org.nourax.nodrive.agenda.edit.EditBlock
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EditRules
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.NewItemDefaults
import org.nourax.nodrive.agenda.edit.TodoForm
import org.nourax.nodrive.agenda.gantt.GanttMapper
import org.nourax.nodrive.agenda.ical.ICalPatch
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.Conflicts
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WritableCalendar
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The second round of the review of "NoAgenda editing and conflicts": a to-do's dates stay valid for the server, a description that
 * was cut is never written back, "+" offers only the person's own calendars, and the clash and load rules of design 7.
 */
class EditRound2Test {
    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun todo(vararg lines: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN", "BEGIN:VTODO", "UID:t1", "DTSTAMP:20261001T080000Z", "SUMMARY:t") + lines +
            listOf("END:VTODO", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

    private fun patch(text: String, vararg change: Pair<String, String>, zone: ZoneId = UTC): String =
        CalendarEdit.patch(text, true, change.toMap(), now, zone)

    private fun lineOf(text: String, name: String): String = text.split("\r\n").firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }.orEmpty()

    // What sabre/vobject 4.5 VTodo::validate checks: DTSTART and DUE are both a day or both a day and time, and DUE is not before DTSTART.
    private fun assertValidForTheServer(text: String) {
        val start = lineOf(text, "DTSTART")
        val due = lineOf(text, "DUE")
        if (start.isEmpty() || due.isEmpty()) return
        fun timed(line: String) = line.substringAfter(':').contains('T')
        fun isDate(line: String) = line.contains("VALUE=DATE") && !line.contains("VALUE=DATE-TIME")
        assertEquals("mixed value types: $start / $due", isDate(start) || !timed(start), isDate(due) || !timed(due))
        assertTrue("DUE before DTSTART: $start / $due", due.substringAfter(':') >= start.substringAfter(':'))
    }

    private fun d(text: String) = EditKeys.day(LocalDate.parse(text))

    // ---- finding 3: to-do dates -------------------------------------------------------------------------------------------

    @Test
    fun aTimedStartMovesToANewDayAndKeepsItsTimeAndTheDueDate() {
        val text = todo("DTSTART:20261103T090000Z", "DUE:20261110T170000Z")
        val out = patch(text, EditKeys.START to d("2026-11-05"))
        assertEquals("DTSTART:20261105T090000Z", lineOf(out, "DTSTART"))
        assertEquals("DUE:20261110T170000Z", lineOf(out, "DUE"))
        assertFalse(out.contains("VALUE=DATE"))
        assertValidForTheServer(out)
    }

    @Test
    fun theTimeOfDayIsTheOneTheCalendarShowsInThePersonsZone() {
        // 09:00 UTC is 14:00 in Karachi; the start moves to the 5th and stays 14:00 there, which is 09:00 UTC
        val text = todo("DTSTART:20261103T090000Z", "DUE:20261110T170000Z")
        val out = patch(text, EditKeys.START to d("2026-11-05"), zone = ZoneId.of("Asia/Karachi"))
        assertEquals("DTSTART:20261105T090000Z", lineOf(out, "DTSTART"))
        // 23:00 UTC on the 3rd is 04:00 on the 4th in Karachi; moving "the 4th" to "the 6th" gives 04:00 on the 6th = 23:00 UTC on the 5th
        val late = patch(todo("DTSTART:20261103T230000Z", "DUE:20261110T170000Z"), EditKeys.START to d("2026-11-06"), zone = ZoneId.of("Asia/Karachi"))
        assertEquals("DTSTART:20261105T230000Z", lineOf(late, "DTSTART"))
    }

    @Test
    fun aStartWithATimeZoneKeepsItsZoneAndTime() {
        val text = todo("DTSTART;TZID=Europe/Berlin:20261103T090000", "DUE;TZID=Europe/Berlin:20261110T170000")
        val out = patch(text, EditKeys.START to d("2026-11-05"))
        assertEquals("DTSTART;TZID=Europe/Berlin:20261105T090000", lineOf(out, "DTSTART"))
        assertEquals("DUE;TZID=Europe/Berlin:20261110T170000", lineOf(out, "DUE"))
        assertValidForTheServer(out)
    }

    @Test
    fun aDueDayOnAToDoWithATimedStartIsWrittenAsADayAndTimeToo() {
        val out = patch(todo("DTSTART:20261103T090000Z"), EditKeys.DUE to d("2026-11-10"))
        assertEquals("DUE:20261110T090000Z", lineOf(out, "DUE"))
        assertEquals("DTSTART:20261103T090000Z", lineOf(out, "DTSTART"))
        assertValidForTheServer(out)
    }

    @Test
    fun aDueTimeOnAToDoWithADayStartMakesTheStartATimeToo() {
        val due = EditKeys.moment(Instant.parse("2026-11-10T17:00:00Z"))
        val out = patch(todo("DTSTART;VALUE=DATE:20261103"), EditKeys.DUE to due)
        assertEquals("DUE:20261110T170000Z", lineOf(out, "DUE"))
        assertEquals("DTSTART:20261103T000000Z", lineOf(out, "DTSTART"))
        assertValidForTheServer(out)
    }

    @Test
    fun changingBothDatesOfADayToDoWithATimeGivesBothATime() {
        val due = EditKeys.moment(Instant.parse("2026-11-10T17:00:00Z"))
        val out = patch(todo("DTSTART;VALUE=DATE:20261103", "DUE;VALUE=DATE:20261105"), EditKeys.START to d("2026-11-04"), EditKeys.DUE to due)
        assertEquals("DTSTART:20261104T000000Z", lineOf(out, "DTSTART"))
        assertEquals("DUE:20261110T170000Z", lineOf(out, "DUE"))
        assertValidForTheServer(out)
    }

    @Test
    fun aToDoOfDaysStaysAToDoOfDays() {
        val out = patch(todo("DTSTART;VALUE=DATE:20261103", "DUE;VALUE=DATE:20261110"), EditKeys.START to d("2026-11-05"))
        assertEquals("DTSTART;VALUE=DATE:20261105", lineOf(out, "DTSTART"))
        assertEquals("DUE;VALUE=DATE:20261110", lineOf(out, "DUE"))
        assertValidForTheServer(out)
    }

    @Test
    fun clearingTheDueDateLeavesTheStartAlone() {
        val out = patch(todo("DTSTART:20261103T090000Z", "DUE:20261110T170000Z"), EditKeys.DUE to "")
        assertEquals("", lineOf(out, "DUE"))
        assertEquals("DTSTART:20261103T090000Z", lineOf(out, "DTSTART"))
    }

    @Test
    fun anUnchangedDateIsNotRewrittenByteForByte() {
        val text = todo("DTSTART;TZID=Europe/Berlin:20261103T090000", "DUE;TZID=Europe/Berlin:20261110T170000")
        val out = patch(text, EditKeys.SUMMARY to "new title")
        assertEquals(lineOf(text, "DTSTART"), lineOf(out, "DTSTART"))
        assertEquals(lineOf(text, "DUE"), lineOf(out, "DUE"))
    }

    @Test
    fun aNewToDoWithDaysIsMadeWithDays() {
        val text = CalendarEdit.create(true, "u1", mapOf(EditKeys.SUMMARY to "x", EditKeys.START to d("2026-11-03"), EditKeys.DUE to d("2026-11-10")), now)
        assertEquals("DTSTART;VALUE=DATE:20261103", lineOf(text.replace("\r\n", "\r\n"), "DTSTART"))
        assertValidForTheServer(text)
    }

    @Test
    fun aStartAfterTheDueDateIsRefusedByTheForm() {
        val form = TodoForm("t", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 3), null, "", EditKeys.SELF)
        assertFalse(form.datesValid())
        assertTrue(form.copy(due = LocalDate.of(2026, 11, 10)).datesValid())
        assertTrue(form.copy(start = null).datesValid())
        assertTrue(form.copy(due = null).datesValid())
    }

    @Test
    fun aChangeThatTheServerAlreadyHasIsRecognisedAndOneItDoesNotHaveIsNot() {
        val text = todo("DTSTART:20261103T090000Z", "DUE:20261110T170000Z")
        val change = mapOf(EditKeys.START to d("2026-11-05"))
        val applied = CalendarEdit.patch(text, true, change, now, UTC)
        assertTrue(CalendarEdit.alreadyApplied(applied, true, change, UTC))
        assertFalse(CalendarEdit.alreadyApplied(text, true, change, UTC))
    }

    @Test
    fun theLocalWriterKeepsParametersAndReadsThem() {
        val patch = ICalPatch(todo("DTSTART;TZID=Europe/Berlin:20261103T090000"), "VTODO")
        assertEquals(";TZID=Europe/Berlin", patch.paramText("DTSTART"))
        assertEquals("", patch.paramText("SUMMARY"))
        assertNull(patch.paramText("DUE"))
    }

    // ---- finding 2: a cut description -------------------------------------------------------------------------------------

    private fun event(description: String) = AgendaItem(
        key = "cal:work/e.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Call", timed = true,
        start = Instant.parse("2026-10-28T10:00:00Z"), end = Instant.parse("2026-10-28T11:00:00Z"), description = description
    )

    @Test
    fun aDescriptionOfTheReadersLimitIsLockedAndNeverInACommand() {
        val cut = event("x".repeat(EditRules.DESCRIPTION_LIMIT))
        assertTrue(EditRules.descriptionLocked(cut))
        val form = EventForm.of(cut, UTC)
        val command = EditCommands.event(cut, form.copy(title = "Call moved", description = form.description + " more"), UTC, emptyList())
        assertNotNull(command)
        assertEquals("Call moved", command!!.change[EditKeys.SUMMARY])
        assertFalse("the cut text must never be written back", command.change.containsKey(EditKeys.DESCRIPTION))
        // only the description changed: nothing to send at all
        assertNull(EditCommands.event(cut, form.copy(description = "short"), UTC, emptyList()))
    }

    @Test
    fun aShorterDescriptionCanBeChanged() {
        val item = event("x".repeat(EditRules.DESCRIPTION_LIMIT - 1))
        assertFalse(EditRules.descriptionLocked(item))
        val form = EventForm.of(item, UTC)
        assertEquals("new", EditCommands.event(item, form.copy(description = "new"), UTC, emptyList())!!.change[EditKeys.DESCRIPTION])
    }

    @Test
    fun aToDoDescriptionThatWasCutIsLockedToo() {
        val item = AgendaItem(key = "cal:p/t.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "t", description = "y".repeat(3000).take(EditRules.DESCRIPTION_LIMIT))
        val form = TodoForm.of(item, UTC)
        val command = EditCommands.todo(item, form.copy(title = "t2", description = "short"), UTC, emptyList())!!
        assertFalse(command.change.containsKey(EditKeys.DESCRIPTION))
        assertEquals("t2", command.change[EditKeys.SUMMARY])
    }

    // ---- finding 4: where "+" may add ------------------------------------------------------------------------------------

    @Test
    fun plusOffersOnlyMyOwnCalendarsThatShowOnTheAgenda() {
        val offered = AgendaBuilder.build(loadScenario("ali")).writableCalendars
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.all { it.mode == CalendarMode.PERSONAL || it.mode == CalendarMode.WORK })
        assertTrue("a calendar shared with me is not offered", offered.none { it.uri.contains("shared_by") })
        // a calendar set to Hidden is not offered either
        val hidden = AgendaBuilder.build(loadScenario("ali", settings = SourceSettings().withCalendar("work", CalendarMode.HIDDEN))).writableCalendars
        assertTrue(hidden.none { it.uri == "work" })
        assertTrue(hidden.any { it.uri == "personal" })
        // a shared calendar the person turned to Work is still not theirs
        val shared = AgendaBuilder.build(loadScenario("ali", settings = SourceSettings().withCalendar("mara-team_shared_by_mara", CalendarMode.WORK))).writableCalendars
        assertTrue(shared.none { it.uri.contains("shared_by") })
    }

    private fun calendar(uri: String, mode: CalendarMode, isDefault: Boolean = false) =
        WritableCalendar(uri, "/remote.php/dav/calendars/ali/$uri/", uri, "#2563EB", true, true, isDefault, mode)

    @Test
    fun theFirstChoiceIsTheServersDefaultThenTheLastUsedThenTheFirstOwn() {
        val team = calendar("team", CalendarMode.TEAM, isDefault = true)
        val work = calendar("work", CalendarMode.WORK)
        val personal = calendar("personal", CalendarMode.PERSONAL)
        val all = listOf(team, work, personal)
        // a team calendar is never preselected, not even when it is the server's default
        assertEquals("work", NewItemDefaults.pick(all, "")!!.uri)
        assertEquals("personal", NewItemDefaults.pick(all, "personal")!!.uri)
        assertEquals("personal", NewItemDefaults.pick(listOf(work, personal.copy(isDefault = true)), "work")!!.uri)
        assertEquals("work", NewItemDefaults.pick(listOf(work, personal), "gone")!!.uri)
        assertNull(NewItemDefaults.pick(listOf(team, calendar("hidden", CalendarMode.HIDDEN)), ""))
    }

    // ---- finding 8: clashes of timed Gantt tasks -------------------------------------------------------------------------

    @Test
    fun aGanttTaskOnOneDayWithRealTimesHasClashTimes() {
        val times = GanttMapper.clashTimes("2026-10-28 09:00:00", "2026-10-28 11:30:00", UTC)!!
        assertEquals(Instant.parse("2026-10-28T09:00:00Z"), times.first)
        assertEquals(Instant.parse("2026-10-28T11:30:00Z"), times.second)
        // the wall time of the server is read in the person's zone
        assertEquals(Instant.parse("2026-10-28T04:00:00Z"), GanttMapper.clashTimes("2026-10-28 09:00:00", "2026-10-28 10:00:00", ZoneId.of("Asia/Karachi"))!!.first)
    }

    @Test
    fun aGanttTaskWithoutRealTimesOrOverSeveralDaysHasNone() {
        assertNull(GanttMapper.clashTimes("2026-10-28 00:00:00", "2026-10-28 00:00:00", UTC))
        assertNull(GanttMapper.clashTimes("2026-10-28 00:00:00", "2026-10-29 00:00:00", UTC))
        assertNull(GanttMapper.clashTimes("2026-10-28 09:00:00", "2026-10-29 10:00:00", UTC))
        assertNull(GanttMapper.clashTimes("2026-10-28", "2026-10-28", UTC))
        assertNull(GanttMapper.clashTimes("2026-10-28 11:00:00", "2026-10-28 09:00:00", UTC))
        assertNull(GanttMapper.clashTimes("", "", UTC))
    }

    @Test
    fun twoTimedGanttTasksOfMineOnOneDayThatOverlapClash() {
        fun task(key: String, from: String, to: String) = AgendaItem(
            key = key, kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = key, workKind = WorkKind.SELF,
            clashStart = Instant.parse("2026-10-28T${from}:00Z"), clashEnd = Instant.parse("2026-10-28T${to}:00Z")
        )
        val badges = Conflicts.clashes(listOf(task("a", "09:00", "11:00"), task("b", "10:00", "12:00"), task("c", "13:00", "14:00")))
        assertEquals(setOf("a", "b"), badges.keys)
    }

    // ---- nit: an event someone else organises -----------------------------------------------------------------------------

    @Test
    fun anEventThatSomeoneElseOrganisesIsNotChangedEvenWhenMyAddressWasNotMatched() {
        val other = event("").copy(hasOrganizer = true, organizerIsMe = false, invitation = false)
        assertEquals(EditBlock.INVITATION, EditRules.of(other).block)
        assertTrue(EditRules.of(other.copy(organizerIsMe = true)).canEdit)
        assertTrue(EditRules.of(other.copy(hasOrganizer = false)).canEdit)
    }
}
