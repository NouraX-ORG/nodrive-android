/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.gantt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.ocs.jsonTypeName

/**
 * NoGantt 2.4.1 shows no version to a normal user, so the version guard is this contract: every answer must have the
 * keys and types of 2.4.1. Any mismatch makes NoGantt read only (design 5, plan 1.6). Values never appear in a message.
 */
object GanttContract {
    enum class Endpoint(val path: String) {
        MY_TASKS("my-tasks"),
        PROJECTS("projects"),
        PROJECT_TASKS("projects/{id}/tasks"),
        MILESTONES("projects/{id}/milestones"),
        SUBPROJECTS("projects/{id}/subprojects"),
        RESOURCES("resources"),
        CAPACITY_CHECK("capacity-check"),
        TASK_SYNC("task-sync"),
        SHARES("projects/{id}/shares")
    }

    class Key(val name: String, val type: String)

    fun requiredKeys(endpoint: Endpoint): List<Key> = when (endpoint) {
        Endpoint.MY_TASKS -> listOf(Key("has_linked_resource", "bool"), Key("tasks", "array"))
        Endpoint.PROJECTS -> listOf(
            Key("id", "number"), Key("title", "string"), Key("color", "string"), Key("owner_id", "string"),
            Key("permission", "string")
        )
        Endpoint.PROJECT_TASKS -> listOf(
            Key("id", "number"), Key("title", "string"), Key("project_id", "number"), Key("start_date", "any"),
            Key("end_date", "any"), Key("work", "any"), Key("status", "string"), Key("manual_progress", "number"),
            Key("resources", "array"), Key("dependencies", "array"), Key("updated_at", "any")
        )
        Endpoint.MILESTONES -> listOf(Key("id", "number"), Key("title", "string"), Key("milestone_date", "string"))
        Endpoint.SUBPROJECTS -> listOf(Key("id", "number"), Key("title", "string"))
        Endpoint.RESOURCES -> listOf(
            Key("id", "number"), Key("name", "string"), Key("type", "string"), Key("uid", "any"), Key("unit", "string")
        )
        Endpoint.CAPACITY_CHECK -> listOf(Key("findings", "array"))
        Endpoint.TASK_SYNC -> listOf(Key("enabled", "bool"))
        Endpoint.SHARES -> listOf(Key("principal_id", "string"), Key("principal_type", "string"), Key("permission", "string"))
    }

    private val MY_TASK_KEYS = listOf("id", "title", "project_id", "start_date", "end_date", "status")

    private fun typeMatches(value: JsonElement?, type: String): Boolean {
        if (value == null) return false
        return when (type) {
            "any" -> true
            "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
            "number" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber
            "bool" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            "array" -> value.isJsonArray
            "object" -> value.isJsonObject
            else -> false
        }
    }

    private fun checkObject(endpoint: Endpoint, obj: JsonObject): String {
        for (key in requiredKeys(endpoint)) {
            if (!obj.has(key.name)) return "${endpoint.path}: key ${key.name} is missing"
            val value = obj.get(key.name)
            if (!typeMatches(value, key.type)) {
                // Some keys may be null where the source allows it (for example a task without a start).
                val nullable = value.isJsonNull && key.type != "array" && key.type != "bool"
                if (!nullable) return "${endpoint.path}: key ${key.name} has another type (${jsonTypeName(value)})"
            }
        }
        return ""
    }

    /** An empty string when the answer has the 2.4.1 shape; else "endpoint: key x is missing" (no values). */
    fun check(endpoint: Endpoint, data: JsonElement?): String {
        if (data == null) return "${endpoint.path}: no data"
        if (endpoint == Endpoint.MY_TASKS) {
            val obj = data as? JsonObject ?: return "${endpoint.path}: the answer is not an object"
            val problem = checkObject(endpoint, obj)
            if (problem.isNotEmpty()) return problem
            // One task of the list: the keys the day list needs.
            val first = (obj.get("tasks") as JsonArray).firstOrNull() as? JsonObject
            if (first != null) {
                MY_TASK_KEYS.firstOrNull { !first.has(it) }?.let { return "${endpoint.path}: task key $it is missing" }
            }
            return ""
        }
        if (endpoint == Endpoint.CAPACITY_CHECK || endpoint == Endpoint.TASK_SYNC) {
            val obj = data as? JsonObject ?: return "${endpoint.path}: the answer is not an object"
            return checkObject(endpoint, obj)
        }
        val list = data as? JsonArray ?: return "${endpoint.path}: the answer is not a list"
        for (entry in list) {
            val obj = entry as? JsonObject ?: return "${endpoint.path}: an entry is not an object"
            val problem = checkObject(endpoint, obj)
            if (problem.isNotEmpty()) return problem
        }
        return ""
    }
}
