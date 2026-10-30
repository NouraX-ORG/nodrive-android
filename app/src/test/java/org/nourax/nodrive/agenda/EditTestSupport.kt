/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.nourax.nodrive.agenda.edit.normaliseVersion
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.net.WritePolicy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small server in memory for the edit tests: calendar objects with ETags, NoGantt tasks and Deck cards, with the behaviours that
 * matter (412 on a wrong `If-Match`, NoGantt replacing the whole list of people, Deck clearing what a card update leaves out).
 * It refuses what the real transport would refuse (WritePolicy) and records every request.
 */
class EditFakeServer : AgendaHttp {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    val requests = CopyOnWriteArrayList<Req>()
    var offline = false

    /** The next write answers this status (and then it is back to normal). */
    var failNextWrite: Int? = null
    var failNextBody: String = ""

    private fun ok(body: ByteArray = ByteArray(0), status: Int = 200, headers: Map<String, String> = emptyMap()) = HttpResult(status, body, headers)

    fun writes(): List<Req> = requests.filter { !WritePolicy.isRead(it.method) }

    // ---- CalDAV -------------------------------------------------------------------------------------------------------
    class Obj(var text: String, var etag: Int)

    val objects = LinkedHashMap<String, Obj>()
    var omitPutEtag = false

    fun putObject(href: String, text: String) {
        val key = href.trimStart('/')
        val o = objects[key]
        if (o == null) objects[key] = Obj(text, 1) else {
            o.text = text
            o.etag++
        }
    }

    fun etagOf(href: String): String = "\"v${objects.getValue(href.trimStart('/')).etag}\""

    // ---- NoGantt -------------------------------------------------------------------------------------------------------
    var ganttTasks = JsonArray()
    var ganttProjects = JsonArray()
    var ganttResources = JsonArray()
    var ganttSettings = JsonObject().also { it.addProperty("hours_per_pt", 8) }
    var propagated = JsonArray()

    /** Dates NoGantt saves instead of the ones sent (it snaps a moved task to working days): applied to the task after a PUT. */
    var snapDates: Map<String, String> = emptyMap()
    var ganttVersion = 0

    /** NoGantt drops a row of a resource that is not in this list, without a word (the hazard of plan 2.5). */
    var dropRowsOf: Set<Int> = emptySet()

    // ---- Deck ----------------------------------------------------------------------------------------------------------
    var card = JsonObject()
    var board = JsonObject()

    /** The ids of the lists (stacks) of the board; a card in another one is on another board. */
    var stacks: List<Int> = listOf(4)
    var nextLabelId = 100
    var deckVersion = 1000

    private fun gantt(method: String, rest: String, query: String, body: String): HttpResult {
        failNextWrite?.takeIf { method != "GET" }?.let {
            failNextWrite = null
            return HttpResult(it, failNextBody.toByteArray())
        }
        val tasksPath = Regex("""projects/(\d+)/tasks""").matchEntire(rest)
        return when {
            method == "GET" && tasksPath != null -> ok(Fx.envelope(ganttTasks.toString()))
            method == "GET" && rest == "projects" -> ok(Fx.envelope(ganttProjects.toString()))
            method == "GET" && rest == "resources" -> ok(Fx.envelope(ganttResources.toString()))
            method == "GET" && rest == "settings" -> ok(Fx.envelope(ganttSettings.toString()))
            method == "PUT" && rest.startsWith("tasks/") -> {
                val id = rest.removePrefix("tasks/").toInt()
                val task = ganttTasks.map { it.asJsonObject }.first { it.get("id").asInt == id }
                val change = JsonParser.parseString(body).asJsonObject
                for (key in listOf("start_date", "end_date", "manual_progress", "status")) if (change.has(key)) task.add(key, change.get(key))
                if (change.has("resources")) {
                    val rows = JsonArray()
                    for (row in change.getAsJsonArray("resources")) {
                        val rid = row.asJsonObject.get("resource_id").asInt
                        if (rid in dropRowsOf) continue
                        rows.add(row)
                    }
                    task.add("resources", rows)
                }
                for ((key, value) in snapDates) task.addProperty(key, value)
                ganttVersion++
                task.addProperty("updated_at", "2026-10-28 12:00:%02d".format(ganttVersion))
                val data = JsonObject()
                data.add("task", task)
                data.add("propagated", propagated)
                data.addProperty("op_id", "op-1")
                data.add("deck", JsonObject())
                ok(Fx.envelope(data.toString()))
            }
            method == "POST" && rest == "changes/op-1/revert" -> ok(Fx.envelope("{}"))
            else -> HttpResult(404)
        }
    }

    private fun deck(method: String, rest: String, body: String): HttpResult {
        failNextWrite?.takeIf { method != "GET" }?.let {
            failNextWrite = null
            return HttpResult(it, failNextBody.toByteArray())
        }
        val cardPath = Regex("""boards/(\d+)/stacks/(\d+)/cards/(\d+)(?:/(assignLabel|removeLabel))?""").matchEntire(rest)
        return when {
            method == "GET" && cardPath != null && cardPath.groupValues[4].isEmpty() -> ok(card.toString().toByteArray())
            method == "GET" && Regex("""boards/\d+""").matches(rest) -> ok(board.toString().toByteArray())
            method == "GET" && Regex("""boards/\d+/stacks""").matches(rest) -> {
                val array = JsonArray()
                stacks.forEach { array.add(JsonObject().also { s -> s.addProperty("id", it) }) }
                ok(array.toString().toByteArray())
            }
            method == "PUT" && cardPath != null && cardPath.groupValues[4].isEmpty() -> {
                val request = JsonParser.parseString(body).asJsonObject
                // Deck replaces the card: what the request leaves out is cleared, and "done" too.
                for (key in listOf("duedate", "startdate", "description", "done")) {
                    if (request.has(key)) card.add(key, request.get(key)) else card.add(key, JsonNull.INSTANCE)
                }
                card.addProperty("title", request.get("title").asString)
                // Deck's update sets the card's list from the list in the address (CardService::update, setStackId).
                card.addProperty("stackId", cardPath.groupValues[2].toInt())
                deckVersion++
                card.addProperty("lastModified", deckVersion)
                ok(card.toString().toByteArray())
            }
            method == "PUT" && cardPath != null -> {
                val labelId = JsonParser.parseString(body).asJsonObject.get("labelId").asInt
                val labels = card.getAsJsonArray("labels")
                if (cardPath.groupValues[4] == "assignLabel") {
                    val found = board.getAsJsonArray("labels").map { it.asJsonObject }.first { it.get("id").asInt == labelId }
                    labels.add(found)
                } else {
                    val keep = JsonArray()
                    labels.filter { it.asJsonObject.get("id").asInt != labelId }.forEach { keep.add(it) }
                    card.add("labels", keep)
                }
                deckVersion++
                card.addProperty("lastModified", deckVersion)
                ok(card.toString().toByteArray())
            }
            method == "POST" && Regex("""boards/\d+/labels""").matches(rest) -> {
                val request = JsonParser.parseString(body).asJsonObject
                val label = JsonObject()
                label.addProperty("id", nextLabelId++)
                label.addProperty("title", request.get("title").asString)
                label.addProperty("color", request.get("color").asString)
                board.getAsJsonArray("labels").add(label)
                ok(label.toString().toByteArray())
            }
            else -> HttpResult(404)
        }
    }

    private fun calendar(method: String, key: String, headers: Map<String, String>, body: String): HttpResult {
        failNextWrite?.takeIf { method != "GET" }?.let {
            failNextWrite = null
            return HttpResult(it, failNextBody.toByteArray())
        }
        val o = objects[key]
        return when (method) {
            "GET" -> if (o == null) HttpResult(404) else ok(o.text.toByteArray(), headers = mapOf("etag" to "\"v${o.etag}\""))
            "PUT" -> {
                if (headers["If-None-Match"] == "*") {
                    if (o != null) return HttpResult(412)
                    objects[key] = Obj(body, 1)
                    return ok(status = 201, headers = if (omitPutEtag) emptyMap() else mapOf("etag" to "\"v1\""))
                }
                if (o == null) return HttpResult(404)
                val match = headers["If-Match"]
                if (match != null && normaliseVersion(match) != "v${o.etag}") return HttpResult(412)
                o.text = body
                o.etag++
                ok(status = 204, headers = if (omitPutEtag) emptyMap() else mapOf("etag" to "\"v${o.etag}\""))
            }
            "DELETE" -> {
                if (o == null) return HttpResult(404)
                val match = headers["If-Match"]
                if (match != null && normaliseVersion(match) != "v${o.etag}") return HttpResult(412)
                objects.remove(key)
                ok(status = 204)
            }
            else -> HttpResult(405)
        }
    }

    override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResult {
        val text = body?.toString(Charsets.UTF_8).orEmpty()
        requests += Req(method, path, headers, text)
        if (offline) return HttpResult(0, networkError = true)
        if (!WritePolicy.allows(method, path)) return HttpResult(0, refused = true)
        val query = path.substringAfter('?', "")
        val p = path.substringBefore('?').trimStart('/')
        return when {
            p.startsWith("remote.php/dav/calendars/") && p.endsWith(".ics") -> calendar(method, p, headers, text)
            p.startsWith("ocs/v2.php/apps/nc_gantt/api/v1/") -> gantt(method, p.removePrefix("ocs/v2.php/apps/nc_gantt/api/v1/"), query, text)
            p.startsWith("index.php/apps/deck/api/v1.0/") -> deck(method, p.removePrefix("index.php/apps/deck/api/v1.0/"), text)
            else -> HttpResult(404)
        }
    }
}

/** An event as a calendar app would save it: an alarm, an attendee, an unknown X- property, and a line that is folded. */
fun sampleEvent(uid: String = "ev-1", extra: String = ""): String = listOf(
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Other App//EN",
    "BEGIN:VTIMEZONE",
    "TZID:Europe/Berlin",
    "BEGIN:STANDARD",
    "DTSTART:19701025T030000",
    "TZOFFSETFROM:+0200",
    "TZOFFSETTO:+0100",
    "END:STANDARD",
    "END:VTIMEZONE",
    "BEGIN:VEVENT",
    "UID:$uid",
    "DTSTAMP:20261001T080000Z",
    "SUMMARY:Dentist",
    "DESCRIPTION:Bring the card\\, and the old\\nx-rays. A long line that goes on and on and on and on and on and on and",
    " on until it is folded by the program that saved it",
    "DTSTART;TZID=Europe/Berlin:20261028T110000",
    "DTEND;TZID=Europe/Berlin:20261028T120000",
    "X-CUSTOM-THING;FOO=bar:keep me",
    "CATEGORIES:health,family",
    "ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test",
    "BEGIN:VALARM",
    "ACTION:DISPLAY",
    "DESCRIPTION:Reminder",
    "TRIGGER:-PT15M",
    "END:VALARM"
) .plus(if (extra.isEmpty()) emptyList() else extra.split("\n")).plus(listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

fun sampleTodo(uid: String = "td-1"): String = listOf(
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Other App//EN",
    "BEGIN:VTODO",
    "UID:$uid",
    "DTSTAMP:20261001T080000Z",
    "SUMMARY:Renew passport",
    "DUE;VALUE=DATE:20261030",
    "STATUS:NEEDS-ACTION",
    "X-CUSTOM-THING:keep me",
    "END:VTODO",
    "END:VCALENDAR"
).joinToString("\r\n") + "\r\n"
