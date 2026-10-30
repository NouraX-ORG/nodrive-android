/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult

/** What came of sending one command. */
sealed interface SendResult {
    /** The server has the change. [newVersion] is the version after it (empty if not known). */
    data class Done(
        val newVersion: String = "",
        /** Titles of the tasks that NoGantt moved because of this change. */
        val alsoMoved: List<String> = emptyList(),
        /** NoGantt's id for taking the change back. */
        val undoOp: String = "",
        /** A linked card was not moved along (NoGantt asks to confirm it there). */
        val cardNotMoved: Boolean = false,
        /** The server's answer did not match what was sent. */
        val checkInHome: Boolean = false,
        /** NoGantt's saved task, to check it against what was sent. */
        val answer: com.google.gson.JsonObject? = null
    ) : SendResult

    /** No network, or the server is not ready: the command stays in the queue and is tried again. */
    data object Offline : SendResult

    /**
     * The server answered with an error of its own (5xx). The command stays and is tried again, but only a few times: then it moves
     * to "Not sent", so one request that never works does not hold the queue back for ever.
     */
    data class ServerError(val status: Int) : SendResult

    /**
     * A change that was only half sent (NoBoard's label switch lost the network in the middle). The card has changed since the person
     * saw it, because of this very change, so the next try starts from [newBase] (empty: from the newest version).
     */
    data class HalfDone(val newBase: String = "") : SendResult

    data class Conflict(val reason: ConflictReason, val detail: String = "", val serverVersion: String = "") : SendResult
}

/** The ETag (or other version) of two answers is the same when the quotes and a weak mark are ignored. */
fun sameVersion(a: String, b: String): Boolean = normaliseVersion(a) == normaliseVersion(b)

fun normaliseVersion(version: String): String = version.trim().removePrefix("W/").trim().trim('"')

/**
 * What a failed answer means for every writer: wait, or a conflict with a reason. Null = the answer is fine. [appPassword]: the
 * answer comes from NoGantt or NoBoard, whose 401 is the "does not accept this sign-in" case of design 5 (the part of NoAgenda
 * turns into "Open in ..."), not a login to repair, so it is a refusal and does not hold the other changes back.
 */
fun HttpResult.failure(appPassword: Boolean = false): SendResult? = when {
    ok -> null
    networkError || status == 0 || status == REQUEST_TIMEOUT || status == TOO_MANY -> SendResult.Offline
    status >= SERVER_ERROR -> SendResult.ServerError(status)
    status == NOT_FOUND || status == GONE -> SendResult.Conflict(ConflictReason.GONE)
    status == UNAUTHORIZED && appPassword -> SendResult.Conflict(ConflictReason.REFUSED, "http401")
    status == UNAUTHORIZED -> SendResult.Offline // the sign-in is repaired in the app; the change waits
    status == FORBIDDEN -> SendResult.Conflict(ConflictReason.REFUSED, "http403")
    status == PRECONDITION_FAILED -> SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "http412", header("ETag").orEmpty())
    else -> SendResult.Conflict(ConflictReason.OTHER, "http$status")
}

private const val SERVER_ERROR = 500
private const val REQUEST_TIMEOUT = 408
private const val TOO_MANY = 429
private const val NOT_FOUND = 404
private const val GONE = 410
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val PRECONDITION_FAILED = 412

/** The headers of a JSON call to NoGantt or NoBoard (the OCS header is what the server's CSRF check wants). */
internal val JSON_HEADERS: Map<String, String> = mapOf("OCS-APIRequest" to "true", "Accept" to "application/json")

internal fun AgendaHttp.sendJson(method: String, path: String, body: com.google.gson.JsonElement): HttpResult =
    send(method, path, JSON_HEADERS, body.toString().toByteArray(Charsets.UTF_8), "application/json")
