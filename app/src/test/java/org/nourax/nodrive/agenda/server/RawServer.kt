/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The throwaway server as somebody else sees it, WITHOUT the app's own code: plain HTTP as a seeded user. The server tests use it
 * to make test data (a NoGantt task, a card), to be "another person who changes the item meanwhile", and to check what the server
 * really holds after NoAgenda wrote it. It talks to the throwaway server only (NODRIVE_AG_URL).
 */
class RawServer(private val user: String) {
    class Reply(val status: Int, val body: String, val headers: Map<String, String>) {
        fun json(): JsonElement = JsonParser.parseString(body)

        /** The `data` of an OCS answer. */
        fun ocs(): JsonElement = json().asJsonObject.getAsJsonObject("ocs").get("data")

        fun header(name: String): String = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
    }

    private val base = checkNotNull(ServerSupport.url).trimEnd('/')
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:${ServerSupport.token(user)}".toByteArray())

    fun request(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Reply {
        val connection = URL(base + "/" + path.trimStart('/')).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Authorization", auth)
        connection.setRequestProperty("OCS-APIRequest", "true")
        connection.setRequestProperty("Accept", "application/json")
        for ((k, v) in headers) connection.setRequestProperty(k, v)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= HTTP_ERROR) connection.errorStream else connection.inputStream
        val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        val received = connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
        return Reply(status, text, received)
    }

    fun get(path: String) = request("GET", path)

    fun json(method: String, path: String, body: JsonElement): Reply =
        request(method, path, body.toString(), mapOf("Content-Type" to "application/json"))

    // ---- NoGantt ----------------------------------------------------------------------------------------------------------
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1/"

    fun ganttGet(path: String): JsonElement = get("$gantt$path${if (path.contains('?')) "&" else "?"}format=json").ocs()

    /** Makes a NoGantt task (as this user, who must manage the project). Returns its id. */
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
            if (work == null) o.add("work", com.google.gson.JsonNull.INSTANCE) else o.addProperty("work", work)
            resources.add(o)
        }
        body.add("resources", resources)
        val reply = json("POST", "${gantt}projects/$projectId/tasks?format=json", body)
        check(reply.status in 200..299) { "creating a task answered ${reply.status}" }
        val data = reply.ocs().asJsonObject
        val task = if (data.has("task")) data.getAsJsonObject("task") else data
        return task.get("id").asInt
    }

    fun task(projectId: Int, taskId: Int): JsonObject =
        ganttGet("projects/$projectId/tasks").asJsonArray.map { it.asJsonObject }.first { it.get("id").asInt == taskId }

    // ---- Deck --------------------------------------------------------------------------------------------------------------
    private val deck = "index.php/apps/deck/api/v1.0/"

    fun stackId(boardId: Int): Int = get("${deck}boards/$boardId/stacks").json().asJsonArray[0].asJsonObject.get("id").asInt

    fun createCard(boardId: Int, stackId: Int, title: String, due: String?, description: String, start: String?, assignTo: String): Int {
        val body = JsonObject()
        body.addProperty("title", title)
        body.addProperty("type", "plain")
        body.addProperty("order", 1)
        body.addProperty("description", description)
        if (due != null) body.addProperty("duedate", due)
        if (start != null) body.addProperty("startdate", start)
        val reply = json("POST", "${deck}boards/$boardId/stacks/$stackId/cards", body)
        check(reply.status in 200..299) { "creating a card answered ${reply.status}" }
        val id = reply.json().asJsonObject.get("id").asInt
        val assign = JsonObject()
        assign.addProperty("userId", assignTo)
        assign.addProperty("type", 0)
        check(json("PUT", "${deck}boards/$boardId/stacks/$stackId/cards/$id/assignUser", assign).status in 200..299) { "assignUser failed" }
        return id
    }

    /** Makes a list (stack) at the end of a board (as this user, who must manage the board). Returns its id. */
    fun createStack(boardId: Int, title: String): Int {
        val body = JsonObject()
        body.addProperty("title", title)
        body.addProperty("order", STACK_ORDER)
        val reply = json("POST", "${deck}boards/$boardId/stacks", body)
        check(reply.status in 200..299) { "creating a stack answered ${reply.status}" }
        return reply.json().asJsonObject.get("id").asInt
    }

    fun card(boardId: Int, stackId: Int, cardId: Int): JsonObject = get("${deck}boards/$boardId/stacks/$stackId/cards/$cardId").json().asJsonObject

    companion object {
        private const val TIMEOUT_MILLIS = 60_000
        private const val HTTP_ERROR = 400
        private const val STACK_ORDER = 99
    }
}

/** The ids the seed wrote (tools/ci/agenda-seed.py): projects, resources, tasks, the board and its labels. */
class Seed(val root: JsonObject) {
    fun project(name: String): Int = root.getAsJsonObject("projects").get(name).asInt

    fun resource(name: String): Int = root.getAsJsonObject("resources").get(name).asInt

    val board: Int get() = root.get("board").asInt

    fun label(name: String): Int = root.getAsJsonObject("labels").get(name).asInt

    companion object {
        fun load(): Seed {
            val dir = File(System.getenv("NODRIVE_AG_REPORT_DIR") ?: "build/agenda-server")
            return Seed(JsonParser.parseString(File(dir, "seed.json").readText(Charsets.UTF_8)).asJsonObject)
        }
    }
}
