/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EditRunner
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.edit.TaskForm
import org.nourax.nodrive.agenda.edit.TimelineEdit
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.LocalDate

/**
 * The Timeline's date editor (plan 3.4, 3.5): a move of a bar is a command like any other, a pure move of a NoGantt task is sent with
 * `adjust_lag`, the notice says the dates NoGantt really saved, and a locked row gives no command.
 */
class TimelineEditTest {
    private val clock: AgendaClock = FixedAgendaClock(NOW)
    private val registry = AgentRegistry.parse("""{"schema":"nourax-agent-registry","version":1,"agents":[]}""".toByteArray())

    private fun runner(server: EditFakeServer, store: InMemoryEditStore = InMemoryEditStore()) =
        EditRunner("ali@test", server, store, clock, { registry }, { "ali" }, { "Ali" }, { false })

    private fun ganttServer(): EditFakeServer {
        val server = EditFakeServer()
        val task = JsonObject()
        task.addProperty("id", 5)
        task.addProperty("title", "Landing page build")
        task.addProperty("project_id", 1)
        task.addProperty("start_date", "2026-10-28 00:00:00")
        task.addProperty("end_date", "2026-10-30 00:00:00")
        task.addProperty("status", "in_progress")
        task.addProperty("manual_progress", 10)
        task.addProperty("progress_mode", "manual")
        task.addProperty("updated_at", "2026-10-28 08:00:00")
        task.add("resources", JsonArray())
        server.ganttTasks = JsonArray().also { it.add(task) }
        server.ganttProjects = JsonParser.parseString("""[{"id":1,"title":"Website","permission":"editor"}]""").asJsonArray
        return server
    }

    private fun taskItem() = AgendaItem(
        key = "gantt:task:5", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Landing page build",
        startDate = LocalDate.of(2026, 10, 28), endDate = LocalDate.of(2026, 10, 30), due = LocalDate.of(2026, 10, 30),
        projectId = 1, taskId = 5, version = "2026-10-28 08:00:00", percentDone = 10, status = "in_progress"
    )

    private fun put(server: EditFakeServer): JsonObject = JsonParser.parseString(server.requests.single { it.method == "PUT" }.body).asJsonObject

    @Test
    fun movingATaskByTwoDaysIsOneGanttUpdateMarkedAsAMove() {
        val command = TimelineEdit.moveBy(taskItem(), 2, UTC, emptyList())!!
        assertEquals(CommandKind.GANTT_UPDATE, command.kind)
        assertEquals("D:2026-10-30", command.change[EditKeys.START])
        assertEquals("D:2026-11-01", command.change[EditKeys.END])
        assertEquals(EditKeys.MOVE_PURE, command.change[EditKeys.MOVE])
        assertEquals("2026-10-30", command.change[EditKeys.MOVE_START])
        assertEquals("2026-11-01", command.change[EditKeys.MOVE_END])
        assertEquals("the version the person saw", "2026-10-28 08:00:00", command.baseVersion)
        // one day earlier
        val back = TimelineEdit.moveBy(taskItem(), -1, UTC, emptyList())!!
        assertEquals("D:2026-10-27", back.change[EditKeys.START])
        assertEquals("D:2026-10-29", back.change[EditKeys.END])
    }

    @Test
    fun aPureMoveIsSentWithAdjustLagAndNoGanttsOwnMoveBehaviour() {
        val server = ganttServer()
        val r = runner(server)
        r.enqueue(TimelineEdit.moveBy(taskItem(), 2, UTC, emptyList())!!)
        val report = r.run()
        val body = put(server)
        assertEquals("2026-10-30 00:00:00", body.get("start_date").asString)
        assertEquals("2026-11-01 00:00:00", body.get("end_date").asString)
        assertTrue(body.get("adjust_lag").asBoolean)
        assertFalse("a linked card never follows without asking", body.get("deck_confirmed").asBoolean)
        assertFalse("the local marks never reach the server", server.requests.single { it.method == "PUT" }.body.contains("move"))
        val notice = report.notice!!
        assertEquals(LocalDate.of(2026, 10, 30), notice.movedStart)
        assertEquals(LocalDate.of(2026, 11, 1), notice.movedEnd)
        assertFalse(notice.datesAdjusted)
        assertEquals("op-1", notice.undoOp)
    }

    @Test
    fun theNoticeNamesTheDatesNoGanttSavedWhenItSnappedThemToWorkingDays() {
        val server = ganttServer()
        server.snapDates = mapOf("start_date" to "2026-11-02 00:00:00", "end_date" to "2026-11-04 00:00:00")
        val r = runner(server)
        r.enqueue(TimelineEdit.moveBy(taskItem(), 3, UTC, emptyList())!!)
        val notice = r.run().notice!!
        assertEquals(LocalDate.of(2026, 11, 2), notice.movedStart)
        assertEquals(LocalDate.of(2026, 11, 4), notice.movedEnd)
        assertTrue(notice.datesAdjusted)
    }

    @Test
    fun otherDateChangesAreNotAMoveAndGetNoAdjustLag() {
        val server = ganttServer()
        val r = runner(server)
        val resize = TimelineEdit.command(taskItem(), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3), UTC, emptyList())!!
        assertEquals(EditKeys.MOVE_SET, resize.change[EditKeys.MOVE])
        assertNull(resize.change[EditKeys.START])
        r.enqueue(resize)
        val notice = r.run().notice!!
        val body = put(server)
        assertEquals("2026-11-03 00:00:00", body.get("end_date").asString)
        assertFalse(body.has("start_date"))
        assertFalse(body.has("adjust_lag"))
        assertEquals(LocalDate.of(2026, 11, 3), notice.movedEnd)
    }

    @Test
    fun aLaterEditOfTheSameTaskDoesNotKeepTheOldMoveMark() {
        val server = ganttServer()
        val r = runner(server)
        r.enqueue(TimelineEdit.moveBy(taskItem(), 1, UTC, emptyList())!!)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(percent = 50), emptyList()).single())
        r.run()
        val body = put(server)
        assertEquals(50, body.get("manual_progress").asInt)
        assertFalse("the percent edit is not a move", body.has("adjust_lag") && body.get("adjust_lag").asBoolean)
    }

    @Test
    fun nothingChangedOrNotValidIsNoCommand() {
        val item = taskItem()
        assertNull(TimelineEdit.moveBy(item, 0, UTC, emptyList()))
        assertNull(TimelineEdit.command(item, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), UTC, emptyList()))
        assertNull("a task without a start has no dates to move", TimelineEdit.moveBy(item.copy(startDate = null), 1, UTC, emptyList()))
    }

    @Test
    fun aTodoMovesBothItsDates() {
        val todo = AgendaItem(
            key = "cal:personal/draft.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Draft report",
            startDate = LocalDate.of(2026, 10, 27), due = LocalDate.of(2026, 10, 31), calendarUri = "personal",
            calendarHref = "/remote.php/dav/calendars/ali/personal/", objectHref = "/remote.php/dav/calendars/ali/personal/draft.ics", version = "\"e1\""
        )
        val command = TimelineEdit.moveBy(todo, 2, UTC, emptyList())!!
        assertEquals(CommandKind.CAL_PATCH, command.kind)
        assertEquals("D:2026-10-29", command.change[EditKeys.START])
        assertEquals("D:2026-11-02", command.change[EditKeys.DUE])
        assertEquals(EditKeys.MOVE_PURE, command.change[EditKeys.MOVE])
        // a start after the due date is refused
        assertNull(TimelineEdit.command(todo, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), UTC, emptyList()))
        // a repeating to-do is changed in NoTasks
        assertNull(TimelineEdit.moveBy(todo.copy(repeating = true), 1, UTC, emptyList()))
    }

    @Test
    fun aCardMovesItsDueDateOnly() {
        val card = AgendaItem(
            key = "deck:card:9", kind = ItemKind.CARD, home = Home.BOARD, title = "Launch card", due = LocalDate.of(2026, 11, 2),
            boardId = 3, stackId = 4, cardId = 9, version = "1000"
        )
        val span = TimelineEdit.spanOf(card)!!
        assertTrue(span.single)
        val command = TimelineEdit.moveBy(card, 1, UTC, emptyList())!!
        assertEquals(CommandKind.DECK_UPDATE, command.kind)
        assertEquals("D:2026-11-03", command.change[EditKeys.DUE])
        assertNotNull(command.change[EditKeys.MOVE])
    }

    @Test
    fun aMilestoneHasNoDatesToChangeHere() {
        val ms = AgendaItem(key = "gantt:ms:1", kind = ItemKind.MILESTONE, home = Home.GANTT, title = "Launch", startDate = LocalDate.of(2026, 11, 9))
        assertNull(TimelineEdit.spanOf(ms))
        assertNull(TimelineEdit.moveBy(ms, 1, UTC, emptyList()))
    }
}
