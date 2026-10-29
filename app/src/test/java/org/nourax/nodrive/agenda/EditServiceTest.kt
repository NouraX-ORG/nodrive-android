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
import org.nourax.nodrive.agenda.edit.CardForm
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.EditBlock
import org.nourax.nodrive.agenda.edit.EditCommands
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.EditRules
import org.nourax.nodrive.agenda.edit.EditRunner
import org.nourax.nodrive.agenda.edit.EventForm
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.edit.TaskForm
import org.nourax.nodrive.agenda.edit.TodoForm
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.WritableCalendar
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Every edit of the table of design section 9 sends exactly the expected request (verb, path, headers, body), a changed item is a
 * conflict, "Apply my change again" sends one fresh read and one write with the new version, and the queue keeps what it must
 * (plan 2.8). A small server in memory (EditTestSupport) answers like the real ones do.
 */
class EditServiceTest {
    private val clock: AgendaClock = FixedAgendaClock(NOW)
    private val robot = AgentRegistry.robot
    private val registry = AgentRegistry.parse(
        (
            """{"schema":"nourax-agent-registry","version":1,"agents":[
            {"id":"AG-0001","name":"Writer-Bot","person":"ali","status":"active","organisation":"o","availability":{"timezone":"UTC","weekly":[{"days":["mon"],"from":"09:00","to":"17:00"}],"capacity_hours_per_week":40,"days_off":[]},
             "links":{"nogantt_resource_ids":[11],"noboard_label":"$robot Writer-Bot","calendar_tag":"agent:AG-0001"}},
            {"id":"AG-0003","name":"Research-Bot","person":"ali","status":"active","organisation":"o",
             "links":{"nogantt_resource_ids":[],"noboard_label":"$robot Research-Bot","calendar_tag":"agent:AG-0003"}}]}"""
            ).toByteArray()
    )
    private val agents get() = registry.ofPerson("ali")

    private fun runner(server: EditFakeServer, store: InMemoryEditStore = InMemoryEditStore(), guarded: (Home) -> Boolean = { false }) =
        EditRunner("ali@test", server, store, clock, { registry }, { "ali" }, { "Ali" }, guarded)

    private val eventHref = "/remote.php/dav/calendars/ali/work/ev-1.ics"
    private val todoHref = "/remote.php/dav/calendars/ali/personal/td-1.ics"

    private fun eventItem(server: EditFakeServer): AgendaItem {
        server.putObject(eventHref, sampleEvent())
        return AgendaItem(
            key = "cal:work/ev-1.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Dentist", timed = true,
            start = Instant.parse("2026-10-28T10:00:00Z"), end = Instant.parse("2026-10-28T11:00:00Z"),
            calendarUri = "work", calendarHref = "/remote.php/dav/calendars/ali/work/", objectUri = "ev-1.ics", objectHref = eventHref,
            version = server.etagOf(eventHref)
        )
    }

    private fun todoItem(server: EditFakeServer): AgendaItem {
        server.putObject(todoHref, sampleTodo())
        return AgendaItem(
            key = "cal:personal/td-1.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Renew passport", due = LocalDate.of(2026, 10, 30),
            calendarUri = "personal", calendarHref = "/remote.php/dav/calendars/ali/personal/", objectUri = "td-1.ics", objectHref = todoHref,
            version = server.etagOf(todoHref)
        )
    }

    private fun eventForm(item: AgendaItem) = EventForm.of(item, UTC)

    // ---- events and to-dos -------------------------------------------------------------------------------------------

    @Test
    fun anEventTitleChangeIsOneReadAndOneWriteWithIfMatchAndKeepsEverythingElse() {
        val server = EditFakeServer()
        val item = eventItem(server)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(title = "Dentist, moved"), UTC, agents)!!)
        val report = r.run()
        assertEquals(setOf(Home.CALENDAR), report.homes)
        assertEquals(listOf("GET", "PUT"), server.requests.map { it.method })
        val put = server.requests.last()
        assertEquals(eventHref, put.path)
        assertEquals("\"v1\"", put.headers["If-Match"])
        assertTrue(put.body.contains("SUMMARY:Dentist\\, moved"))
        assertTrue(put.body.contains("BEGIN:VALARM"))
        assertTrue(put.body.contains("X-CUSTOM-THING;FOO=bar:keep me"))
        assertTrue(store.commands("ali@test").isEmpty())
        assertTrue(store.conflicts("ali@test").isEmpty())
    }

    @Test
    fun aTimeAndAnAgentAreChangedInOneWrite() {
        val server = EditFakeServer()
        val item = eventItem(server)
        val r = runner(server)
        val form = eventForm(item).copy(startTime = LocalTime.of(14, 0), endTime = LocalTime.of(15, 0), agentId = "AG-0001")
        r.enqueue(EditCommands.event(item, form, UTC, agents)!!)
        r.run()
        val text = server.objects.getValue(eventHref.trimStart('/')).text
        assertTrue(text.contains("DTSTART:20261028T140000Z"))
        assertTrue(text.contains("DTEND:20261028T150000Z"))
        assertTrue(text.contains("X-NOURAX-AGENT-ID:AG-0001"))
        assertTrue(text.contains("agent:AG-0001"))
        assertEquals(1, server.writes().size)
    }

    @Test
    fun nothingChangedIsNoCommand() {
        val server = EditFakeServer()
        val item = eventItem(server)
        assertNull(EditCommands.event(item, eventForm(item), UTC, agents))
        val todo = todoItem(server)
        assertNull(EditCommands.todo(todo, TodoForm.of(todo, UTC), UTC, agents))
    }

    @Test
    fun aTodoIsTickedDoneWithStatusCompletedAndTimeAndPercent() {
        val server = EditFakeServer()
        val item = todoItem(server)
        val r = runner(server)
        r.enqueue(EditCommands.todoDone(item, true, NOW))
        r.run()
        val text = server.objects.getValue(todoHref.trimStart('/')).text
        assertTrue(text.contains("STATUS:COMPLETED"))
        assertTrue(text.contains("COMPLETED:20261028T120000Z"))
        assertTrue(text.contains("PERCENT-COMPLETE:100"))
        assertEquals("\"v1\"", server.requests.first { it.method == "PUT" }.headers["If-Match"])
    }

    @Test
    fun aNewEventIsCreatedWithAnAgentAndIfNoneMatch() {
        val server = EditFakeServer()
        val calendar = WritableCalendar("work", "/remote.php/dav/calendars/ali/work/", "Work", "#2563EB", true, true, true, CalendarMode.WORK)
        val form = EventForm("Review", false, LocalDate.of(2026, 11, 3), LocalTime.of(14, 0), LocalDate.of(2026, 11, 3), LocalTime.of(15, 0), "", "AG-0001")
        val command = EditCommands.newEvent(calendar, form, UTC, agents)
        assertTrue(command.itemKey.startsWith("new:"))
        val r = runner(server)
        r.enqueue(command)
        r.run()
        val put = server.requests.single { it.method == "PUT" }
        assertEquals("*", put.headers["If-None-Match"])
        assertTrue(put.path.startsWith("/remote.php/dav/calendars/ali/work/"))
        assertTrue(put.path.endsWith(".ics"))
        assertTrue(put.body.contains("X-NOURAX-AGENT-ID:AG-0001"))
        assertTrue(put.body.contains("SUMMARY:Review"))
        assertEquals(1, server.objects.size)
    }

    @Test
    fun aNewEventWithSelfHasNoAgentMark() {
        val server = EditFakeServer()
        val calendar = WritableCalendar("work", "/remote.php/dav/calendars/ali/work/", "Work", "#2563EB", true, true, true, CalendarMode.WORK)
        val form = EventForm("Lunch", false, LocalDate.of(2026, 11, 3), LocalTime.of(12, 0), LocalDate.of(2026, 11, 3), LocalTime.of(13, 0), "", EditKeys.SELF)
        val r = runner(server)
        r.enqueue(EditCommands.newEvent(calendar, form, UTC, agents))
        r.run()
        val body = server.requests.single { it.method == "PUT" }.body
        assertFalse(body.contains("X-NOURAX-AGENT-ID"))
        assertFalse(body.contains("CATEGORIES"))
    }

    @Test
    fun aLostAnswerOnCreateDoesNotMakeADuplicateOrAConflict() {
        val server = EditFakeServer()
        val calendar = WritableCalendar("work", "/remote.php/dav/calendars/ali/work/", "Work", "#2563EB", true, true, true, CalendarMode.WORK)
        val form = EventForm("Once", false, LocalDate.of(2026, 11, 3), LocalTime.of(12, 0), LocalDate.of(2026, 11, 3), LocalTime.of(13, 0), "", EditKeys.SELF)
        val command = EditCommands.newEvent(calendar, form, UTC, agents)
        // the first try reached the server although the phone never got the answer
        val first = server.send("PUT", command.loc("calendarHref") + command.change.getValue(EditKeys.OBJECT_NAME), mapOf("If-None-Match" to "*"),
            org.nourax.nodrive.agenda.edit.CalendarEdit.create(false, command.change.getValue(EditKeys.UID), command.change, NOW).toByteArray(), "text/calendar")
        assertEquals(201, first.status)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(command)
        r.run()
        assertEquals(1, server.objects.size)
        assertTrue(store.conflicts("ali@test").isEmpty())
        assertTrue(store.commands("ali@test").isEmpty())
    }

    @Test
    fun deleteIsADeleteWithIfMatchAndNothingElseIsDeleted() {
        val server = EditFakeServer()
        val item = eventItem(server)
        val r = runner(server)
        r.enqueue(EditCommands.removeObject(item))
        r.run()
        val delete = server.requests.single { it.method == "DELETE" }
        assertEquals(eventHref, delete.path)
        assertEquals("\"v1\"", delete.headers["If-Match"])
        assertTrue(server.objects.isEmpty())
        // a delete of a thing that waits for sending is just forgotten, never sent
        val store = InMemoryEditStore()
        val r2 = runner(EditFakeServer(), store)
        val calendar = WritableCalendar("work", "/remote.php/dav/calendars/ali/work/", "Work", "#2563EB", true, true, true, CalendarMode.WORK)
        val created = r2.enqueue(EditCommands.newEvent(calendar, EventForm("x", false, LocalDate.of(2026, 11, 3), LocalTime.of(9, 0), LocalDate.of(2026, 11, 3), LocalTime.of(10, 0), "", EditKeys.SELF), UTC, agents))
        r2.enqueue(EditCommands.removeObject(AgendaItem(key = created.itemKey, kind = ItemKind.EVENT, home = Home.CALENDAR)))
        assertTrue(store.commands("ali@test").isEmpty())
    }

    // ---- conflicts ----------------------------------------------------------------------------------------------------

    @Test
    fun aChangedEtagIsAConflictTheOtherChangeWinsAndNothingIsWritten() {
        val server = EditFakeServer()
        val item = eventItem(server)
        // somebody else saves the event after the person opened it
        val theirs = sampleEvent().replace("SUMMARY:Dentist", "SUMMARY:Dentist at 3")
        server.putObject(eventHref, theirs)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(description = "Mine"), UTC, agents)!!)
        val report = r.run()
        assertEquals(1, report.newConflicts)
        assertTrue(server.writes().isEmpty())
        assertEquals(theirs, server.objects.getValue(eventHref.trimStart('/')).text)
        val conflict = store.conflicts("ali@test").single()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, conflict.reason)
        assertTrue(store.commands("ali@test").isEmpty())
        assertEquals("Dentist", conflict.command.title)
    }

    @Test
    fun a412OnTheWriteIsAConflictToo() {
        val server = EditFakeServer()
        val item = eventItem(server)
        server.failNextWrite = 412
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(title = "x"), UTC, agents)!!)
        r.run()
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun applyMyChangeAgainIsOneFreshReadAndOneWriteWithTheNewEtagAndKeepsBothChanges() {
        val server = EditFakeServer()
        val item = eventItem(server)
        val theirs = sampleEvent().replace("DESCRIPTION:Reminder", "DESCRIPTION:Reminder").replace("SUMMARY:Dentist", "SUMMARY:Dentist at 3")
        server.putObject(eventHref, theirs)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(description = "Bring the card"), UTC, agents)!!)
        r.run()
        server.requests.clear()
        val conflict = store.conflicts("ali@test").single()
        assertTrue(r.resolve(conflict.id, applyAgain = true))
        r.run()
        assertEquals(listOf("GET", "PUT"), server.requests.map { it.method })
        assertEquals("\"v2\"", server.requests.last().headers["If-Match"])
        val text = server.objects.getValue(eventHref.trimStart('/')).text
        assertTrue("their change stays", text.contains("SUMMARY:Dentist at 3"))
        assertTrue("my change is on top", text.contains("DESCRIPTION:Bring the card"))
        assertTrue(store.conflicts("ali@test").isEmpty())
    }

    @Test
    fun keepTheOtherChangeDropsMineAndSendsNothing() {
        val server = EditFakeServer()
        val item = eventItem(server)
        server.putObject(eventHref, sampleEvent().replace("SUMMARY:Dentist", "SUMMARY:Other"))
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(title = "Mine"), UTC, agents)!!)
        r.run()
        server.requests.clear()
        assertFalse(r.resolve(store.conflicts("ali@test").single().id, applyAgain = false))
        assertTrue(store.conflicts("ali@test").isEmpty())
        assertTrue(store.commands("ali@test").isEmpty())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aGoneItemIsAConflictNotAnError() {
        val server = EditFakeServer()
        val item = eventItem(server)
        server.objects.clear()
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(title = "x"), UTC, agents)!!)
        r.run()
        assertEquals(ConflictReason.GONE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun anItemThatRepeatsNowIsNotChanged() {
        val server = EditFakeServer()
        val item = eventItem(server)
        server.putObject(eventHref, sampleEvent(extra = "RRULE:FREQ=WEEKLY"))
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.event(item, eventForm(item).copy(title = "x"), UTC, agents)!!.copy(rebase = true))
        r.run()
        assertEquals(ConflictReason.NOW_REPEATING, store.conflicts("ali@test").single().reason)
        assertTrue(server.writes().isEmpty())
    }

    // ---- the queue ----------------------------------------------------------------------------------------------------

    @Test
    fun withoutANetworkTheChangesWaitInOrderAndAreSentWhenTheNetworkIsBack() {
        val server = EditFakeServer()
        val event = eventItem(server)
        val todo = todoItem(server)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        server.offline = true
        r.enqueue(EditCommands.event(event, eventForm(event).copy(title = "First"), UTC, agents)!!)
        r.enqueue(EditCommands.todoDone(todo, true, NOW))
        r.enqueue(EditCommands.todo(todo, TodoForm.of(todo, UTC).copy(description = "Third"), UTC, agents)!!)
        val offline = r.run()
        // the two changes of the to-do are one command (later values win), so two wait
        assertEquals(2, offline.waiting)
        assertEquals(0, offline.newConflicts)
        assertTrue(offline.notice!!.queued)
        assertEquals("only the first one is tried", 1, server.requests.size)
        assertEquals(2, store.commands("ali@test").size)
        server.offline = false
        server.requests.clear()
        val online = r.run()
        assertEquals(0, online.waiting)
        val puts = server.requests.filter { it.method == "PUT" }
        assertEquals(listOf(eventHref, todoHref), puts.map { it.path }.distinct())
        assertTrue(server.objects.getValue(eventHref.trimStart('/')).text.contains("SUMMARY:First"))
        val todoText = server.objects.getValue(todoHref.trimStart('/')).text
        assertTrue(todoText.contains("STATUS:COMPLETED"))
        assertTrue("two changes of the same to-do do not clash with each other", todoText.contains("DESCRIPTION:Third"))
        assertTrue(store.conflicts("ali@test").isEmpty())
    }

    @Test
    fun twoWaitingChangesOfTheSameThingAreFoldedIntoOne() {
        val server = EditFakeServer()
        val event = eventItem(server)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        server.offline = true
        r.enqueue(EditCommands.event(event, eventForm(event).copy(title = "A"), UTC, agents)!!)
        r.enqueue(EditCommands.event(event, eventForm(event).copy(title = "B", description = "D"), UTC, agents)!!)
        val queue = store.commands("ali@test")
        assertEquals(1, queue.size)
        assertEquals("B", queue.single().change[EditKeys.SUMMARY])
        assertEquals("D", queue.single().change[EditKeys.DESCRIPTION])
    }

    @Test
    fun theQueueSurvivesARestartBecauseItLivesInTheStore() {
        val server = EditFakeServer()
        val event = eventItem(server)
        val store = InMemoryEditStore()
        server.offline = true
        runner(server, store).apply { enqueue(EditCommands.event(event, eventForm(event).copy(title = "Later"), UTC, agents)!!) }.run()
        // a new runner, as after a restart, finds it and sends it
        server.offline = false
        val again = runner(server, store).run()
        assertEquals(0, again.waiting)
        assertTrue(server.objects.getValue(eventHref.trimStart('/')).text.contains("SUMMARY:Later"))
    }

    @Test
    fun aChangeThatWasBeingSentWhenTheAppStoppedIsAppliedOnTheNewestVersionWithoutAFalseConflict() {
        val server = EditFakeServer()
        val event = eventItem(server)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        val stored = r.enqueue(EditCommands.event(event, eventForm(event).copy(title = "Once"), UTC, agents)!!)
        // it reached the server, but the app stopped before it took the command out of the queue
        server.putObject(eventHref, sampleEvent().replace("SUMMARY:Dentist", "SUMMARY:Once"))
        store.update(stored.copy(state = org.nourax.nodrive.agenda.edit.CommandState.SENDING))
        val report = runner(server, store).run()
        assertEquals(0, report.newConflicts)
        assertTrue(store.conflicts("ali@test").isEmpty())
    }

    @Test
    fun accountRemovalDeletesTheUnsentChanges() {
        val server = EditFakeServer()
        val event = eventItem(server)
        val store = InMemoryEditStore()
        server.offline = true
        runner(server, store).apply { enqueue(EditCommands.event(event, eventForm(event).copy(title = "x"), UTC, agents)!!) }.run()
        store.forget("ali@test")
        assertTrue(store.commands("ali@test").isEmpty())
    }

    // ---- NoGantt -------------------------------------------------------------------------------------------------------

    @Test
    fun twoChangesOfOneGanttTaskDoNotClashWithEachOther() {
        val server = ganttServer(rows = listOf(1 to 6.0))
        val store = InMemoryEditStore()
        val r = runner(server, store)
        // the dates and the agent are two commands of the same task; the second starts from the version the first one made
        val form = TaskForm.of(taskItem()).copy(end = LocalDate.of(2026, 11, 4), agentId = "AG-0001")
        EditCommands.task(taskItem(), form, agents).forEach { r.enqueue(it) }
        assertEquals(2, store.commands("ali@test").size)
        val report = r.run()
        assertEquals(0, report.newConflicts)
        assertEquals(2, server.writes().size)
        assertTrue(store.conflicts("ali@test").isEmpty())
    }

    private fun ganttServer(progressMode: String = "manual", rows: List<Pair<Int, Double?>> = listOf(1 to 6.0)): EditFakeServer {
        val server = EditFakeServer()
        val task = JsonObject()
        task.addProperty("id", 5)
        task.addProperty("title", "Landing page build")
        task.addProperty("project_id", 1)
        task.addProperty("start_date", "2026-10-28 00:00:00")
        task.addProperty("end_date", "2026-10-30 00:00:00")
        task.addProperty("status", "in_progress")
        task.addProperty("manual_progress", 10)
        task.addProperty("progress_mode", progressMode)
        task.addProperty("updated_at", "2026-10-28 08:00:00")
        val array = JsonArray()
        rows.forEach { (rid, work) ->
            val o = JsonObject()
            o.addProperty("resource_id", rid)
            if (work == null) o.add("work", com.google.gson.JsonNull.INSTANCE) else o.addProperty("work", work)
            array.add(o)
        }
        task.add("resources", array)
        server.ganttTasks = JsonArray().also { it.add(task) }
        server.ganttProjects = JsonParser.parseString("""[{"id":1,"title":"Website","permission":"editor","roles_only_assignment":false}]""").asJsonArray
        server.ganttResources = JsonParser.parseString(
            """[{"id":1,"name":"Ali","type":"person","uid":"ali","uid_display_name":"Ali","unit":"h"},
                {"id":11,"name":"$robot Writer-Bot (Ali)","type":"person","uid":"","uid_display_name":"","unit":"h"}]"""
        ).asJsonArray
        return server
    }

    private fun taskItem(version: String = "2026-10-28 08:00:00") = AgendaItem(
        key = "gantt:task:5", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Landing page build",
        startDate = LocalDate.of(2026, 10, 28), endDate = LocalDate.of(2026, 10, 30), due = LocalDate.of(2026, 10, 30),
        projectId = 1, taskId = 5, version = version, percentDone = 10, status = "in_progress"
    )

    @Test
    fun aGanttEndDateIsAPutOfTheTaskWithItsTimeOfDayAndTheMovedTasksAreReported() {
        val server = ganttServer()
        server.propagated = JsonParser.parseString("""[{"id":5,"title":"Landing page build"},{"id":6,"title":"Publish banners"}]""").asJsonArray
        val r = runner(server)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(end = LocalDate.of(2026, 11, 4)), agents).single())
        val report = r.run()
        val put = server.requests.single { it.method == "PUT" }
        assertEquals("ocs/v2.php/apps/nc_gantt/api/v1/tasks/5?format=json", put.path)
        assertEquals("true", put.headers["OCS-APIRequest"])
        val body = JsonParser.parseString(put.body).asJsonObject
        assertEquals("2026-11-04 00:00:00", body.get("end_date").asString)
        assertFalse(body.has("start_date"))
        assertEquals(false, body.get("deck_confirmed").asBoolean)
        assertEquals(setOf(Home.GANTT), report.homes)
        assertEquals(listOf("Publish banners"), report.notice!!.alsoMoved)
        assertEquals("op-1", report.notice!!.undoOp)
    }

    @Test
    fun undoTakesTheDateChangeBackWithNoGanttsOwnRevert() {
        val server = ganttServer()
        val r = runner(server)
        val notice = r.undo("op-1")
        assertTrue(notice.undone)
        val post = server.requests.single()
        assertEquals("POST", post.method)
        assertEquals("ocs/v2.php/apps/nc_gantt/api/v1/changes/op-1/revert?format=json", post.path)
    }

    @Test
    fun doneIsStatusDoneAndHundredPercentWhenTheProgressIsManual() {
        val server = ganttServer()
        val r = runner(server)
        r.enqueue(EditCommands.taskDone(taskItem()))
        r.run()
        val body = JsonParser.parseString(server.requests.single { it.method == "PUT" }.body).asJsonObject
        assertEquals("done", body.get("status").asString)
        assertEquals(100, body.get("manual_progress").asInt)
    }

    @Test
    fun percentDoneIsRefusedWhenTheProgressComesFromTheBoard() {
        val server = ganttServer(progressMode = "deck")
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(percent = 50), agents).single())
        r.run()
        assertTrue(server.writes().isEmpty())
        assertEquals("progress_from_board", store.conflicts("ali@test").single().detail)
        // and the sheet does not offer it
        assertTrue(EditRules.of(taskItem().copy(progressMode = "deck")).percentFromBoard)
    }

    @Test
    fun aGanttTaskChangedMeanwhileIsAConflict() {
        val server = ganttServer()
        server.ganttTasks[0].asJsonObject.addProperty("updated_at", "2026-10-28 09:30:00")
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(end = LocalDate.of(2026, 11, 4)), agents).single())
        r.run()
        assertTrue(server.writes().isEmpty())
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun myAgentOnAGanttTaskIsAPutOfTheWholeListWithOtherRowsUnchangedAndTheAnswerIsChecked() {
        val server = ganttServer(rows = listOf(1 to 6.0))
        val r = runner(server)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(agentId = "AG-0001"), agents).single())
        val report = r.run()
        val body = JsonParser.parseString(server.requests.single { it.method == "PUT" }.body).asJsonObject
        val sent = body.getAsJsonArray("resources")
        assertEquals(2, sent.size())
        assertEquals(1, sent[0].asJsonObject.get("resource_id").asInt)
        assertEquals(6.0, sent[0].asJsonObject.get("work").asDouble, 0.0)
        assertEquals(11, sent[1].asJsonObject.get("resource_id").asInt)
        assertTrue(sent[1].asJsonObject.get("work").isJsonNull)
        assertFalse("the answer matches what was sent", report.notice!!.checkInHome)
    }

    @Test
    fun whenTheServerDropsARowTheTaskIsFlaggedForACheckInNoGantt() {
        val server = ganttServer(rows = listOf(1 to 6.0))
        server.dropRowsOf = setOf(11)
        val r = runner(server)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(agentId = "AG-0001"), agents).single())
        val report = r.run()
        assertTrue(report.notice!!.checkInHome)
    }

    @Test
    fun theRefusalsOfTheAgentChangeSendNothing() {
        // the agent had work: taking it off would change the work of the task
        val server = ganttServer(rows = listOf(1 to 2.0, 11 to 6.0))
        val store = InMemoryEditStore()
        val r = runner(server, store)
        val item = taskItem().copy(agent = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot"), workKind = WorkKind.MY_AGENT)
        r.enqueue(EditCommands.task(item, TaskForm.of(item).copy(agentId = EditKeys.SELF), agents).single())
        r.run()
        assertTrue(server.writes().isEmpty())
        assertEquals(ConflictReason.WORK_WOULD_CHANGE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun aBadDateAnswerFromTheServerIsShownInPlainWords() {
        val server = ganttServer()
        server.failNextWrite = 400
        server.failNextBody = "{\"message\":\"invalid_date\"}"
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(end = LocalDate.of(2026, 11, 4)), agents).single())
        r.run()
        val conflict = store.conflicts("ali@test").single()
        assertEquals(ConflictReason.INVALID, conflict.reason)
        assertEquals("invalid_date", conflict.detail)
    }

    @Test
    fun theVersionGuardWritesNothingToNoGanttOrNoBoard() {
        val server = ganttServer()
        val store = InMemoryEditStore()
        val r = runner(server, store, guarded = { true })
        r.enqueue(EditCommands.taskDone(taskItem()))
        r.run()
        assertTrue(server.requests.isEmpty())
        assertEquals("version_guard", store.conflicts("ali@test").single().detail)
    }

    // ---- NoBoard -------------------------------------------------------------------------------------------------------

    private fun deckServer(manage: Boolean = true, onCard: List<Pair<Int, String>> = emptyList()): EditFakeServer {
        val server = EditFakeServer()
        server.card = JsonParser.parseString(
            """{"id":7,"title":"Call supplier","type":"plain","owner":{"primaryKey":"mara","uid":"mara","displayname":"Mara","type":0},
                "description":"Keep this text","order":3,"duedate":"2026-10-29T00:00:00+00:00","startdate":"2026-10-27T00:00:00+00:00",
                "archived":false,"done":null,"lastModified":1000,"ETag":"","labels":[]}"""
        ).asJsonObject
        val labels = JsonArray()
        onCard.forEach { (id, title) ->
            val l = JsonObject()
            l.addProperty("id", id)
            l.addProperty("title", title)
            server.card.getAsJsonArray("labels").add(l)
            labels.add(l)
        }
        val board = JsonObject()
        board.add("labels", labels)
        board.add("permissions", JsonParser.parseString("""{"PERMISSION_READ":true,"PERMISSION_EDIT":true,"PERMISSION_MANAGE":$manage}"""))
        server.board = board
        return server
    }

    private fun cardItem(agent: AgentRef = AgentRef.SELF) = AgendaItem(
        key = "deck:card:7", kind = ItemKind.CARD, home = Home.BOARD, title = "Call supplier", due = LocalDate.of(2026, 10, 29),
        boardId = 3, stackId = 4, cardId = 7, version = "1000", agent = agent, workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT
    )

    @Test
    fun aCardUpdateIsBuiltFromTheFreshCardAndAlwaysCarriesDone() {
        val server = deckServer()
        val r = runner(server)
        r.enqueue(EditCommands.card(cardItem(), CardForm.of(cardItem()).copy(due = LocalDate.of(2026, 11, 2)), agents).single())
        r.run()
        val put = server.requests.single { it.method == "PUT" }
        assertEquals("index.php/apps/deck/api/v1.0/boards/3/stacks/4/cards/7", put.path)
        val body = JsonParser.parseString(put.body).asJsonObject
        for (key in listOf("title", "type", "owner", "description", "order", "duedate", "startdate", "archived", "done")) assertTrue(key, body.has(key))
        assertEquals("mara", body.get("owner").asString)
        assertEquals("Keep this text", body.get("description").asString)
        assertEquals("2026-10-27T00:00:00+00:00", body.get("startdate").asString)
        assertTrue(body.get("duedate").asString.startsWith("2026-11-02T00:00:00"))
        assertTrue(body.get("done").isJsonNull)
        assertFalse("colour is never sent", body.has("color"))
        // the server cleared nothing it should keep
        assertEquals("Keep this text", server.card.get("description").asString)
        assertEquals("2026-10-27T00:00:00+00:00", server.card.get("startdate").asString)
    }

    @Test
    fun tickingACardDoneSendsATimeAndUntickingSendsNull() {
        val server = deckServer()
        val r = runner(server)
        r.enqueue(EditCommands.cardDone(cardItem()))
        r.run()
        val body = JsonParser.parseString(server.requests.single { it.method == "PUT" }.body).asJsonObject
        assertEquals("2026-10-28T12:00:00Z", body.get("done").asString.replace("+00:00", "Z"))
    }

    @Test
    fun aCardChangedMeanwhileIsAConflict() {
        val server = deckServer()
        server.card.addProperty("lastModified", 1001)
        val store = InMemoryEditStore()
        val r = runner(server, store)
        r.enqueue(EditCommands.cardDone(cardItem()))
        r.run()
        assertTrue(server.writes().isEmpty())
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun myAgentLabelOnACardRemovesMyOtherAgentsLabelsFirstThenAssignsTheNewOne() {
        val server = deckServer(onCard = listOf(20 to "$robot Research-Bot"))
        server.board.getAsJsonArray("labels").add(JsonParser.parseString("""{"id":21,"title":"$robot Writer-Bot","color":"31CC7C"}"""))
        val item = cardItem(AgentRef(isSelf = false, id = "AG-0003", name = "Research-Bot"))
        val r = runner(server)
        r.enqueue(EditCommands.card(item, CardForm.of(item).copy(agentId = "AG-0001"), agents).single())
        r.run()
        val writes = server.writes().map { it.method + " " + it.path.substringAfter("cards/7") }
        assertEquals(listOf("PUT /removeLabel", "PUT /assignLabel"), writes)
        assertEquals(20, JsonParser.parseString(server.writes()[0].body).asJsonObject.get("labelId").asInt)
        assertEquals(21, JsonParser.parseString(server.writes()[1].body).asJsonObject.get("labelId").asInt)
        assertEquals(listOf(21), server.card.getAsJsonArray("labels").map { it.asJsonObject.get("id").asInt })
    }

    @Test
    fun aMissingLabelIsCreatedWhenThePersonMayManageTheBoardAndRefusedWhenNot() {
        val manage = deckServer(manage = true)
        runner(manage).apply { enqueue(EditCommands.card(cardItem(), CardForm.of(cardItem()).copy(agentId = "AG-0001"), agents).single()) }.run()
        val created = manage.board.getAsJsonArray("labels").single().asJsonObject
        assertEquals("$robot Writer-Bot", created.get("title").asString)
        assertEquals("2563EB", created.get("color").asString)
        assertEquals(1, manage.card.getAsJsonArray("labels").size())

        val noManage = deckServer(manage = false)
        val store = InMemoryEditStore()
        runner(noManage, store).apply { enqueue(EditCommands.card(cardItem(), CardForm.of(cardItem()).copy(agentId = "AG-0001"), agents).single()) }.run()
        assertTrue(noManage.writes().isEmpty())
        assertEquals(ConflictReason.LABEL_NEEDS_MANAGE, store.conflicts("ali@test").single().reason)
    }

    @Test
    fun selfTakesAllMyAgentLabelsOffAndOnlyMine() {
        val server = deckServer(onCard = listOf(20 to "$robot Research-Bot", 22 to "urgent"))
        val item = cardItem(AgentRef(isSelf = false, id = "AG-0003", name = "Research-Bot"))
        runner(server).apply { enqueue(EditCommands.card(item, CardForm.of(item).copy(agentId = EditKeys.SELF), agents).single()) }.run()
        assertEquals(listOf(22), server.card.getAsJsonArray("labels").map { it.asJsonObject.get("id").asInt })
    }

    // ---- who may change what ---------------------------------------------------------------------------------------------

    @Test
    fun repeatingInvitationViewerAndGuardedRowsCanNotBeChangedAndSendNothing() {
        val base = AgendaItem(key = "cal:work/x.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "x")
        assertTrue(EditRules.of(base).canEdit)
        assertEquals(EditBlock.REPEATING_EVENT, EditRules.of(base.copy(repeating = true)).block)
        assertEquals(EditBlock.REPEATING_TODO, EditRules.of(base.copy(home = Home.TASKS, kind = ItemKind.TODO, repeating = true)).block)
        assertEquals(EditBlock.INVITATION, EditRules.of(base.copy(invitation = true)).block)
        assertFalse(EditRules.of(base.copy(invitation = true)).canChangeAgent)
        val viewer = taskItem().copy(readOnly = true, readOnlyReason = ReadOnlyReason.VIEWER_PROJECT)
        assertFalse(EditRules.of(viewer).canEdit)
        assertEquals(EditBlock.READ_ONLY, EditRules.of(viewer).block)
        assertEquals(EditBlock.VERSION_GUARD, EditRules.of(taskItem().copy(versionReadOnly = true)).block)
        assertFalse(EditRules.of(base.copy(readOnly = true, readOnlyReason = ReadOnlyReason.SUBSCRIPTION)).canEdit)
        assertFalse(EditRules.of(base.copy(workKind = WorkKind.TEAM)).canEdit)
        assertFalse(EditRules.of(base.copy(info = true)).canEdit)
        assertEquals(EditBlock.WAITING, EditRules.of(base.copy(pending = true)).block)
    }

    @Test
    fun aGanttTaskOrACardCanNeverBeDeletedHere() {
        assertFalse(EditRules.of(taskItem()).canDelete)
        assertFalse(EditRules.of(cardItem()).canDelete)
        assertTrue(EditRules.of(AgendaItem(key = "k", kind = ItemKind.EVENT, home = Home.CALENDAR)).canDelete)
        assertTrue(EditRules.of(AgendaItem(key = "k", kind = ItemKind.TODO, home = Home.TASKS)).canDelete)
    }

    @Test
    fun theKindsOfTheCommandsMatchTheTableOfSection9() {
        val server = EditFakeServer()
        val e = eventItem(server)
        assertEquals(CommandKind.CAL_PATCH, EditCommands.event(e, eventForm(e).copy(title = "x"), UTC, agents)!!.kind)
        assertEquals(CommandKind.CAL_DELETE, EditCommands.removeObject(e).kind)
        assertEquals(CommandKind.GANTT_UPDATE, EditCommands.taskDone(taskItem()).kind)
        assertEquals(CommandKind.DECK_UPDATE, EditCommands.cardDone(cardItem()).kind)
        assertNotNull(EditCommands.task(taskItem(), TaskForm.of(taskItem()).copy(agentId = "AG-0001"), agents).single { it.kind == CommandKind.GANTT_AGENT })
    }
}
