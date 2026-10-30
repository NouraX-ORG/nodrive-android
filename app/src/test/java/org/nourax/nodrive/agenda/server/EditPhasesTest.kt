/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.AccountAgenda
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.UTC
import org.nourax.nodrive.agenda.edit.CardForm
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.edit.TaskForm
import org.nourax.nodrive.agenda.edit.TodoForm
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Every edit of design section 9 against the throwaway server, as the app would send it (plan 2.8: phases edit-calendar, edit-gantt,
 * edit-deck and conflict). What the server really holds is read back as the same user with plain HTTP, not through the app's
 * own readers. The test data is made by the test (a task, a card, an event with a name of its own), so the other tests of this
 * package find the seed as it was. Skipped unless NODRIVE_AG_URL is set.
 */
class EditPhasesTest {
    private val robot = AgentRegistry.robot
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1/"
    private val deck = "index.php/apps/deck/api/v1.0/"
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val tag = UUID.randomUUID().toString().take(6)

    @Before
    fun server() = ServerSupport.assumeServer()

    private fun row(check: String, ok: Boolean, detail: String) {
        ServerSupport.report("report.md", "| $check | ${if (ok) "PASS" else "FAIL"} | $detail |")
    }

    private fun day(offset: Long): LocalDate = today.plusDays(offset)

    private class Session(val user: String) {
        val store = InMemoryEditStore()
        val agenda = AccountAgenda("$user@throwaway", ServerSupport.http(user), InMemoryAgendaStore(), ServerSupport.clock, editStore = store)
        val raw = RawServer(user)

        fun refresh(): AgendaSnapshot = agenda.refresh(manual = true)
    }

    private fun AgendaSnapshot.mine(title: String): AgendaItem =
        build.items.first { it.title == title && it.workKind != WorkKind.TEAM && !it.problemOnly }

    private fun AgendaSnapshot.clean(what: String) {
        assertTrue("$what: nothing should wait: ${edit.pending}", edit.pending.isEmpty())
        assertTrue("$what: no conflict expected, got ${edit.conflicts.map { it.reason.name + ":" + it.detail }}", edit.conflicts.isEmpty())
    }

    // ---- edit-calendar ----------------------------------------------------------------------------------------------------
    @Test
    fun editCalendarCreateEditAgentDoneAndDelete() {
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val work = snapshot.build.writableCalendars.first { it.uri == "work" }
        val title = "Edit event $tag"

        // create, with an agent: the server holds the agent id twice (property and category)
        val form = EventForm(title, false, day(2), LocalTime.of(10, 0), day(2), LocalTime.of(11, 0), "first", "AG-0001")
        snapshot = ali.agenda.submit(EditCommands.newEvent(work, form, UTC, agents))
        snapshot.clean("create")
        var item = snapshot.mine(title)
        assertFalse(item.pending)
        assertEquals("Writer-Bot", item.agent.name)
        var ics = ali.raw.get(item.objectHref)
        assertEquals(200, ics.status)
        assertTrue(ics.body, ics.body.contains("X-NOURAX-AGENT-ID:AG-0001"))
        assertTrue(ics.body, ics.body.contains("agent:AG-0001"))
        row("edit-calendar create", true, "event with agent id and category")

        // edit the title, the time and the agent (back to Self)
        val edited = EditCommands.event(item, EventForm.of(item, UTC).copy(title = "$title moved", startTime = LocalTime.of(14, 0), endTime = LocalTime.of(15, 0), agentId = EditKeys.SELF), UTC, agents)!!
        snapshot = ali.agenda.submit(edited)
        snapshot.clean("edit")
        item = snapshot.mine("$title moved")
        assertTrue(item.agent.isSelf)
        ics = ali.raw.get(item.objectHref)
        assertTrue(ics.body, ics.body.contains("SUMMARY:$title moved"))
        assertTrue(ics.body, ics.body.contains("T140000Z"))
        assertFalse(ics.body, ics.body.contains("X-NOURAX-AGENT-ID"))
        assertFalse(ics.body, ics.body.contains("agent:AG-0001"))
        row("edit-calendar edit, agent off", true, "title, time and agent changed; both marks gone")

        // the agent on again, through a second edit of the same object (the ETag is the one the server made)
        snapshot = ali.agenda.submit(EditCommands.event(item, EventForm.of(item, UTC).copy(agentId = "AG-0001"), UTC, agents)!!)
        snapshot.clean("agent on")
        assertEquals("Writer-Bot", snapshot.mine("$title moved").agent.name)

        // delete: gone from the calendar; the server keeps it in the trash bin
        val href = item.objectHref
        snapshot = ali.agenda.submit(EditCommands.removeObject(snapshot.mine("$title moved")))
        snapshot.clean("delete")
        assertEquals(404, ali.raw.get(href).status)
        assertTrue(snapshot.build.items.none { it.title.startsWith(title) })
        val trash = ServerSupport.http("ali").send("PROPFIND", "remote.php/dav/calendars/ali/trashbin/", mapOf("Depth" to "1"))
        row("edit-calendar delete", true, "object gone (404); trash bin answered ${trash.status}")
    }

    @Test
    fun editCalendarTodoDoneAndNotDone() {
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val list = snapshot.build.writableCalendars.first { it.uri == "personal" && it.todos }
        val title = "Edit todo $tag"
        snapshot = ali.agenda.submit(EditCommands.newTodo(list, TodoForm(title, null, day(3), null, "", EditKeys.SELF), UTC, agents))
        snapshot.clean("create todo")
        val item = snapshot.mine(title)
        val href = item.objectHref
        assertEquals(day(3), item.due)

        snapshot = ali.agenda.submit(EditCommands.todoDone(item, true, Instant.now()))
        snapshot.clean("tick done")
        var ics = ali.raw.get(href).body
        assertTrue(ics, ics.contains("STATUS:COMPLETED"))
        assertTrue(ics, ics.contains("PERCENT-COMPLETE:100"))
        assertTrue(ics, ics.contains("COMPLETED:"))
        assertTrue("a done to-do leaves the list", snapshot.build.items.none { it.title == title })

        // not done again: from the version the server has now
        val etag = ali.raw.get(href).header("ETag")
        snapshot = ali.agenda.submit(EditCommands.todoDone(item, false, Instant.now()).copy(baseVersion = etag))
        snapshot.clean("tick not done")
        ics = ali.raw.get(href).body
        assertTrue(ics, ics.contains("STATUS:NEEDS-ACTION"))
        assertFalse(ics, ics.contains("COMPLETED:"))
        assertFalse(ics, ics.contains("PERCENT-COMPLETE"))
        assertTrue(snapshot.build.items.any { it.title == title })
        row("edit-calendar to-do done and not done", true, "STATUS, COMPLETED and PERCENT-COMPLETE written and removed")

        // a title change on a to-do
        val again = snapshot.mine(title)
        snapshot = ali.agenda.submit(EditCommands.todo(again, TodoForm.of(again, UTC).copy(title = "$title edited", due = day(4)), UTC, agents)!!)
        snapshot.clean("edit todo")
        val edited = snapshot.mine("$title edited")
        assertEquals(day(4), edited.due)
        ali.agenda.submit(EditCommands.removeObject(edited))
        assertEquals(404, ali.raw.get(href).status)
    }

    // A to-do the way the Tasks page makes it (a start and a due date WITH a time): moving the start must not give the server a
    // to-do with mixed value types (sabre answers 415), and the time of day stays.
    @Test
    fun editCalendarTimedTodoKeepsItsTimesWhenTheStartMoves() {
        val ali = Session("ali")
        val uid = "r2-timed-$tag"
        val title = "Timed todo $tag"
        val start = day(2).toString().replace("-", "")
        val due = day(6).toString().replace("-", "")
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Throwaway//EN", "BEGIN:VTODO", "UID:$uid", "DTSTAMP:20261001T080000Z",
            "SUMMARY:$title", "DTSTART:${start}T090000Z", "DUE:${due}T170000Z", "STATUS:NEEDS-ACTION", "END:VTODO", "END:VCALENDAR"
        ).joinToString("\r\n") + "\r\n"
        val href = "remote.php/dav/calendars/ali/personal/$uid.ics"
        val made = ali.raw.request("PUT", href, ics, mapOf("Content-Type" to "text/calendar; charset=utf-8", "If-None-Match" to "*"))
        assertTrue("the server takes a timed to-do: ${made.status}", made.status in 200..299)
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val item = snapshot.mine(title)
        val form = TodoForm.of(item, UTC)
        // the start moves one day later; the due date and its time stay
        snapshot = ali.agenda.submit(EditCommands.todo(item, form.copy(start = form.start!!.plusDays(1)), UTC, agents)!!)
        snapshot.clean("timed to-do, start moved")
        var text = ali.raw.get(href).body
        assertTrue(text, text.contains("DTSTART:${day(3).toString().replace("-", "")}T090000Z"))
        assertTrue(text, text.contains("DUE:${due}T170000Z"))
        assertFalse(text, text.contains("VALUE=DATE"))
        row("edit-calendar timed to-do start", true, "DTSTART moved a day with its time; DUE unchanged; no mixed value types")

        // a new due day on a to-do with a timed start: the due keeps the start's value type
        val again = snapshot.mine(title)
        val later = TodoForm.of(again, UTC)
        snapshot = ali.agenda.submit(EditCommands.todo(again, later.copy(due = later.due!!.plusDays(1), dueTime = null), UTC, agents)!!)
        snapshot.clean("timed to-do, due day moved")
        text = ali.raw.get(href).body
        assertFalse(text, text.contains("VALUE=DATE"))
        assertTrue(text, text.contains("DUE:${day(7).toString().replace("-", "")}T"))
        ali.agenda.submit(EditCommands.removeObject(snapshot.mine(title)))
        assertEquals(404, ali.raw.get(href).status)
        row("edit-calendar timed to-do due", true, "DUE moved as a date and time, never as a bare date")
    }

    // ---- edit-gantt -------------------------------------------------------------------------------------------------------
    @Test
    fun editGanttDatesPercentDoneAndUndo() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val aliResource = seed.resource("Ali")
        val a = mara.createTask(project, "Edit task A $tag", day(1).toString(), day(3).toString(), listOf(aliResource to 4.0))
        val b = mara.createTask(project, "Edit task B $tag", day(4).toString(), day(5).toString(), listOf(aliResource to 2.0))
        // B follows A: moving A may move B
        val dependency = JsonObject()
        dependency.add("dependencies", JsonArray().also {
            val d = JsonObject()
            d.addProperty("predecessor_id", "t-$a")
            d.addProperty("dependency_type", "EA")
            d.addProperty("lag_days", 0)
            it.add(d)
        })
        val linked = mara.json("PUT", "${gantt}tasks/$b?format=json", dependency)
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        var item = snapshot.mine("Edit task A $tag")

        // the end date: written with its time of day; NoGantt says what it moved; Undo takes it back
        snapshot = ali.agenda.submit(EditCommands.task(item, TaskForm.of(item).copy(end = day(7)), agents).single())
        snapshot.clean("end date")
        assertTrue(mara.task(project, a).get("end_date").asString, mara.task(project, a).get("end_date").asString.startsWith(day(7).toString()))
        val notice = snapshot.edit.notice
        assertNotNull(notice)
        assertTrue("NoGantt gives an id for Undo", notice!!.undoOp.isNotEmpty())
        row("edit-gantt end date", true, "end date written; also moved ${notice.alsoMoved.size}; dependency link answered ${linked.status}")
        snapshot = ali.agenda.undo(notice.undoOp)
        assertTrue(mara.task(project, a).get("end_date").asString, mara.task(project, a).get("end_date").asString.startsWith(day(3).toString()))
        assertTrue(snapshot.edit.notice!!.undone)
        row("edit-gantt undo", true, "the end date is back")

        // percent done and done
        item = snapshot.mine("Edit task A $tag")
        snapshot = ali.agenda.submit(EditCommands.task(item, TaskForm.of(item).copy(percent = 60), agents).single())
        snapshot.clean("percent")
        assertEquals(60, mara.task(project, a).get("manual_progress").asInt)
        item = snapshot.mine("Edit task A $tag")
        snapshot = ali.agenda.submit(EditCommands.taskDone(item))
        snapshot.clean("done")
        val done = mara.task(project, a)
        assertEquals("done", done.get("status").asString)
        assertEquals(100, done.get("manual_progress").asInt)
        assertTrue("a done task leaves the list", snapshot.build.items.none { it.title == "Edit task A $tag" && it.workKind != WorkKind.TEAM })
        row("edit-gantt percent and done", true, "manual_progress 60, then status done and 100")
    }

    private fun rowsOf(task: JsonObject): List<Pair<Int, Double?>> = task.getAsJsonArray("resources").map {
        val o = it.asJsonObject
        o.get("resource_id").asInt to (o.get("work")?.takeIf { w -> !w.isJsonNull }?.asDouble)
    }

    @Test
    fun editGanttMyAgentSelfToAgentAndBack() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val aliResource = seed.resource("Ali")
        val writerBot = seed.resource("$robot Writer-Bot (Ali)")
        val c = mara.createTask(project, "Edit agent $tag", day(1).toString(), day(2).toString(), listOf(aliResource to 4.0))
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        var item = snapshot.mine("Edit agent $tag")
        assertTrue(item.agent.isSelf)
        // the choices say Writer-Bot can be chosen on this task
        val choices = snapshot.analysis.ganttChoices.getValue(item.key)
        assertTrue(choices.toString(), choices.first { it.agentId == "AG-0001" }.enabled)

        snapshot = ali.agenda.submit(EditCommands.task(item, TaskForm.of(item).copy(agentId = "AG-0001"), agents).single())
        snapshot.clean("agent on")
        assertFalse("the server's answer matched what was sent", snapshot.edit.notice!!.checkInHome)
        var rows = rowsOf(mara.task(project, c))
        assertEquals(listOf(aliResource to 4.0, writerBot to null), rows)
        item = snapshot.mine("Edit agent $tag")
        assertEquals("Writer-Bot", item.agent.name)
        assertEquals(4.0, item.myWorkHours, 0.0001)

        snapshot = ali.agenda.submit(EditCommands.task(item, TaskForm.of(item).copy(agentId = EditKeys.SELF), agents).single())
        snapshot.clean("agent off")
        rows = rowsOf(mara.task(project, c))
        assertEquals(listOf(aliResource to 4.0), rows)
        assertTrue(snapshot.mine("Edit agent $tag").agent.isSelf)
        row("edit-gantt my agent", true, "Self to Writer-Bot to Self; my own row and its work unchanged")
    }

    @Test
    fun editGanttTheRefusalsSendNothing() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val aliResource = seed.resource("Ali")
        val writerBot = seed.resource("$robot Writer-Bot (Ali)")
        val four = mara.createTask(
            project, "Edit four $tag", day(1).toString(), day(2).toString(),
            listOf(aliResource to 1.0, seed.resource("Sara") to 1.0, seed.resource("Temp worker") to 1.0, seed.resource("Designer") to 1.0)
        )
        val work = mara.createTask(project, "Edit work $tag", day(1).toString(), day(2).toString(), listOf(aliResource to 2.0, writerBot to 6.0))
        val plain = mara.createTask(project, "Edit unknown $tag", day(1).toString(), day(2).toString(), listOf(aliResource to 2.0))
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents

        fun refused(title: String, taskId: Int, agentId: String, reason: ConflictReason) {
            val item = snapshot.mine(title)
            val before = rowsOf(mara.task(project, taskId))
            snapshot = ali.agenda.submit(EditCommands.task(item, TaskForm.of(item).copy(agentId = agentId), agents).single())
            val conflict = snapshot.edit.conflicts.single()
            assertEquals(conflict.detail, reason, conflict.reason)
            assertEquals("$title: the server is as it was", before, rowsOf(mara.task(project, taskId)))
            snapshot = ali.agenda.resolveConflict(conflict.id, applyAgain = false)
            row("edit-gantt refusal $reason", true, "nothing changed on the server")
        }
        refused("Edit four $tag", four, "AG-0001", ConflictReason.TOO_MANY_PEOPLE)
        refused("Edit work $tag", work, EditKeys.SELF, ConflictReason.WORK_WOULD_CHANGE)
        // a retired agent has no planning person that Ali can see
        refused("Edit unknown $tag", plain, "AG-0004", ConflictReason.AGENT_NOT_IN_PROJECT)
        assertTrue(snapshot.edit.conflicts.isEmpty())
    }

    // ---- edit-deck --------------------------------------------------------------------------------------------------------
    @Test
    fun editDeckDueDateDoneAndAgentLabel() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val board = seed.board
        val stack = mara.stackId(board)
        val a = mara.createCard(board, stack, "Edit card A $tag", day(2).toString() + "T00:00:00+00:00", "Keep this text", day(0).toString() + "T00:00:00+00:00", "ali")
        val b = mara.createCard(board, stack, "Edit card B $tag", day(2).toString() + "T00:00:00+00:00", "Keep this too", null, "ali")
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        var item = snapshot.mine("Edit card A $tag")

        // the due date: a full update built from the fresh card; nothing else is cleared
        snapshot = ali.agenda.submit(EditCommands.card(item, CardForm.of(item).copy(due = day(5)), agents).single())
        snapshot.clean("due")
        var card = ali.raw.card(board, stack, a)
        assertTrue(card.toString(), card.get("duedate").asString.startsWith(day(5).toString()))
        assertEquals("Keep this text", card.get("description").asString)
        assertTrue(card.toString(), card.get("startdate").asString.startsWith(day(0).toString()))
        assertTrue("not done", card.get("done") == null || card.get("done").isJsonNull)
        assertEquals("Edit card A $tag", card.get("title").asString)
        row("edit-deck due date", true, "due date written; description and start date unchanged")

        // done
        item = snapshot.mine("Edit card A $tag")
        snapshot = ali.agenda.submit(EditCommands.cardDone(item))
        snapshot.clean("done")
        card = ali.raw.card(board, stack, a)
        assertTrue(card.toString(), card.has("done") && !card.get("done").isJsonNull)
        assertEquals("Keep this text", card.get("description").asString)
        assertTrue(snapshot.build.items.none { it.title == "Edit card A $tag" })
        row("edit-deck done", true, "done written; description unchanged")

        // my agent label: on, and off again
        item = snapshot.mine("Edit card B $tag")
        snapshot = ali.agenda.submit(EditCommands.card(item, CardForm.of(item).copy(agentId = "AG-0001"), agents).single())
        snapshot.clean("label on")
        card = ali.raw.card(board, stack, b)
        assertEquals(listOf("$robot Writer-Bot"), card.getAsJsonArray("labels").map { it.asJsonObject.get("title").asString })
        assertEquals("Keep this too", card.get("description").asString)
        item = snapshot.mine("Edit card B $tag")
        assertEquals("Writer-Bot", item.agent.name)
        snapshot = ali.agenda.submit(EditCommands.card(item, CardForm.of(item).copy(agentId = EditKeys.SELF), agents).single())
        snapshot.clean("label off")
        assertEquals(0, ali.raw.card(board, stack, b).getAsJsonArray("labels").size())
        assertTrue(snapshot.mine("Edit card B $tag").agent.isSelf)
        row("edit-deck agent label", true, "label put on and taken off")

        // a label that the board does not have, and Ali may only edit: refused, the board is as it was
        val labelsBefore = RawServer("mara").get("${deck}boards/$board").json().asJsonObject.getAsJsonArray("labels").size()
        item = snapshot.mine("Edit card B $tag")
        snapshot = ali.agenda.submit(EditCommands.card(item, CardForm.of(item).copy(agentId = "AG-0003"), agents).single())
        assertEquals(ConflictReason.LABEL_NEEDS_MANAGE, snapshot.edit.conflicts.single().reason)
        assertEquals(labelsBefore, RawServer("mara").get("${deck}boards/$board").json().asJsonObject.getAsJsonArray("labels").size())
        ali.agenda.resolveConflict(snapshot.edit.conflicts.single().id, applyAgain = false)
        row("edit-deck label refused", true, "an edit-only member can not make a label")
    }

    @Test
    fun editDeckAMissingLabelIsCreatedOnABoardThePersonManages() {
        val seed = Seed.load()
        val mara = Session("mara")
        val board = seed.board
        val stack = mara.raw.stackId(board)
        val card = mara.raw.createCard(board, stack, "Edit manage $tag", day(2).toString() + "T00:00:00+00:00", "x", null, "mara")
        var snapshot = mara.refresh()
        val agents = snapshot.build.myAgents
        val item = snapshot.mine("Edit manage $tag")
        snapshot = mara.agenda.submit(EditCommands.card(item, CardForm.of(item).copy(agentId = "AG-0006"), agents).single())
        snapshot.clean("label created")
        val titles = mara.raw.get("${deck}boards/$board").json().asJsonObject.getAsJsonArray("labels").map { it.asJsonObject.get("title").asString }
        assertTrue(titles.toString(), "$robot Plan-Bot" in titles)
        val onCard = mara.raw.card(board, stack, card).getAsJsonArray("labels").map { it.asJsonObject.get("title").asString }
        assertEquals(listOf("$robot Plan-Bot"), onCard)
        row("edit-deck label created", true, "the label was made on the board and put on the card")
    }

    // ---- conflict ---------------------------------------------------------------------------------------------------------
    @Test
    fun conflictAnObjectChangedMeanwhileKeepsTheOtherChangeAndApplyAgainKeepsBoth() {
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val work = snapshot.build.writableCalendars.first { it.uri == "work" }
        val title = "Edit conflict $tag"
        snapshot = ali.agenda.submit(EditCommands.newEvent(work, EventForm(title, false, day(2), LocalTime.of(9, 0), day(2), LocalTime.of(10, 0), "", EditKeys.SELF), UTC, agents))
        snapshot.clean("create")
        val stale = snapshot.mine(title)

        // somebody else saves the same event (the calendar page, a phone) after Ali opened it
        val other = RawServer("ali")
        val current = other.get(stale.objectHref)
        val theirs = current.body.replace("SUMMARY:$title", "SUMMARY:$title (theirs)")
        val saved = other.request("PUT", stale.objectHref, theirs, mapOf("If-Match" to current.header("ETag"), "Content-Type" to "text/calendar; charset=utf-8"))
        assertTrue("the other change was saved: ${saved.status}", saved.status in 200..299)

        snapshot = ali.agenda.submit(EditCommands.event(stale, EventForm.of(stale, UTC).copy(description = "Mine"), UTC, agents)!!)
        val conflict = snapshot.edit.conflicts.single()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, conflict.reason)
        var ics = ali.raw.get(stale.objectHref).body
        assertTrue("the other change wins", ics.contains("SUMMARY:$title (theirs)"))
        assertFalse(ics, ics.contains("DESCRIPTION:Mine"))
        assertTrue("the row shows the other change", snapshot.build.items.any { it.title == "$title (theirs)" })
        row("conflict calendar", true, "the other change stayed; nothing was overwritten")

        snapshot = ali.agenda.resolveConflict(conflict.id, applyAgain = true)
        snapshot.clean("apply again")
        ics = ali.raw.get(stale.objectHref).body
        assertTrue(ics, ics.contains("SUMMARY:$title (theirs)"))
        assertTrue(ics, ics.contains("DESCRIPTION:Mine"))
        row("conflict apply again", true, "both changes are on the server")
        ali.agenda.submit(EditCommands.removeObject(snapshot.mine("$title (theirs)")))
    }

    @Test
    fun conflictANoGanttTaskChangedMeanwhile() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val id = mara.createTask(project, "Edit race $tag", day(1).toString(), day(2).toString(), listOf(seed.resource("Ali") to 2.0))
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val stale = snapshot.mine("Edit race $tag")
        val rename = JsonObject()
        rename.addProperty("title", "Edit race $tag (theirs)")
        assertEquals(200, mara.json("PUT", "${gantt}tasks/$id?format=json", rename).status)

        snapshot = ali.agenda.submit(EditCommands.task(stale, TaskForm.of(stale).copy(end = day(6)), agents).single())
        val conflict = snapshot.edit.conflicts.single()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, conflict.reason)
        assertTrue(mara.task(project, id).get("end_date").asString.startsWith(day(2).toString()))
        snapshot = ali.agenda.resolveConflict(conflict.id, applyAgain = true)
        snapshot.clean("apply again")
        val task = mara.task(project, id)
        assertEquals("Edit race $tag (theirs)", task.get("title").asString)
        assertTrue(task.get("end_date").asString.startsWith(day(6).toString()))
        row("conflict gantt", true, "the other change stayed; apply again kept both")
    }

    @Test
    fun conflictACardChangedMeanwhile() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val board = seed.board
        val stack = mara.stackId(board)
        val id = mara.createCard(board, stack, "Edit card race $tag", day(2).toString() + "T00:00:00+00:00", "d", null, "ali")
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val agents = snapshot.build.myAgents
        val stale = snapshot.mine("Edit card race $tag")
        // the manager renames the card (a full update, as Deck's own page sends it)
        val card = mara.card(board, stack, id)
        val rename = JsonObject()
        rename.addProperty("title", "Edit card race $tag (theirs)")
        rename.addProperty("type", "plain")
        rename.addProperty("owner", "mara")
        rename.addProperty("description", "d")
        rename.addProperty("order", card.get("order").asInt)
        rename.addProperty("duedate", card.get("duedate").asString)
        assertEquals(200, mara.json("PUT", "${deck}boards/$board/stacks/$stack/cards/$id", rename).status)

        snapshot = ali.agenda.submit(EditCommands.card(stale, CardForm.of(stale).copy(due = day(6)), agents).single())
        val conflict = snapshot.edit.conflicts.single()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, conflict.reason)
        assertTrue(mara.card(board, stack, id).get("duedate").asString.startsWith(day(2).toString()))
        snapshot = ali.agenda.resolveConflict(conflict.id, applyAgain = true)
        snapshot.clean("apply again")
        val now = mara.card(board, stack, id)
        assertEquals("Edit card race $tag (theirs)", now.get("title").asString)
        assertTrue(now.get("duedate").asString.startsWith(day(6).toString()))
        assertNull(snapshot.edit.notice?.takeIf { it.conflict })
        row("conflict deck", true, "the other change stayed; apply again kept both")
    }
}
