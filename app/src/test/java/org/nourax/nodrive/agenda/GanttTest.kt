/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.gantt.GanttClient
import org.nourax.nodrive.agenda.gantt.GanttContract
import org.nourax.nodrive.agenda.gantt.GanttContract.Endpoint
import org.nourax.nodrive.agenda.gantt.GanttFetchOptions
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.BuildInput
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.LocalDate

class GanttTest {
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1"

    private fun withResource(input: BuildInput, id: Int, key: String, value: String): BuildInput {
        val out = JsonArray()
        for (r in input.gantt.resources) {
            val o = r.asJsonObject.deepCopy()
            if (o.get("id").asInt == id) o.add(key, JsonPrimitive(value))
            out.add(o)
        }
        return input.copy(gantt = input.gantt.copy(resources = out))
    }

    @Test
    fun unwrapsTheOcsEnvelope() {
        val reply = OcsJson.parse(HttpResult(200, Fx.envelope("""{"has_linked_resource":true,"tasks":[]}""")))
        assertTrue(reply.ok)
        assertTrue(reply.data is JsonObject)
        val notFound = OcsJson.parse(HttpResult(404, "<html>not found</html>".toByteArray()))
        assertFalse(notFound.ok)
        assertTrue(notFound.notFound)
        assertFalse(OcsJson.parse(HttpResult(200, """{"other":1}""".toByteArray())).envelopeOk)
        assertTrue(OcsJson.parse(HttpResult(401, "{}".toByteArray())).refused)
    }

    @Test
    fun everyFixtureEndpointPassesTheContract() {
        assertEquals("", GanttContract.check(Endpoint.MY_TASKS, Fx.json("gantt/my-tasks-ali.json")))
        assertEquals("", GanttContract.check(Endpoint.PROJECTS, Fx.json("gantt/projects-ali.json")))
        assertEquals("", GanttContract.check(Endpoint.PROJECT_TASKS, Fx.json("gantt/tasks-1.json")))
        assertEquals("", GanttContract.check(Endpoint.MILESTONES, Fx.json("gantt/milestones-1.json")))
        assertEquals("", GanttContract.check(Endpoint.SUBPROJECTS, Fx.json("gantt/subprojects-1.json")))
        assertEquals("", GanttContract.check(Endpoint.RESOURCES, Fx.json("gantt/resources.json")))
        assertEquals("", GanttContract.check(Endpoint.CAPACITY_CHECK, Fx.json("gantt/capacity-mara.json")))
        assertEquals("", GanttContract.check(Endpoint.TASK_SYNC, Fx.json("gantt/task-sync-off.json")))
        assertEquals("", GanttContract.check(Endpoint.SHARES, Fx.json("gantt/shares-1.json")))
    }

    @Test
    fun aMissingOrRenamedKeyFailsLoudly() {
        val tasks = Fx.arr("gantt/tasks-1.json")
        val first = tasks.get(0).asJsonObject
        first.remove("resources")
        first.add("assignments", JsonArray())
        val problem = GanttContract.check(Endpoint.PROJECT_TASKS, tasks)
        assertTrue(problem.contains("projects/{id}/tasks"))
        assertTrue(problem.contains("resources"))
        // the sentence never holds a value of the data
        assertFalse(problem.contains("Write homepage"))

        val mine = Fx.obj("gantt/my-tasks-ali.json")
        mine.remove("has_linked_resource")
        assertTrue(GanttContract.check(Endpoint.MY_TASKS, mine).contains("has_linked_resource"))

        val projects = Fx.arr("gantt/projects-ali.json")
        projects.get(0).asJsonObject.add("id", JsonPrimitive("one")) // wrong type
        assertTrue(GanttContract.check(Endpoint.PROJECTS, projects).isNotEmpty())
        assertTrue(GanttContract.check(Endpoint.PROJECTS, JsonObject()).isNotEmpty()) // not a list
    }

    @Test
    fun readsTheScenarioThroughTheClient() {
        val http = FakeHttp()
        val input = loadScenario("ali", http)
        val g = input.gantt
        assertTrue(g.loaded)
        assertTrue(g.contractOk)
        assertTrue(g.hasLinkedResource)
        assertEquals(8, g.myTasks.size())
        assertEquals(2, g.projects.size())
        assertEquals(9, g.resources.size())
        assertEquals(13, g.projectData.getValue(1).tasks.size())
        assertEquals(1, g.projectData.getValue(1).milestones.size())
        assertTrue(g.projectData.getValue(1).loaded)
        // Ali is an editor, not an admin: no share list is asked
        assertEquals(0, http.count("GET", "$gantt/projects/1/shares"))
        // every NoGantt request carries the OCS header and asks for JSON
        for (request in http.requests.filter { it.path.contains("nc_gantt") }) {
            assertEquals(request.path, "true", request.headers["OCS-APIRequest"])
            assertTrue(request.path, request.path.contains("format=json"))
        }
    }

    @Test
    fun aManagerGetsTheSharesAndTheCapacityCheck() {
        val http = FakeHttp()
        val input = loadScenario("mara", http)
        assertEquals(1, http.count("GET", "$gantt/projects/1/shares"))
        assertEquals(1, http.count("GET", "$gantt/capacity-check"))
        assertTrue(input.gantt.projectData.getValue(1).sharesKnown)
        assertEquals(1, input.gantt.capacity.getAsJsonArray("findings").size())
    }

    @Test
    fun aHiddenProjectIsNotRead() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        val options = GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli(), wantProject = { it.id != 2 })
        val result = GanttClient.fetch(http, options, GanttSnapshot())
        assertTrue(result.ok)
        assertTrue(result.snapshot.projectData.containsKey(1))
        assertFalse(result.snapshot.projectData.containsKey(2))
        assertEquals(0, http.count("GET", "$gantt/projects/2/tasks"))
    }

    @Test
    fun theSharesOfAHiddenProjectAreKeptAndNotReadAgain() {
        val http = FakeHttp()
        routeScenario(http, "mara")
        val sharesPath = "$gantt/projects/1/shares"
        val first = GanttClient.fetch(http, GanttFetchOptions("mara", false, emptyList(), NOW.toEpochMilli()), GanttSnapshot())
        assertEquals(1, http.count("GET", sharesPath))
        val hidden = GanttClient.fetch(http, GanttFetchOptions("mara", false, emptyList(), NOW.toEpochMilli() + 1000, wantProject = { it.id != 1 }), first.snapshot)
        assertTrue(hidden.snapshot.projectData.getValue(1).sharesKnown)
        assertFalse(hidden.snapshot.projectData.getValue(1).loaded)
        assertTrue(hidden.snapshot.projectData.getValue(1).tasks.size() == 0)
        assertEquals(1, http.count("GET", sharesPath)) // not read again
        val shown = GanttClient.fetch(http, GanttFetchOptions("mara", false, emptyList(), NOW.toEpochMilli() + 2000), hidden.snapshot)
        assertEquals(1, http.count("GET", sharesPath))
        assertTrue(shown.snapshot.projectData.getValue(1).loaded)
    }

    @Test
    fun noGanttOnTheServerIsUnavailableNotAnError() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        http.route("GET", "$gantt/my-tasks", 404, "")
        val result = GanttClient.fetch(http, GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli()), GanttSnapshot())
        assertTrue(result.ok)
        assertTrue(result.snapshot.unavailable)
        assertTrue(result.snapshot.loaded)
        val out = AgendaBuilder.build(loadScenario("ali").copy(gantt = result.snapshot))
        assertTrue(out.ganttUnavailable)
        assertTrue(out.items.none { it.home == org.nourax.nodrive.agenda.model.Home.GANTT })
    }

    @Test
    fun notLinkedGivesTheNotLinkedFlag() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        http.routeOcs("my-tasks", "gantt/my-tasks-ali-unlinked.json")
        val result = GanttClient.fetch(http, GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli()), GanttSnapshot())
        assertTrue(result.ok)
        assertFalse(result.snapshot.hasLinkedResource)
        assertTrue(AgendaBuilder.build(loadScenario("ali").copy(gantt = result.snapshot)).ganttNotLinked)
    }

    @Test
    fun aFailureKeepsThePreviousSnapshot() {
        val http = FakeHttp()
        val input = loadScenario("ali", http)
        http.offline = true
        val result = GanttClient.fetch(http, GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli() + 5000), input.gantt)
        assertFalse(result.ok)
        assertTrue(result.networkError)
        assertEquals(input.gantt.myTasks.size(), result.snapshot.myTasks.size())
        assertEquals(input.gantt.fetchedAtMs, result.snapshot.fetchedAtMs)
    }

    @Test
    fun aRefusalIsNotAnOutage() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        http.route("GET", "$gantt/my-tasks", 403, "{}")
        val result = GanttClient.fetch(http, GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli()), GanttSnapshot())
        assertFalse(result.ok)
        assertEquals(403, result.status)
        assertFalse(result.networkError)
    }

    @Test
    fun aChangedShapeTurnsNoGanttReadOnly() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        val tasks = Fx.arr("gantt/tasks-1.json")
        for (t in tasks) t.asJsonObject.remove("updated_at")
        http.route("GET", "$gantt/projects/1/tasks", 200, Fx.envelope(tasks.toString()))
        val result = GanttClient.fetch(http, GanttFetchOptions("ali", false, emptyList(), NOW.toEpochMilli()), GanttSnapshot())
        assertTrue(result.ok)
        assertFalse(result.snapshot.contractOk)
        assertTrue(result.snapshot.contractProblem.contains("updated_at"))
        val out = AgendaBuilder.build(loadScenario("ali").copy(gantt = result.snapshot))
        assertTrue(out.ganttReadOnlyByGuard)
        val ganttRows = out.items.filter { it.home == org.nourax.nodrive.agenda.model.Home.GANTT }
        assertTrue(ganttRows.isNotEmpty())
        assertTrue(ganttRows.all { it.versionReadOnly })
    }

    @Test
    fun agentsOfAliAreRecognisedByIdAndByName() {
        val out = AgendaBuilder.build(loadScenario("ali"))
        // (a) the planning person is listed in the registry's links.nogantt_resource_ids
        val copy = out.items.byKey("gantt:task:101")!!
        assertEquals("Writer-Bot", copy.agent.name)
        assertEquals(WorkKind.MY_AGENT, copy.workKind)
        // the hours of the agent never count for Ali: only his own row (2 h)
        assertEquals(2.0, copy.myWorkHours, 0.0)
        val self = out.items.byKey("gantt:task:102")!!
        assertTrue(self.agent.isSelf)
        assertEquals(WorkKind.SELF, self.workKind)
        assertEquals(10.0, self.myWorkHours, 0.0)
    }

    @Test
    fun aNameOnlyAgentNextToItsPersonIsRecognisedForTheManagerView() {
        val out = AgendaBuilder.build(loadScenario("mara"))
        // (b) "robot Design-Bot (Sara)" next to Sara (no id in the registry)
        val banner = out.items.byKey("gantt:task:103:sara")!!
        assertEquals("Design-Bot", banner.agent.name)
        assertEquals("Sara", banner.personName)
        assertTrue(banner.late)
        assertEquals(2, banner.lateDays)
    }

    @Test
    fun nearMissesAreNeverGuessed() {
        // a trailing space in the name: not the exact name, so it is an unknown agent and a flag
        var input = withResource(loadScenario("ali"), 13, "name", "$ROBOT Research-Bot (Ali) ")
        var item = AgendaBuilder.build(input).items.byKey("gantt:task:108")!!
        assertFalse(item.has(ProblemKind.TWO_AGENTS))
        assertTrue(item.has(ProblemKind.UNKNOWN_AGENT))
        // the person's name differs: not recognised
        input = withResource(loadScenario("ali"), 13, "name", "$ROBOT Research-Bot (Alice)")
        item = AgendaBuilder.build(input).items.byKey("gantt:task:108")!!
        assertTrue(item.has(ProblemKind.UNKNOWN_AGENT))
        // a planning person that has an account is a person, never an agent
        input = withResource(loadScenario("ali"), 13, "uid", "someone")
        item = AgendaBuilder.build(input).items.byKey("gantt:task:108")!!
        assertFalse(item.has(ProblemKind.TWO_AGENTS))
        assertFalse(item.has(ProblemKind.UNKNOWN_AGENT))
        // without the registry nothing is guessed: the agent planning people are unknown agents
        val noRegistry = loadScenario("ali").copy(registry = AgentRegistry.MISSING)
        item = AgendaBuilder.build(noRegistry).items.byKey("gantt:task:101")!!
        assertTrue(item.agent.unknown)
    }

    @Test
    fun twoAgentsOfOnePersonAreFlagged() {
        val item = AgendaBuilder.build(loadScenario("ali")).items.byKey("gantt:task:108")!!
        assertTrue(item.has(ProblemKind.TWO_AGENTS))
        assertEquals("Writer-Bot", item.agent.name) // the first is shown
    }

    @Test
    fun anAgentLookingLikeOneButNotInTheRegistryIsUnknown() {
        val item = AgendaBuilder.build(loadScenario("ali")).items.byKey("gantt:task:113")!!
        assertTrue(item.agent.unknown)
        assertTrue(item.has(ProblemKind.UNKNOWN_AGENT))
        assertTrue(item.agent.id.contains("Ghost-Bot"))
    }

    @Test
    fun workInPersonDaysIsConvertedToHours() {
        val input = withResource(loadScenario("ali"), 1, "unit", "PT")
        val item = AgendaBuilder.build(input).items.byKey("gantt:task:102")!!
        assertEquals(80.0, item.myWorkHours, 0.0) // 10 person days x 8 hours (the instance's hours per person day)
    }

    @Test
    fun projectColoursNeverShowTheServerBlue() {
        val task = AgendaBuilder.build(loadScenario("ali")).items.byKey("gantt:task:102")!!
        assertEquals("#2563EB", task.sourceColor)
    }

    @Test
    fun milestonesComeWithProjectsWhereIHaveTasks() {
        val ms = AgendaBuilder.build(loadScenario("ali")).items.byKey("gantt:ms:301")!!
        assertEquals(ItemKind.MILESTONE, ms.kind)
        assertEquals(LocalDate.of(2026, 11, 10), ms.startDate)
    }

    @Test
    fun theSnapshotSurvivesItsCacheForm() {
        val input = loadScenario("mara")
        val gson = com.google.gson.GsonBuilder().serializeNulls().create()
        val copy = gson.fromJson(gson.toJson(input.gantt), GanttSnapshot::class.java)
        assertTrue(copy.loaded)
        assertEquals(input.gantt.projects, copy.projects)
        assertEquals(input.gantt.projectData.getValue(1).tasks.size(), copy.projectData.getValue(1).tasks.size())
        assertEquals(input.gantt.projectData.getValue(1).shares.size(), copy.projectData.getValue(1).shares.size())
        assertEquals(input.gantt.capacity, copy.capacity)
        assertNotNull(copy.projectData[1])
        assertNull(copy.projectData[99])
    }

    @Test
    fun workModesAreChosenPerProject() {
        val hidden = loadScenario("ali").copy(
            settings = org.nourax.nodrive.agenda.logic.SourceSettings().withProject(1, WorkMode.HIDDEN)
        )
        assertTrue(AgendaBuilder.build(hidden).items.none { it.projectId == 1 })
    }
}
