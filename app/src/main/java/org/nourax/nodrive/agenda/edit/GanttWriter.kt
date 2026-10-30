/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.gantt.GanttClient
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.getOcs
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.arr
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.obj
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry

/**
 * NoGantt task changes (design 9, plan 2.4 and 2.5): dates, % done, status, done and my agent. NoGantt has no ETag: the task is read
 * right before the write and its `updated_at` is compared with the one the person saw (a short race remains; it is documented).
 * `deck_confirmed` is always false, so NoGantt never writes a linked card without the project's own rule asking first.
 */
class GanttWriter(
    private val http: AgendaHttp,
    private val registry: () -> AgentRegistry,
    private val me: () -> String,
    private val myName: () -> String
) {
    private val base = GanttClient.API_BASE

    private fun ocs(path: String) = OcsJson.parse(http.getOcs("$base$path${if (path.contains('?')) "&" else "?"}format=json"))

    private class Fresh(
        val task: JsonObject,
        val project: JsonObject?,
        val resources: List<JsonObject>,
        val hoursPerPt: Double,
        /** The planning people that are on any task of this project. */
        val inProject: Set<Int>
    )

    private sealed interface Read {
        class Ok(val fresh: Fresh) : Read

        class Stop(val result: SendResult) : Read
    }

    private fun read(command: EditCommand, withResources: Boolean): Read {
        val projectId = command.locInt("projectId")
        val taskId = command.locInt("taskId")
        val reply = ocs("projects/$projectId/tasks")
        if (!reply.ok) return Read.Stop(stopFor(reply.httpStatus, reply.networkError))
        val task = (reply.data as? JsonArray)?.objects()?.firstOrNull { intOf(it.get("id")) == taskId }
            ?: return Read.Stop(SendResult.Conflict(ConflictReason.GONE))
        val version = task.str("updated_at")
        if (!command.rebase && command.baseVersion.isNotEmpty() && version != command.baseVersion) {
            return Read.Stop(SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "updated_at", version))
        }
        var project: JsonObject? = null
        var resources: List<JsonObject> = emptyList()
        var hoursPerPt = DEFAULT_HOURS_PER_PT
        if (withResources) {
            val projects = ocs("projects")
            project = (projects.data as? JsonArray)?.objects()?.firstOrNull { intOf(it.get("id")) == projectId }
            val all = ocs("resources")
            if (!all.ok) return Read.Stop(stopFor(all.httpStatus, all.networkError))
            resources = (all.data as? JsonArray)?.objects().orEmpty()
            (ocs("settings").data as? JsonObject)?.let { hoursPerPt = GanttAgentChange.hoursPerPtOf(it) }
        }
        val inProject = GanttAgentChange.idsInProject((reply.data as? JsonArray)?.objects().orEmpty())
        return Read.Ok(Fresh(task, project, resources, hoursPerPt, inProject))
    }

    // What a failed read means. NoGantt's 401 and 403 are the "does not accept this sign-in" case: a refusal, not a wait.
    private fun stopFor(status: Int, networkError: Boolean): SendResult = when {
        networkError || status == 0 -> SendResult.Offline
        status >= SERVER_ERROR -> SendResult.ServerError(status)
        status == UNAUTHORIZED -> SendResult.Conflict(ConflictReason.REFUSED, "http401")
        status == FORBIDDEN -> SendResult.Conflict(ConflictReason.REFUSED, "http403")
        status == NOT_FOUND -> SendResult.Conflict(ConflictReason.GONE)
        else -> SendResult.Conflict(ConflictReason.OTHER, "http$status")
    }

    /** The text of a date field with the time of day that the task had (NoGantt keeps "2026-11-04 00:00:00"). */
    private fun dateText(old: String, value: EditKeys.Value?): String? {
        val day = (value as? EditKeys.Value.Day)?.date ?: return null
        val time = if (old.length >= TIME_START + TIME_LENGTH && old[DATE_LENGTH] == ' ') old.substring(TIME_START, TIME_START + TIME_LENGTH) else "00:00:00"
        return "$day $time"
    }

    fun update(command: EditCommand): SendResult {
        val fresh = when (val r = read(command, withResources = false)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r.fresh
        }
        val task = fresh.task
        val body = JsonObject()
        dateText(task.str("start_date"), EditKeys.parse(command.change[EditKeys.START]))?.let { body.addProperty("start_date", it) }
        dateText(task.str("end_date"), EditKeys.parse(command.change[EditKeys.END]))?.let { body.addProperty("end_date", it) }
        val manual = task.str("progress_mode") != "deck"
        command.change[EditKeys.PERCENT]?.toIntOrNull()?.let {
            if (!manual) return SendResult.Conflict(ConflictReason.REFUSED, "progress_from_board")
            body.addProperty("manual_progress", it.coerceIn(0, MAX_PERCENT))
        }
        command.change[EditKeys.STATUS]?.let { body.addProperty("status", it) }
        if (command.change[EditKeys.DONE] == "true") {
            body.addProperty("status", "done")
            if (manual) body.addProperty("manual_progress", MAX_PERCENT)
        } else if (command.change[EditKeys.DONE] == "false" && !body.has("status")) {
            body.addProperty("status", if (task.int("manual_progress") > 0) "in_progress" else "not_started")
        }
        if (body.size() == 0) return SendResult.Done(task.str("updated_at"))
        body.addProperty("deck_confirmed", false)
        return put(command, body)
    }

    fun changeAgent(command: EditCommand): SendResult {
        val fresh = when (val r = read(command, withResources = true)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r.fresh
        }
        val ctx = GanttAgentChange.Context(me(), myName(), registry(), fresh.hoursPerPt)
        val agentId = command.change[EditKeys.AGENT].orEmpty()
        val target: Agent? = if (agentId == EditKeys.SELF || agentId.isEmpty()) null else registry().byId(agentId)
            ?: return SendResult.Conflict(ConflictReason.AGENT_NOT_IN_PROJECT, agentId)
        if (target != null && !ctx.isMe(target.person)) return SendResult.Conflict(ConflictReason.REFUSED, "not_my_agent")
        return when (val plan = GanttAgentChange.plan(fresh.task, fresh.project, fresh.resources, ctx, target, fresh.inProject)) {
            is GanttAgentChange.Plan.Refuse -> SendResult.Conflict(plan.reason, plan.detail)
            GanttAgentChange.Plan.Same -> SendResult.Done(fresh.task.str("updated_at"))
            is GanttAgentChange.Plan.Send -> {
                val rows = JsonArray()
                for (row in plan.rows) {
                    val o = JsonObject()
                    o.addProperty("resource_id", row.resourceId)
                    if (row.work == null) o.add("work", JsonNull.INSTANCE) else o.addProperty("work", row.work)
                    rows.add(o)
                }
                val body = JsonObject()
                body.add("resources", rows)
                body.addProperty("deck_confirmed", false)
                val result = put(command, body)
                if (result is SendResult.Done) {
                    // What the server saved must be what was sent (it drops a row it can not see, without a word).
                    val matches = result.answer?.let { GanttAgentChange.answerMatches(plan.rows, it) } ?: false
                    if (!matches) {
                        // Ids only, never names or values.
                        AgendaLog.w(TAG, "agent change: sent rows ${plan.rows.map { it.resourceId }}, got ${result.answer?.let { GanttAgentChange.idsOf(it) }}")
                    }
                    result.copy(checkInHome = !matches)
                } else {
                    result
                }
            }
        }
    }

    private fun put(command: EditCommand, body: JsonObject): SendResult {
        val reply = http.sendJson("PUT", "${base}tasks/${command.locInt("taskId")}?format=json", body)
        reply.failure(appPassword = true)?.let { failure ->
            if (failure is SendResult.Conflict && failure.reason == ConflictReason.OTHER) return mapBad(reply.status, reply.text())
            return failure
        }
        val ocs = OcsJson.parse(reply)
        val data = ocs.data as? JsonObject
        val task = data?.obj("task") ?: JsonObject()
        val moved = data?.arr("propagated")?.objects().orEmpty()
            .filter { intOf(it.get("id")) != command.locInt("taskId") }.map { it.str("title") }.filter { it.isNotEmpty() }
        val opElement = data?.get("op_id")
        val op = if (opElement != null && opElement.isJsonPrimitive) opElement.asString else ""
        val deck = data?.obj("deck")
        val cardNotMoved = deck != null && (deck.int("unconfirmed") > 0 || deck.arr("pending").size() > 0 || deck.arr("failed").size() > 0)
        return SendResult.Done(
            newVersion = task.str("updated_at"),
            alsoMoved = moved,
            undoOp = op,
            cardNotMoved = cardNotMoved,
            answer = task
        )
    }

    /** A 400 from NoGantt names its reason (`invalid_date`, `too_many_resources`, ...). Only the code is kept. */
    private fun mapBad(status: Int, body: String): SendResult {
        val known = listOf("too_many_resources", "roles_only_assignment", "invalid_date", "invalid_status", "invalid_color")
        val code = known.firstOrNull { body.contains(it) }.orEmpty()
        val reason = when (code) {
            "too_many_resources" -> ConflictReason.TOO_MANY_PEOPLE
            "roles_only_assignment" -> ConflictReason.ROLES_ONLY
            else -> ConflictReason.INVALID
        }
        return SendResult.Conflict(reason, code.ifEmpty { "http$status" })
    }

    /** Takes a change back with NoGantt's own undo (the 10 second "Undo" after a date change). */
    fun undo(opId: String): Boolean {
        if (opId.isEmpty()) return false
        val reply = http.send("POST", "${base}changes/$opId/revert?format=json", JSON_HEADERS, ByteArray(0), "application/json")
        return reply.ok
    }

    private companion object {
        const val TAG = "AgendaGantt"
        const val SERVER_ERROR = 500
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val MAX_PERCENT = 100
        const val DEFAULT_HOURS_PER_PT = 8.0
        const val DATE_LENGTH = 10
        const val TIME_START = 11
        const val TIME_LENGTH = 8
    }
}
