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
import org.nourax.nodrive.agenda.dav.CalendarReadOptions
import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.dav.CalendarSnapshot
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.deck.DeckFetchOptions
import org.nourax.nodrive.agenda.deck.DeckSnapshot
import org.nourax.nodrive.agenda.gantt.GanttClient
import org.nourax.nodrive.agenda.gantt.GanttFetchOptions
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.logic.BuildInput
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.ocs.ServerInfo
import org.nourax.nodrive.agenda.ocs.ServerInfoClient
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** The fixtures: made-up data in the shape of NoGantt 2.4.1, Deck 1.19.1 and the server's CalDAV answers. */
object Fx {
    fun bytes(relative: String): ByteArray =
        checkNotNull(Fx::class.java.getResourceAsStream("/nodrive/agenda/$relative")) { "missing fixture $relative" }.use { it.readBytes() }

    fun text(relative: String): String = String(bytes(relative), Charsets.UTF_8)

    fun json(relative: String) = JsonParser.parseString(text(relative))

    fun obj(relative: String): JsonObject = json(relative).asJsonObject

    fun arr(relative: String): JsonArray = json(relative).asJsonArray

    /** Wraps a JSON payload in the OCS envelope. */
    fun envelope(data: String): ByteArray =
        ("{\"ocs\":{\"meta\":{\"status\":\"ok\",\"statuscode\":200,\"message\":\"OK\"},\"data\":$data}}").toByteArray(Charsets.UTF_8)
}

/** Today in every scenario is Wednesday 2026-10-28. */
val NOW: Instant = Instant.parse("2026-10-28T12:00:00Z")
val TODAY: LocalDate = LocalDate.of(2026, 10, 28)
val UTC: ZoneId = ZoneId.of("UTC")
val ROBOT: String = AgentRegistry.robot

/** Answers from canned bodies and records every request. */
class FakeHttp : AgendaHttp {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class Route(val method: String, val path: String, val contains: String, val status: Int, val body: ByteArray)

    val requests = CopyOnWriteArrayList<Request>()
    private val routes = ArrayList<Route>()
    var offline = false

    private fun normalise(path: String): String = path.substringBefore('?').trim('/')

    fun route(method: String, path: String, status: Int, body: ByteArray, contains: String = "") {
        routes += Route(method, normalise(path), contains, status, body)
    }

    fun route(method: String, path: String, status: Int, body: String, contains: String = "") =
        route(method, path, status, body.toByteArray(Charsets.UTF_8), contains)

    fun routeFile(method: String, path: String, status: Int, fixture: String, contains: String = "") =
        route(method, path, status, Fx.bytes(fixture), contains)

    fun routeOcs(path: String, fixture: String) =
        route("GET", "ocs/v2.php/apps/nc_gantt/api/v1/$path", 200, Fx.envelope(Fx.text(fixture)))

    fun count(method: String, path: String): Int = requests.count { it.method == method && normalise(it.path) == normalise(path) }

    override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResult {
        val text = body?.toString(Charsets.UTF_8).orEmpty()
        requests += Request(method, path, headers, text)
        if (offline) return HttpResult(0, networkError = true)
        val wanted = normalise(path)
        // The last matching route wins, so a test can override an answer.
        val route = routes.lastOrNull { it.method == method && it.path == wanted && (it.contains.isEmpty() || text.contains(it.contains)) }
            ?: return HttpResult(404)
        return HttpResult(route.status, route.body)
    }
}

/** Routes of the whole scenario for one person: "ali" (staff) or "mara" (manager). */
fun routeScenario(http: FakeHttp, user: String) {
    http.routeFile("GET", "remote.php/dav/files/$user/NoAgenda/agent-registry.json", 200, "agent-registry.json")
    http.route("GET", "ocs/v2.php/cloud/user", 200, Fx.envelope(Fx.text("server/user-$user.json")))
    http.route("GET", "ocs/v1.php/cloud/capabilities", 200, Fx.envelope(Fx.text("server/capabilities.json")))

    http.routeOcs("my-tasks", "gantt/my-tasks-$user.json")
    http.routeOcs("projects", "gantt/projects-$user.json")
    http.routeOcs("resources", "gantt/resources.json")
    http.routeOcs("work-schedules", "gantt/work-schedules.json")
    http.routeOcs("holidays", "gantt/holidays.json")
    http.routeOcs("work-calendars", "gantt/work-calendars.json")
    http.routeOcs("settings", "gantt/settings.json")
    http.routeOcs("task-sync", "gantt/task-sync-off.json")
    http.routeOcs("capacity-check", if (user == "mara") "gantt/capacity-mara.json" else "gantt/capacity-empty.json")
    for (n in listOf("1", "2")) {
        http.routeOcs("projects/$n/tasks", "gantt/tasks-$n.json")
        http.routeOcs("projects/$n/milestones", "gantt/milestones-$n.json")
        http.routeOcs("projects/$n/subprojects", "gantt/subprojects-$n.json")
        http.routeOcs("projects/$n/shares", "gantt/shares-$n.json")
    }

    http.routeFile("GET", "index.php/apps/deck/api/v1.0/boards", 200, "deck/boards-$user.json")
    http.routeFile("GET", "index.php/apps/deck/api/v1.0/boards/10/stacks", 200, "deck/stacks-10.json")

    if (user == "ali") {
        http.routeFile("PROPFIND", "remote.php/dav/principals/users/ali/", 207, "caldav/principal-ali.xml")
        http.routeFile("PROPFIND", "remote.php/dav/calendars/ali/", 207, "caldav/home-ali.xml")
        val base = "remote.php/dav/calendars/ali"
        http.routeFile("REPORT", "$base/personal/", 207, "caldav/events-personal.xml", "VEVENT")
        http.routeFile("REPORT", "$base/personal/", 207, "caldav/todos-personal.xml", "VTODO")
        http.routeFile("REPORT", "$base/work/", 207, "caldav/events-work.xml", "VEVENT")
        http.routeFile("REPORT", "$base/mara-team_shared_by_mara/", 207, "caldav/events-team.xml", "VEVENT")
        http.routeFile("REPORT", "$base/holidays/", 207, "caldav/events-holidays.xml", "VEVENT")
        http.routeFile("REPORT", "$base/contact_birthdays/", 207, "caldav/events-birthdays.xml", "VEVENT")
        http.routeFile("REPORT", "$base/ali-task-copy/", 207, "caldav/empty.xml", "VEVENT")
    } else if (user == "mara") {
        http.routeFile("PROPFIND", "remote.php/dav/principals/users/mara/", 207, "caldav/principal-mara.xml")
        http.routeFile("PROPFIND", "remote.php/dav/calendars/mara/", 207, "caldav/home-mara.xml")
        val base = "remote.php/dav/calendars/mara/personal/"
        http.routeFile("REPORT", base, 207, "caldav/empty.xml", "VEVENT")
        http.routeFile("REPORT", base, 207, "caldav/empty.xml", "VTODO")
    }
}

/** Reads the scenario of one person through the real readers (with the fake transport) into a build input. */
fun loadScenario(user: String, http: FakeHttp = FakeHttp(), settings: SourceSettings = SourceSettings()): BuildInput {
    routeScenario(http, user)
    val server: ServerInfo = ServerInfoClient.fetch(http, ServerInfo(), NOW.toEpochMilli()).info
    val (from, to) = CalendarReader.syncWindow(TODAY)
    val calendar: CalendarSnapshot = CalendarReader.read(http, CalendarReadOptions(user, from, to, UTC, NOW), CalendarSnapshot()).snapshot
    val gantt: GanttSnapshot = GanttClient.fetch(http, GanttFetchOptions(user, server.isServerAdmin, server.groups, NOW.toEpochMilli()), GanttSnapshot()).snapshot
    val deck: DeckSnapshot = DeckClient.fetch(http, DeckFetchOptions(user, server.deckVersion, NOW.toEpochMilli()), DeckSnapshot()).snapshot
    return BuildInput(
        davUser = user,
        server = server,
        calendar = calendar,
        gantt = gantt,
        deck = deck,
        registry = AgentRegistry.parse(Fx.bytes("agent-registry.json")),
        settings = settings,
        today = TODAY,
        zone = UTC,
        now = NOW
    )
}

/** Ali after he answered "which calendars are personal?" with just "Personal" (the others count as Work). */
fun loadAliAnswered(): BuildInput {
    val input = loadScenario("ali")
    return BuildInput(
        input.davUser, input.server, input.calendar, input.gantt, input.deck, input.registry,
        SourceSettings().answerPersonalQuestion(setOf("personal"), listOf("personal", "work", "ali-task-copy"), false),
        input.today, input.zone, input.now
    )
}

fun List<AgendaItem>.byKey(key: String): AgendaItem? = firstOrNull { it.key == key }

fun List<AgendaItem>.byTitle(title: String): AgendaItem? = firstOrNull { it.title == title }

fun AgendaItem.has(kind: ProblemKind): Boolean = problems.any { it.kind == kind }
