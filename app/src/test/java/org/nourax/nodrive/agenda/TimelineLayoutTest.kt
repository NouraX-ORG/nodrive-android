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
import org.nourax.nodrive.agenda.edit.EditBlock
import org.nourax.nodrive.agenda.edit.TimelineEdit
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.AgendaFilter
import org.nourax.nodrive.agenda.logic.AgendaView
import org.nourax.nodrive.agenda.logic.DayLoad
import org.nourax.nodrive.agenda.logic.TimelineEntryType
import org.nourax.nodrive.agenda.logic.TimelineGroup
import org.nourax.nodrive.agenda.logic.TimelineInput
import org.nourax.nodrive.agenda.logic.TimelineLayout
import org.nourax.nodrive.agenda.logic.TimelineResult
import org.nourax.nodrive.agenda.logic.TimelineScale
import org.nourax.nodrive.agenda.logic.TimelineZoom
import org.nourax.nodrive.agenda.logic.ViewState
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemDependency
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.DayOfWeek
import java.time.LocalDate

/** The Timeline's maths (plan 3.5): date <-> x, groups and order, arrows, stubs, shading, and what may be changed. No Android types. */
class TimelineLayoutTest {
    private val first = LocalDate.of(2026, 10, 21)
    private val scale = TimelineScale(first, 64, TimelineZoom.WEEKS.dpPerDay)

    private fun d(offset: Long): LocalDate = TODAY.plusDays(offset)

    private fun task(
        id: Int,
        title: String,
        start: LocalDate?,
        end: LocalDate?,
        project: Int = 1,
        phase: Int = 0,
        deps: List<ItemDependency> = emptyList(),
        percent: Int = 0
    ) = AgendaItem(
        key = "gantt:task:$id", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = title, startDate = start, endDate = end, due = end,
        sourceKey = "gantt:$project", sourceName = "Project $project", sourceColor = "#2563EB", projectId = project, projectName = "Project $project",
        phaseId = phase, phaseName = if (phase > 0) "Phase $phase" else "", taskId = id, dependencies = deps, percentDone = percent,
        status = "in_progress", version = "v1"
    )

    private fun build(items: List<AgendaItem>, loads: Map<LocalDate, DayLoad> = emptyMap(), window: Pair<LocalDate, LocalDate>? = null, s: TimelineScale = scale): TimelineResult =
        TimelineLayout.build(TimelineInput(items, s, TODAY, loads, window))

    private fun TimelineResult.entry(key: String) = entries.first { it.key == key }

    @Test
    fun dateAndXRoundTripAtBothZooms() {
        for (zoom in TimelineZoom.entries) {
            val s = TimelineScale(first, 64, zoom.dpPerDay)
            for (offset in listOf(0L, 1L, 6L, 7L, 30L, 63L)) {
                val day = first.plusDays(offset)
                assertEquals(offset * zoom.dpPerDay, s.xOfDay(day), 0.0001)
                assertEquals(day, s.dayAtX(s.xOfDay(day)))
                // the middle of the day belongs to the day, the right edge to the next one
                assertEquals(day, s.dayAtX(s.xOfDay(day) + zoom.dpPerDay / 2))
                assertEquals(day.plusDays(1), s.dayAtX(s.xAfterDay(day)))
            }
            assertEquals(64 * zoom.dpPerDay, s.width, 0.0001)
        }
        assertEquals(12.0, TimelineZoom.WEEKS.dpPerDay, 0.0)
        assertEquals(40.0, TimelineZoom.DAYS.dpPerDay, 0.0)
    }

    @Test
    fun theRangeIsSevenDaysBeforeToEightWeeksAfter() {
        val state = ViewState(view = AgendaView.TIMELINE, date = TODAY)
        val range = AgendaFilter.range(state, DayOfWeek.MONDAY)
        assertEquals(TODAY.minusDays(7), range.first)
        assertEquals(TODAY.plusWeeks(8), range.second)
        assertEquals(64, TimelineScale.of(range, TimelineZoom.WEEKS).days)
        // the Timeline has no list rows
        assertTrue(AgendaFilter.rows(org.nourax.nodrive.agenda.logic.AgendaBuilder.build(loadAliAnswered()), state, TODAY, UTC, DayOfWeek.MONDAY).isEmpty())
    }

    @Test
    fun aBarSpansTheFirstToTheLastDayIncluded() {
        val r = build(listOf(task(1, "A", d(0), d(2))))
        val e = r.entry("gantt:task:1")
        assertEquals(TimelineEntryType.BAR, e.type)
        assertEquals(scale.xOfDay(d(0)), e.rect.left, 0.0001)
        assertEquals(scale.xAfterDay(d(2)), e.rect.right, 0.0001)
        assertEquals(3 * 12.0, e.rect.width, 0.0001)
        assertFalse(e.clippedLeft)
        assertFalse(e.clippedRight)
        // a one-day bar is one day wide
        val one = build(listOf(task(2, "B", d(1), d(1)))).entry("gantt:task:2")
        assertEquals(12.0, one.rect.width, 0.0001)
    }

    @Test
    fun aBarThatStartsBeforeOrEndsAfterTheRangeIsCutAndMarked() {
        val r = build(listOf(task(1, "Long", first.minusDays(5), scale.last.plusDays(5)), task(2, "Outside", first.minusDays(9), first.minusDays(3))))
        val e = r.entry("gantt:task:1")
        assertTrue(e.clippedLeft)
        assertTrue(e.clippedRight)
        assertEquals(0.0, e.rect.left, 0.0001)
        assertEquals(scale.width, e.rect.right, 0.0001)
        assertTrue("a bar entirely outside the range is not drawn", r.entries.none { it.key == "gantt:task:2" })
    }

    @Test
    fun theGroupsAreProjectThenPhaseThenItemsInStartOrder() {
        val items = listOf(
            task(1, "Late start", d(5), d(6), project = 1, phase = 2),
            task(2, "Early start", d(1), d(2), project = 1, phase = 2),
            task(3, "No phase", d(3), d(4), project = 1),
            task(4, "Other phase", d(0), d(1), project = 1, phase = 1),
            task(5, "Second project", d(0), d(1), project = 2)
        )
        val keys = build(items).entries.map { if (it.type == TimelineEntryType.GROUP) "G:${it.title}" else it.title }
        assertEquals(
            listOf(
                "G:Project 1", "No phase", // items without a phase come first
                "G:Phase 1", "Other phase", // phases by their earliest start
                "G:Phase 2", "Early start", "Late start",
                "G:Project 2", "Second project"
            ),
            keys
        )
        val groups = build(items).entries.filter { it.type == TimelineEntryType.GROUP }
        assertEquals(listOf(TimelineGroup.PROJECT, TimelineGroup.PHASE, TimelineGroup.PHASE, TimelineGroup.PROJECT), groups.map { it.group })
        // every row has its own line
        assertEquals(build(items).entries.indices.toList(), build(items).entries.map { it.row })
    }

    @Test
    fun toDosAndCardsAreGroupedUnderTheirListAndBoardAfterTheProjects() {
        val todo = AgendaItem(
            key = "cal:personal/draft.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Draft report", startDate = d(-1), due = d(3),
            sourceName = "Personal", sourceColor = "#795AAF", calendarUri = "personal"
        )
        val card = AgendaItem(
            key = "deck:card:9", kind = ItemKind.CARD, home = Home.BOARD, title = "Launch card", due = d(4), sourceName = "Team board",
            sourceColor = "#2563EB", boardId = 3, cardId = 9
        )
        val r = build(listOf(card, todo, task(1, "A", d(0), d(1))))
        val groups = r.entries.filter { it.type == TimelineEntryType.GROUP }
        assertEquals(listOf(TimelineGroup.PROJECT, TimelineGroup.LIST, TimelineGroup.BOARD), groups.map { it.group })
        assertEquals(TimelineEntryType.BAR, r.entry("cal:personal/draft.ics").type)
        assertEquals(TimelineEntryType.DIAMOND, r.entry("deck:card:9").type)
    }

    @Test
    fun onlyDatedWorkOfMineIsDrawn() {
        val noStartTodo = AgendaItem(key = "cal:l/a.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "No start", due = d(2), calendarUri = "l")
        val noDueTodo = AgendaItem(key = "cal:l/b.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "No due", startDate = d(2), calendarUri = "l")
        val repeating = AgendaItem(key = "cal:l/c.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Repeats", startDate = d(1), due = d(2), repeating = true, calendarUri = "l")
        val event = AgendaItem(key = "cal:l/d.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Event", timed = true)
        val team = task(7, "Team", d(1), d(2)).copy(workKind = WorkKind.TEAM)
        val done = task(8, "Done", d(1), d(2)).copy(done = true)
        val problem = task(9, "Problem", d(1), d(2)).copy(problemOnly = true)
        val info = task(10, "Info", d(1), d(2)).copy(info = true)
        val mine = task(11, "Mine", d(1), d(2))
        val r = build(listOf(noStartTodo, noDueTodo, repeating, event, team, done, problem, info, mine))
        assertEquals(listOf("gantt:task:11"), r.entries.filter { it.type != TimelineEntryType.GROUP }.map { it.key })
        assertTrue(build(emptyList()).entries.isEmpty())
        assertFalse(build(listOf(event)).hasWork)
        assertTrue(r.hasWork)
    }

    @Test
    fun aTaskWithOnlyAnEndDateIsDrawnButCannotBeMoved() {
        val e = build(listOf(task(1, "End only", null, d(3)))).entry("gantt:task:1")
        assertEquals(TimelineEntryType.BAR, e.type)
        assertTrue(e.noDates)
        assertFalse(e.canMove)
        assertNull(TimelineEdit.spanOf(task(1, "End only", null, d(3))))
    }

    @Test
    fun milestonesAreDiamondsOnTheirDay() {
        val ms = AgendaItem(
            key = "gantt:ms:5", kind = ItemKind.MILESTONE, home = Home.GANTT, title = "Launch", startDate = d(4), endDate = d(4), due = d(4),
            sourceColor = "#2563EB", projectId = 1, projectName = "Project 1", readOnly = true, readOnlyReason = ReadOnlyReason.MILESTONE
        )
        val e = build(listOf(task(1, "A", d(0), d(1)), ms)).entry("gantt:ms:5")
        assertEquals(TimelineEntryType.DIAMOND, e.type)
        assertEquals(scale.xOfDay(d(4)) + 6.0, e.rect.centerX, 0.0001)
        assertEquals(TimelineLayout.DIAMOND, e.rect.width, 0.0001)
        assertFalse("a milestone is not moved here", e.canMove)
    }

    // ---- arrows ------------------------------------------------------------------------------------------------------------
    private fun two(type: String, lag: Double = 0.0): TimelineResult {
        val a = task(1, "Pred", d(0), d(2))
        val b = task(2, "Succ", d(5), d(7), deps = listOf(ItemDependency("gantt:task:1", type, lag, "Pred", "Ali")))
        return build(listOf(a, b))
    }

    @Test
    fun theFourDependencyTypesStartAndEndAtTheRightEdges() {
        val expected = mapOf(
            "EA" to (true to true), // pred finish -> succ start
            "AA" to (false to true), // pred start -> succ start
            "EE" to (true to false), // pred finish -> succ finish
            "AE" to (false to false) // pred start -> succ finish
        )
        for ((type, ends) in expected) {
            val r = two(type)
            val arrow = r.arrows.single()
            val pred = r.entry("gantt:task:1").rect
            val succ = r.entry("gantt:task:2").rect
            val (fromFinish, toStart) = ends
            assertEquals(type, arrow.type)
            assertFalse(arrow.stub)
            assertEquals("gantt:task:1", arrow.fromKey)
            assertEquals("gantt:task:2", arrow.toKey)
            assertEquals("$type start x", if (fromFinish) pred.right else pred.left, arrow.points.first().x, 0.0001)
            assertEquals("$type start y", pred.centerY, arrow.points.first().y, 0.0001)
            assertEquals("$type end x", if (toStart) succ.left else succ.right, arrow.points.last().x, 0.0001)
            assertEquals("$type end y", succ.centerY, arrow.points.last().y, 0.0001)
        }
    }

    @Test
    fun theLagIsKeptOnTheArrowForTheDetail() {
        assertEquals(2.0, two("EA", 2.0).arrows.single().lagDays, 0.0)
    }

    @Test
    fun aPredecessorThatIsNotOnTheScreenIsAShortStubWithItsTitleAndOwner() {
        val succ = task(2, "Succ", d(5), d(7), deps = listOf(ItemDependency("gantt:task:99", "EA", 0.0, "Banner design", "Sara")))
        val r = build(listOf(succ))
        val arrow = r.arrows.single()
        assertTrue(arrow.stub)
        assertEquals("", arrow.fromKey)
        assertEquals("Banner design", arrow.fromTitle)
        assertEquals("Sara", arrow.predecessorOwner)
        val rect = r.entry("gantt:task:2").rect
        assertEquals(2, arrow.points.size)
        assertEquals(rect.left, arrow.points.last().x, 0.0001)
        assertEquals(rect.left - TimelineLayout.STUB, arrow.points.first().x, 0.0001)
        // a predecessor that exists but is outside the range is a stub as well
        val far = task(1, "Far", first.minusDays(30), first.minusDays(20))
        assertTrue(build(listOf(far, succ.copy(dependencies = listOf(ItemDependency("gantt:task:1", "EA"))))).arrows.single().stub)
    }

    @Test
    fun aPredecessorMilestoneGetsAnArrowToo() {
        val ms = AgendaItem(key = "gantt:ms:5", kind = ItemKind.MILESTONE, home = Home.GANTT, title = "Kick-off", startDate = d(1), projectId = 1, projectName = "Project 1")
        val succ = task(2, "Succ", d(5), d(7), deps = listOf(ItemDependency("gantt:ms:5", "EA")))
        val arrow = build(listOf(ms, succ)).arrows.single()
        assertFalse(arrow.stub)
        assertEquals("gantt:ms:5", arrow.fromKey)
    }

    // ---- shading ------------------------------------------------------------------------------------------------------------
    @Test
    fun busyTimeShadesTheDayFromZeroToThirtyFivePercent() {
        val loads = mapOf(
            d(0) to DayLoad(d(0), true, 0.0, 8.0, 0, busyHours = 0.0),
            d(1) to DayLoad(d(1), true, 0.0, 4.0, 0, busyHours = 4.0),
            d(2) to DayLoad(d(2), true, 0.0, 0.0, 0, busyHours = 8.0)
        )
        val r = build(listOf(task(1, "A", d(0), d(1))), loads, window = d(-3) to d(5))
        fun alphaOf(day: LocalDate) = r.shading.first { it.day == day }.alpha
        assertEquals(0.0, alphaOf(d(0)), 0.0001)
        assertEquals(0.175, alphaOf(d(1)), 0.0001)
        assertEquals(0.35, alphaOf(d(2)), 0.0001)
        assertEquals(64, r.shading.size)
        assertEquals(scale.xOfDay(d(1)), r.shading.first { it.day == d(1) }.x, 0.0001)
    }

    @Test
    fun aDayWithoutAWorkingDayInTheKnownWindowIsADayOffAndOutsideTheWindowIsNotGuessed() {
        val loads = mapOf(d(0) to DayLoad(d(0), true, 0.0, 8.0, 0))
        val r = build(listOf(task(1, "A", d(0), d(1))), loads, window = d(-3) to d(3))
        assertFalse(r.shading.first { it.day == d(0) }.dayOff)
        assertTrue(r.shading.first { it.day == d(1) }.dayOff)
        assertFalse("outside the window nothing is known", r.shading.first { it.day == d(10) }.dayOff)
        // a day that is in the loads but not a working day (a holiday with planned work) is a day off
        val off = mapOf(d(0) to DayLoad(d(0), false, 2.0, 0.0, 0))
        assertTrue(build(listOf(task(1, "A", d(0), d(1))), off, window = d(-3) to d(3)).shading.first { it.day == d(0) }.dayOff)
        // without any analysis there is no hatching at all
        assertTrue(build(listOf(task(1, "A", d(0), d(1)))).shading.none { it.dayOff || it.alpha > 0.0 })
    }

    @Test
    fun todayHasAPositionOnlyInsideTheRange() {
        assertEquals(scale.xOfDay(TODAY), build(listOf(task(1, "A", d(0), d(1)))).todayX, 0.0001)
        val later = TimelineScale(TODAY.plusDays(30), 10, 12.0)
        assertEquals(-1.0, build(emptyList(), s = later).todayX, 0.0)
    }

    // ---- what may be changed ------------------------------------------------------------------------------------------------
    @Test
    fun readOnlyItemsGiveNoEditCommand() {
        val zone = UTC
        val ok = task(1, "Free", d(0), d(2))
        assertNotNull(TimelineEdit.moveBy(ok, 1, zone, emptyList()))
        val locked = mapOf(
            "viewer project" to ok.copy(readOnly = true, readOnlyReason = ReadOnlyReason.VIEWER_PROJECT),
            "version guard" to ok.copy(versionReadOnly = true),
            "waiting to send" to ok.copy(pending = true),
            "team row" to ok.copy(workKind = WorkKind.TEAM),
            "problem only" to ok.copy(problemOnly = true)
        )
        for ((name, item) in locked) {
            assertNull(name, TimelineEdit.moveBy(item, 1, zone, emptyList()))
            assertNull(name, TimelineEdit.command(item, d(1), d(3), zone, emptyList()))
            val e = build(listOf(item)).entries.firstOrNull { it.type != TimelineEntryType.GROUP }
            if (e != null) assertFalse(name, e.canMove)
        }
        assertEquals(EditBlock.READ_ONLY, build(listOf(locked.getValue("viewer project"))).entry("gantt:task:1").block)
        assertEquals(EditBlock.VERSION_GUARD, build(listOf(locked.getValue("version guard"))).entry("gantt:task:1").block)
        assertEquals(EditBlock.WAITING, build(listOf(locked.getValue("waiting to send"))).entry("gantt:task:1").block)
        // a repeating to-do is not a bar at all, and a milestone gives no command
        val ms = AgendaItem(key = "gantt:ms:1", kind = ItemKind.MILESTONE, home = Home.GANTT, title = "M", startDate = d(1), readOnly = true, readOnlyReason = ReadOnlyReason.MILESTONE)
        assertNull(TimelineEdit.moveBy(ms, 1, zone, emptyList()))
    }

    @Test
    fun theSameInputAlwaysGivesTheSameLayout() {
        val items = listOf(
            task(1, "A", d(0), d(2), deps = listOf(ItemDependency("gantt:task:2", "EA"))),
            task(2, "B", d(-2), d(-1), phase = 3),
            task(3, "C", d(4), d(6), project = 2)
        )
        assertEquals(build(items), build(items))
        assertEquals(build(items), build(items.reversed()))
    }

    @Test
    fun theAgentOfAnItemStaysOnItsEntry() {
        val writer = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot")
        val e = build(listOf(task(1, "A", d(0), d(2)).copy(agent = writer, workKind = WorkKind.MY_AGENT))).entry("gantt:task:1")
        assertEquals("Writer-Bot", e.item!!.agent.name)
    }

    @Test
    fun theMapperGivesPhasesAndDependenciesFromNoGantt() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        val copy = items.byTitle("Write homepage copy")!!
        assertEquals(401, copy.phaseId)
        assertEquals("Phase 1", copy.phaseName)
        val banners = items.byTitle("Publish banners")!!
        val dep = banners.dependencies.single()
        assertEquals("gantt:task:103", dep.predecessorKey)
        assertEquals("EA", dep.type)
        assertEquals("Banner design", dep.predecessorTitle)
        assertEquals("Sara", dep.predecessorOwner) // the person, never the agent's planning person
        // the layout draws it as a stub: Banner design belongs to Sara, so it is not one of my rows
        val layout = TimelineLayout.build(
            TimelineInput(items, TimelineScale.of(TODAY.minusDays(7) to TODAY.plusWeeks(8), TimelineZoom.WEEKS), TODAY)
        )
        assertTrue(layout.arrows.any { it.stub && it.toKey == banners.key && it.fromTitle == "Banner design" })
        assertEquals(1, layout.entries.count { it.type == TimelineEntryType.GROUP && it.group == TimelineGroup.PHASE })
    }
}
