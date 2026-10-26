/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Reads the answer of GET /ocs/v2.php/core/navigation/apps (design section 2). */
object WorkspaceAppsParser {
    private const val TYPE_LINK = "link"
    // NoDrive has its own Files, Activity and Photos screens: the server's entries would only duplicate them.
    private val HIDDEN_APPS = setOf("files", "activity", "photos")
    private const val APP_EXTERNAL = "external"

    /** Throws a JsonParseException when the body is not an OCS answer. */
    fun parse(json: String, base: String): List<WorkspaceApp> {
        val data = try {
            JsonParser.parseString(json).asJsonObject.getAsJsonObject("ocs").get("data")
        } catch (e: RuntimeException) {
            throw JsonParseException("not an OCS answer", e)
        }
        if (data == null || !data.isJsonArray) return emptyList()
        return data.asJsonArray.mapNotNull { entry ->
            entry.takeIf(JsonElement::isJsonObject)?.let { toApp(it.asJsonObject, base) }
        }
    }

    private fun toApp(entry: JsonObject, base: String): WorkspaceApp? {
        val id = entry.text("id")
        val appId = entry.text("app")
        val name = entry.text("name")?.trim()
        if (id.isNullOrEmpty() || name.isNullOrEmpty() || entry.text("type") != TYPE_LINK) return null
        if (id in HIDDEN_APPS || appId in HIDDEN_APPS || appId == APP_EXTERNAL) return null
        val href = WorkspaceOrigin.resolve(entry.text("href"), base) ?: return null
        return WorkspaceApp(id, appId, name, href, WorkspaceOrigin.resolve(entry.text("icon"), base))
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
