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
import org.nourax.nodrive.agenda.Fx
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.StatusKeys
import org.nourax.nodrive.agenda.has
import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.dav.DavXml
import org.nourax.nodrive.agenda.gantt.GanttContract
import org.nourax.nodrive.agenda.gantt.GanttContract.Endpoint
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.SourceState
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.getOcs
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.keySignature
import org.nourax.nodrive.agenda.registry.RegistryStatus
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore

/**
 * The auth proof, the API contract and the reads against the throwaway server (design 5 and 12; plan 1.6 and 1.9).
 * Seeded by tools/ci/agenda-seed.py. Skipped unless NODRIVE_AG_URL is set.
 */
class AgendaServerTest {
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1/"
    private val deck = "index.php/apps/deck/api/v1.0/"

    @Before
    fun server() = ServerSupport.assumeServer()

    private fun row(check: String, ok: Boolean, detail: String) {
        ServerSupport.report("report.md", "| $check | ${if (ok) "PASS" else "FAIL"} | $detail |")
    }

    private fun get(http: AgendaHttp, path: String) = http.getOcs(path)

    // ---- the auth proof: the app password with Basic authentication and the CSRF header ------------------------------
    @Test
    fun theAppPasswordIsAcceptedByEveryHome() {
        val http = ServerSupport.http("mara")
        val ocsCalls = listOf("my-tasks", "projects", "resources", "capacity-check", "task-sync", "work-schedules", "settings")
        for (call in ocsCalls) {
            val result = get(http, "$gantt$call?format=json")
            row("auth NoGantt $call", result.status == 200, "HTTP ${result.status}")
            assertEquals(call, 200, result.status)
            assertTrue(call, OcsJson.parse(result).ok)
        }
        val projects = OcsJson.parse(get(http, "${gantt}projects?format=json")).data as JsonArray
        assertTrue(projects.size() >= 2)
        val firstId = projects.first { it.asJsonObject.get("title").asString == "Website" }.asJsonObject.get("id").asInt
        for (call in listOf("tasks", "milestones", "subprojects", "shares")) {
            val result = get(http, "${gantt}projects/$firstId/$call?format=json")
            row("auth NoGantt projects/{id}/$call", result.status == 200, "HTTP ${result.status}")
            assertEquals(call, 200, result.status)
        }
        // Deck REST with the same credential and the CSRF header
        val boards = get(http, "${deck}boards?details=true")
        row("auth NoBoard boards", boards.status == 200, "HTTP ${boards.status}")
        assertEquals(200, boards.status)
        val boardId = (OcsJson.parseElement(boards.body) as JsonArray).first { it.asJsonObject.get("title").asString == "Team board" }.asJsonObject.get("id").asInt
        val stacks = get(http, "${deck}boards/$boardId/stacks")
        row("auth NoBoard stacks", stacks.status == 200, "HTTP ${stacks.status}")
        assertEquals(200, stacks.status)
        // CalDAV
        val principal = http.send("PROPFIND", "remote.php/dav/principals/users/mara/", mapOf("Depth" to "0"), DavXml.propfindBody(CalendarReader.principalProps), "application/xml")
        row("auth CalDAV PROPFIND", principal.status == 207, "HTTP ${principal.status}")
        assertEquals(207, principal.status)
        // the registry file of the user's own NoDrive
        val registry = ServerSupport.http("ali").send("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json")
        row("auth registry file", registry.status == 200, "HTTP ${registry.status}")
        assertEquals(200, registry.status)
    }

    @Test
    fun noGanttNeedsTheCsrfHeaderAndNoAgendaAlwaysSendsIt() {
        // For the report only: what the server says without the header. NoAgenda never sends such a request.
        val http = ServerSupport.http("ali")
        val bare = http.send("GET", "${gantt}my-tasks?format=json", mapOf("Accept" to "application/json"))
        row("NoGantt my-tasks without OCS-APIRequest", true, "HTTP ${bare.status} (NoAgenda always sends the header)")
        val with = get(http, "${gantt}my-tasks?format=json")
        assertEquals(200, with.status)
    }

    @Test
    fun theServerSaysTheDeckVersionAndNoGanttHasNoVersionForANormalUser() {
        val http = ServerSupport.http("ali")
        val caps = OcsJson.parse(get(http, "ocs/v1.php/cloud/capabilities?format=json")).data as JsonObject
        val deckVersion = caps.getAsJsonObject("capabilities").getAsJsonObject("deck").get("version").asString
        row("Deck version", deckVersion.startsWith("1.19"), deckVersion)
        assertTrue(deckVersion.startsWith("1.19"))
        // NoGantt shows no capability to a normal user, which is why the guard is a contract check (design 5)
        assertFalse(caps.getAsJsonObject("capabilities").has("nc_gantt"))
    }

    // ---- the contract: the live answers have the keys and types of 2.4.1 -------------------------------------------------
    private fun fixtureKeys(relative: String): Map<String, String> {
        val e = Fx.json(relative)
        val first = (if (e.isJsonArray) e.asJsonArray.first().asJsonObject else if (e.asJsonObject.has("tasks")) e.asJsonObject.getAsJsonArray("tasks").first().asJsonObject else e.asJsonObject)
        return first.entrySet().associate { it.key to org.nourax.nodrive.agenda.ocs.jsonTypeName(it.value) }
    }

    private fun compare(name: String, live: JsonObject, fixture: Map<String, String>) {
        val missing = fixture.keys.filter { !live.has(it) }
        val retyped = fixture.filter { (k, t) -> live.has(k) && t != "null" && org.nourax.nodrive.agenda.ocs.jsonTypeName(live.get(k)) !in setOf(t, "null") }.keys
        val extra = live.keySet() - fixture.keys
        ServerSupport.report("contract-keys.txt", "$name: keys ${keySignature(live).joinToString(" ")}")
        row("contract $name", missing.isEmpty() && retyped.isEmpty(), "missing ${missing.sorted()}, other type ${retyped.sorted()}, new keys ${extra.sorted()}")
        assertEquals("$name: a key of the fixture is missing live", emptyList<String>(), missing.sorted())
        assertEquals("$name: a key has another type live", emptyList<String>(), retyped.sorted())
    }

    @Test
    fun theLiveAnswersHaveTheKeysOfTheCommittedFixtures() {
        val mara = ServerSupport.http("mara")
        fun data(path: String) = OcsJson.parse(get(mara, path)).data
        // contract check of the app itself on live data
        val projects = data("${gantt}projects?format=json") as JsonArray
        assertEquals("", GanttContract.check(Endpoint.PROJECTS, projects))
        compare("projects", projects.first().asJsonObject, fixtureKeys("gantt/projects-mara.json"))
        val myTasks = data("${gantt}my-tasks?format=json") as JsonObject
        assertEquals("", GanttContract.check(Endpoint.MY_TASKS, myTasks))
        assertEquals("", GanttContract.check(Endpoint.RESOURCES, data("${gantt}resources?format=json")))
        compare("resources", (data("${gantt}resources?format=json") as JsonArray).first().asJsonObject, fixtureKeys("gantt/resources.json"))
        val id = projects.first { it.asJsonObject.get("title").asString == "Website" }.asJsonObject.get("id").asInt
        val tasks = data("${gantt}projects/$id/tasks?format=json") as JsonArray
        assertEquals("", GanttContract.check(Endpoint.PROJECT_TASKS, tasks))
        compare("project task", tasks.first().asJsonObject, fixtureKeys("gantt/tasks-1.json"))
        val milestones = data("${gantt}projects/$id/milestones?format=json") as JsonArray
        assertEquals("", GanttContract.check(Endpoint.MILESTONES, milestones))
        assertEquals("", GanttContract.check(Endpoint.SUBPROJECTS, data("${gantt}projects/$id/subprojects?format=json")))
        assertEquals("", GanttContract.check(Endpoint.SHARES, data("${gantt}projects/$id/shares?format=json")))
        assertEquals("", GanttContract.check(Endpoint.CAPACITY_CHECK, data("${gantt}capacity-check?format=json")))
        assertEquals("", GanttContract.check(Endpoint.TASK_SYNC, data("${gantt}task-sync?format=json")))
        // Deck
        val boards = OcsJson.parseElement(get(mara, "${deck}boards?details=true").body) as JsonArray
        val team = boards.first { it.asJsonObject.get("title").asString == "Team board" }.asJsonObject
        compare("board", team, fixtureKeys("deck/boards-mara.json"))
        val stacks = OcsJson.parseElement(get(mara, "${deck}boards/${team.get("id").asInt}/stacks").body) as JsonArray
        val card = stacks.flatMap { it.asJsonObject.getAsJsonArray("cards") }.first().asJsonObject
        compare("card", card, Fx.arr("deck/stacks-10.json").get(0).asJsonObject.getAsJsonArray("cards").get(0).asJsonObject.entrySet()
            .associate { it.key to org.nourax.nodrive.agenda.ocs.jsonTypeName(it.value) })
        // CalDAV: each property that the calendar list asks for is answered for at least one calendar
        val ali = ServerSupport.http("ali")
        val home = ali.send("PROPFIND", "remote.php/dav/calendars/ali/", mapOf("Depth" to "1"), DavXml.propfindBody(CalendarReader.calendarProps), "application/xml")
        assertEquals(207, home.status)
        val ms = DavXml.parseMultistatus(home.body)
        val answered = ms.responses.flatMap { it.props.keys }.toSet()
        val names = listOf("resourcetype", "displayname", "sync-token", "supported-calendar-component-set", "owner-principal")
        for (n in names) assertTrue("CalDAV property $n is not answered", answered.any { it.endsWith("|$n") })
        row("contract CalDAV calendar properties", true, "answered: ${answered.map { it.substringAfter('|') }.sorted()}")
    }

    // ---- the reads: what each person sees ----------------------------------------------------------------------------------
    private fun read(user: String): AgendaSnapshot {
        val agenda = AccountAgenda("$user@throwaway", ServerSupport.http(user), InMemoryAgendaStore(), ServerSupport.clock)
        val first = agenda.refresh(manual = true)
        // a second read finds the same (the sync tokens say nothing changed)
        val second = agenda.refresh(manual = true)
        assertEquals(first.build.items.map { it.key }.sorted(), second.build.items.map { it.key }.sorted())
        return second
    }

    private fun AgendaSnapshot.title(t: String): AgendaItem? = build.items.firstOrNull { it.title == t }

    @Test
    fun aliSeesHisDayWithHisAgents() {
        val snapshot = read("ali")
        for (key in listOf(StatusKeys.CALENDAR, StatusKeys.GANTT, StatusKeys.BOARD, StatusKeys.REGISTRY)) {
            assertEquals(key, SourceState.FRESH, snapshot.status(key).state)
        }
        assertEquals(RegistryStatus.OK, snapshot.registryStatus)
        val items = snapshot.build.items
        row("Ali: rows", true, "${items.size}")
        assertNotNull(snapshot.title("Dentist"))
        assertNotNull(snapshot.title("Client call"))
        // NoGantt: Writer-Bot is recognised by the id in the registry; Design-Bot by its exact name
        val copy = snapshot.title("Write homepage copy")!!
        assertEquals("Writer-Bot", copy.agent.name)
        assertEquals(WorkKind.MY_AGENT, copy.workKind)
        assertTrue(snapshot.title("Prepare client demo")!!.agent.isSelf)
        // NoBoard: the agent label, and an unknown robot label is flagged
        assertEquals("Writer-Bot", snapshot.title("Call supplier")!!.agent.name)
        assertTrue(snapshot.title("Stranger bot")!!.agent.unknown)
        // a task linked to a card is one row with a badge
        assertTrue(snapshot.title("Landing page build")?.boardBadge ?: false)
        assertNull(snapshot.title("Landing page card"))
        // integrity: two agents; an agent without its person; an unknown robot planning person
        assertTrue(snapshot.title("Two agents")!!.has(ProblemKind.TWO_AGENTS))
        assertNotNull(items.firstOrNull { it.problemOnly && it.problems.any { p -> p.kind == ProblemKind.AGENT_WITHOUT_YOU } })
        assertTrue(snapshot.title("Ghost agent task")!!.agent.unknown)
        // calendar rules: the invitation is one row and always Self; the free event does not block time
        assertEquals(1, items.count { it.title == "Planning meeting" })
        assertTrue(items.first { it.title == "Planning meeting" }.agent.isSelf)
        assertTrue(snapshot.title("Maybe lunch")!!.transparent)
        // NoGantt's copy calendar of Sara is not Ali's; the board's calendar is never read
        assertFalse(snapshot.build.sources.any { it.key.startsWith("app-generated--deck--") && it.mode != "hidden" })
        // the one-time question is open
        assertTrue(snapshot.build.needsPersonalQuestion)
        // the shared board is Do for a member (Deck also makes each person a welcome board of his own, which he runs)
        assertEquals("do", snapshot.build.sources.first { it.type == org.nourax.nodrive.agenda.model.SourceType.BOARD && it.name == "Team board" }.mode)
        assertEquals("do", snapshot.build.sources.first { it.type == org.nourax.nodrive.agenda.model.SourceType.PROJECT && it.name == "Website" }.mode)
    }

    @Test
    fun maraSeesTheProblemsOfWhatSheRuns() {
        val snapshot = read("mara")
        assertTrue(snapshot.build.anyOversee)
        val items = snapshot.build.items
        row("Mara: rows", true, "${items.size}")
        fun team(title: String) = items.firstOrNull { it.title == title && it.workKind == WorkKind.TEAM }
        assertTrue(team("Banner design")!!.has(ProblemKind.LATE))
        assertEquals("Design-Bot", team("Banner design")!!.agent.name)
        assertTrue(team("Publish banners")!!.has(ProblemKind.BLOCKED))
        assertTrue(team("Review sitemap")!!.has(ProblemKind.NOT_ASSIGNED))
        assertTrue(team("Orphan writing")!!.has(ProblemKind.AGENT_NO_PERSON))
        assertTrue(team("Cross pairing")!!.has(ProblemKind.AGENT_WRONG_PERSON))
        assertTrue(team("Temp work")!!.has(ProblemKind.NOT_LINKED))
        assertTrue(items.any { it.has(ProblemKind.NOT_SHARED) })
        assertTrue(items.all { it.workKind == WorkKind.TEAM || it.home != org.nourax.nodrive.agenda.model.Home.GANTT || it.problemOnly })
    }

    @Test
    fun saraHasAnInvalidAgentListAndEverythingShowsAsSelfOrUnknown() {
        val snapshot = read("sara")
        assertEquals(RegistryStatus.INVALID, snapshot.registryStatus)
        // Sara is a viewer of the project but "Banner design" is hers (her planning person is on it), so she has the row.
        val banner = snapshot.title("Banner design")
        assertNotNull(banner)
        assertTrue(banner!!.agent.isSelf || banner.agent.unknown)
        row("Sara: Banner design, agent", true, if (banner.agent.isSelf) "self" else "unknown")
    }

    @Test
    fun noGanttsCopyCalendarIsHiddenAutomaticallyAndGivesNoRows() {
        // The seed switches the copy of Sara's tasks into a calendar on (NoGantt task-sync). NoAgenda never shows that
        // calendar: the tasks come from NoGantt itself, so showing the copies would be every task twice.
        val snapshot = read("sara")
        val uri = snapshot.build.taskCopyCalendarUri
        assertTrue("the seed should have switched the task copy on", uri.isNotEmpty())
        val source = snapshot.build.sources.first { it.type == org.nourax.nodrive.agenda.model.SourceType.CALENDAR && it.key == uri }
        assertTrue(source.autoHidden)
        assertEquals(org.nourax.nodrive.agenda.model.AutoHiddenReason.TASK_COPY, source.autoHiddenReason)
        assertEquals("hidden", source.mode)
        assertTrue(snapshot.build.items.none { it.sourceKey == "cal:$uri" })
        row("Sara: NoGantt copy calendar hidden automatically", true, "hidden, no rows")
    }

    @Test
    fun olafNeverSeesAnAgentOfAnotherOrganisation() {
        val snapshot = read("olaf")
        assertEquals(RegistryStatus.MISSING, snapshot.registryStatus)
        // Olaf is not linked in NoGantt: the empty state says so
        assertTrue(snapshot.build.ganttNotLinked || snapshot.build.ganttUnavailable)
        val everything = snapshot.build.items.joinToString("\n") { it.title + " " + it.agent.name + " " + it.agent.id + " " + it.personName }
        assertFalse(everything.contains("Writer-Bot"))
        assertFalse(everything.contains("Design-Bot"))
    }

    @Test
    fun theOnlyRequestsOfARefreshAreReads() {
        // The proof is the transport's own guard (see AgendaSafetyTest); here a full read must work with it.
        val snapshot = read("ali")
        assertTrue(snapshot.build.items.isNotEmpty())
    }
}
