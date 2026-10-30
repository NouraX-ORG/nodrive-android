/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The throwaway server as somebody else sees it, without the app's code: plain HTTP as a seeded user (the device tests make their
 * test data with it and read back what the server holds after the screens wrote). Only the throwaway server of the test run.
 */
class LiveRaw(private val base: String, private val user: String, private val token: String) {
    class Reply(val status: Int, val body: String) {
        fun json(): JsonElement = JsonParser.parseString(body)

        fun ocsData(): JsonElement = json().asJsonObject.getAsJsonObject("ocs").get("data")
    }

    fun request(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Reply {
        val connection = URL(base.trimEnd('/') + "/" + path.trimStart('/')).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray()))
        connection.setRequestProperty("OCS-APIRequest", "true")
        connection.setRequestProperty("Accept", "application/json")
        for ((k, v) in headers) connection.setRequestProperty(k, v)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
        return Reply(status, stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty())
    }

    fun json(method: String, path: String, body: JsonElement) = request(method, path, body.toString(), mapOf("Content-Type" to "application/json"))

    // ---- calendars -----------------------------------------------------------------------------------------------------------
    fun putObject(calendar: String, name: String, ics: String): Reply =
        request("PUT", "remote.php/dav/calendars/$user/$calendar/$name", ics, mapOf("Content-Type" to "text/calendar; charset=utf-8", "If-None-Match" to "*"))

    /** Every event and to-do of a calendar as one iCalendar text. */
    fun export(calendar: String): String =
        request("GET", "remote.php/dav/calendars/$user/$calendar/?export", null, mapOf("Accept" to "text/calendar")).body.replace("\r\n ", "")

    /** The lines of the event or to-do whose SUMMARY is [summary], or null. */
    fun block(calendar: String, summary: String): String? {
        val text = export(calendar)
        val parts = Regex("BEGIN:(VEVENT|VTODO)(.*?)END:\\1", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.value }
        return parts.firstOrNull { it.contains("SUMMARY:$summary\r\n") || it.contains("SUMMARY:$summary\n") }
    }

    /** The event or to-do with that SUMMARY in the person's own calendars (a new item goes into the server's default one). */
    fun blockAny(summary: String): String? = listOf("personal", "work").firstNotNullOfOrNull { block(it, summary) }

    fun exportAll(): String = listOf("personal", "work").joinToString("\n") { export(it) }

    // ---- NoGantt ---------------------------------------------------------------------------------------------------------------
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1/"

    private fun ganttList(path: String): JsonArray = request("GET", "$gantt$path?format=json").ocsData().asJsonArray

    fun projectId(title: String): Int = ganttList("projects").first { it.asJsonObject.get("title").asString == title }.asJsonObject.get("id").asInt

    fun resourceId(name: String): Int = ganttList("resources").first { it.asJsonObject.get("name").asString == name }.asJsonObject.get("id").asInt

    fun createTask(projectId: Int, title: String, start: String, end: String, rows: List<Pair<Int, Double?>>): Int {
        val body = JsonObject()
        body.addProperty("title", title)
        body.addProperty("start_date", start)
        body.addProperty("end_date", end)
        body.addProperty("manual_progress", 0)
        val resources = JsonArray()
        for ((rid, work) in rows) {
            val o = JsonObject()
            o.addProperty("resource_id", rid)
            if (work == null) o.add("work", JsonNull.INSTANCE) else o.addProperty("work", work)
            resources.add(o)
        }
        body.add("resources", resources)
        val reply = json("POST", "${gantt}projects/$projectId/tasks?format=json", body)
        check(reply.status in 200..299) { "creating a task answered ${reply.status}" }
        val data = reply.ocsData().asJsonObject
        return (if (data.has("task")) data.getAsJsonObject("task") else data).get("id").asInt
    }

    fun task(projectId: Int, taskId: Int): JsonObject = ganttList("projects/$projectId/tasks").map { it.asJsonObject }.first { it.get("id").asInt == taskId }

    /** Makes [successor] follow [predecessor]. */
    fun link(successor: Int, predecessor: Int) {
        val body = JsonObject()
        val deps = JsonArray()
        val d = JsonObject()
        d.addProperty("predecessor_id", "t-$predecessor")
        d.addProperty("dependency_type", "EA")
        d.addProperty("lag_days", 0)
        deps.add(d)
        body.add("dependencies", deps)
        json("PUT", "${gantt}tasks/$successor?format=json", body)
    }

    // ---- Deck ------------------------------------------------------------------------------------------------------------------
    private val deck = "index.php/apps/deck/api/v1.0/"

    fun boardId(title: String): Int =
        request("GET", "${deck}boards").json().asJsonArray.first { it.asJsonObject.get("title").asString == title }.asJsonObject.get("id").asInt

    fun stackId(boardId: Int): Int = request("GET", "${deck}boards/$boardId/stacks").json().asJsonArray[0].asJsonObject.get("id").asInt

    fun createCard(boardId: Int, stackId: Int, title: String, due: String, assignTo: String): Int {
        val body = JsonObject()
        body.addProperty("title", title)
        body.addProperty("type", "plain")
        body.addProperty("order", 1)
        body.addProperty("duedate", due)
        val reply = json("POST", "${deck}boards/$boardId/stacks/$stackId/cards", body)
        check(reply.status in 200..299) { "creating a card answered ${reply.status}" }
        val id = reply.json().asJsonObject.get("id").asInt
        val assign = JsonObject()
        assign.addProperty("userId", assignTo)
        assign.addProperty("type", 0)
        check(json("PUT", "${deck}boards/$boardId/stacks/$stackId/cards/$id/assignUser", assign).status in 200..299) { "assignUser failed" }
        return id
    }

    fun card(boardId: Int, stackId: Int, cardId: Int): JsonObject = request("GET", "${deck}boards/$boardId/stacks/$stackId/cards/$cardId").json().asJsonObject

    private companion object {
        const val TIMEOUT_MILLIS = 60_000
        const val HTTP_ERROR = 400
    }
}
