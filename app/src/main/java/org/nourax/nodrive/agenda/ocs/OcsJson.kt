/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ocs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.nourax.nodrive.agenda.net.HttpResult
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The answer of an OCS call, with the envelope taken off. */
class OcsReply(
    val httpStatus: Int,
    val networkError: Boolean,
    val envelopeOk: Boolean,
    val data: JsonElement?,
    /** The answer said `Retry-After`: the server is not ready, wait. */
    val retryAfter: Boolean = false
) {
    val ok: Boolean get() = httpStatus in 200..299 && envelopeOk
    val notFound: Boolean get() = httpStatus == 404
    val refused: Boolean get() = httpStatus == 401 || httpStatus == 403
}

object OcsJson {
    fun parseElement(bytes: ByteArray): JsonElement? = try {
        JsonParser.parseString(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /** `{"ocs":{"meta":{...},"data":...}}` -> data. */
    fun parse(result: HttpResult): OcsReply {
        val root = parseElement(result.body)
        val ocs = (root as? JsonObject)?.get("ocs") as? JsonObject
        if (ocs == null || ocs.get("meta") !is JsonObject || !ocs.has("data")) {
            return OcsReply(result.status, result.networkError, false, null, result.header("Retry-After") != null)
        }
        return OcsReply(result.status, result.networkError, true, ocs.get("data"), result.header("Retry-After") != null)
    }
}

// ---- tolerant readers for Gson trees ---------------------------------------------------------------------

fun JsonElement?.isString(): Boolean = this != null && this.isJsonPrimitive && this.asJsonPrimitive.isString

fun JsonObject.str(key: String): String {
    val v = get(key)
    return if (v != null && v.isJsonPrimitive) v.asString else ""
}

fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val v = get(key)
    return if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) v.asBoolean else default
}

fun JsonObject.obj(key: String): JsonObject = (get(key) as? JsonObject) ?: JsonObject()

fun JsonObject.arr(key: String): JsonArray = (get(key) as? JsonArray) ?: JsonArray()

fun JsonObject.int(key: String, default: Int = 0): Int = numberOf(get(key), default.toDouble()).toInt()

fun JsonObject.num(key: String, default: Double): Double = numberOf(get(key), default)

/** A number, or a number written as text; [fallback] for null, missing or anything else. */
fun numberOf(value: JsonElement?, fallback: Double): Double {
    if (value == null || !value.isJsonPrimitive) return fallback
    val primitive = value.asJsonPrimitive
    return when {
        primitive.isNumber -> primitive.asDouble
        primitive.isString -> primitive.asString.toDoubleOrNull() ?: fallback
        else -> fallback
    }
}

fun intOf(value: JsonElement?, fallback: Int = 0): Int = numberOf(value, fallback.toDouble()).toInt()

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** "Y-m-d" or "Y-m-d H:i:s" text -> the date (first ten characters). */
fun dateOfText(value: JsonElement?): LocalDate? {
    if (value == null || !value.isString()) return null
    val text = value.asString
    if (text.length < 10) return null
    return try {
        LocalDate.parse(text.substring(0, 10))
    } catch (_: Exception) {
        null
    }
}

/** A Deck due date: "2026-11-02T00:00:00+00:00" (shown in the local zone) or a plain date. */
fun localDateOfIso(text: String, zone: ZoneId): LocalDate? {
    if (text.isEmpty()) return null
    if (text.length <= 10) return runCatching { LocalDate.parse(text) }.getOrNull()
    val instant: Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
    if (instant != null) return instant.atZone(zone).toLocalDate()
    return runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()
}

/** The keys with their types of a JSON object, sorted: "id:number". Values never appear. */
fun keySignature(obj: JsonObject): List<String> = obj.entrySet().map { (k, v) -> "$k:${jsonTypeName(v)}" }.sorted()

fun jsonTypeName(value: JsonElement?): String = when {
    value == null -> "undefined"
    value.isJsonNull -> "null"
    value.isJsonArray -> "array"
    value.isJsonObject -> "object"
    value.asJsonPrimitive.isString -> "string"
    value.asJsonPrimitive.isBoolean -> "bool"
    else -> "number"
}
