/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import java.time.LocalDate

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
        val answer: JsonObject? = null,
        /** The dates NoGantt really saved (it may snap a moved task to working days); null = not known. */
        val savedStart: LocalDate? = null,
        val savedEnd: LocalDate? = null
    ) : SendResult

    /**
     * No network, or the server is not ready (502, 503 and 504, which Nextcloud answers in maintenance mode, or an answer with
     * `Retry-After`): the command stays in the queue and is tried again. Waiting here is never counted, so a maintenance window of
     * any length does not move a change to "Not sent".
     */
    data object Offline : SendResult

    /**
     * The server answered with an error of its own (500, 501, 505 and so on; not the "not ready" answers, which are [Offline]). The
     * command stays and is tried again, but only a few times in total: then it moves to "Not sent" (where "Send again" is offered), so
     * one request that never works does not hold the queue back for ever.
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
 * What a 5xx answer means: 502, 503 and 504 (a server in maintenance mode or an upgrade, a proxy with no server behind it) and any
 * answer that says `Retry-After` are "not ready, wait" ([SendResult.Offline]); the others are a server error of its own.
 */
fun serverAnswer(status: Int, retryAfter: Boolean = false): SendResult =
    if (retryAfter || status == BAD_GATEWAY || status == SERVICE_UNAVAILABLE || status == GATEWAY_TIMEOUT) {
        SendResult.Offline
    } else {
        SendResult.ServerError(status)
    }

/**
 * What a failed answer means for every writer: wait, or a conflict with a reason. Null = the answer is fine. [appPassword]: the
 * answer comes from NoGantt or NoBoard, whose 401 is the "does not accept this sign-in" case of design 5 (the part of NoAgenda
 * turns into "Open in ..."), not a login to repair, so it is a refusal and does not hold the other changes back.
 */
fun HttpResult.failure(appPassword: Boolean = false): SendResult? = when {
    ok -> null
    refused -> SendResult.Conflict(ConflictReason.OTHER, WRITE_REFUSED)
    networkError || status == 0 || status == REQUEST_TIMEOUT || status == TOO_MANY -> SendResult.Offline
    status >= SERVER_ERROR -> serverAnswer(status, header("Retry-After") != null)
    status == NOT_FOUND || status == GONE -> SendResult.Conflict(ConflictReason.GONE)
    status == UNAUTHORIZED && appPassword -> SendResult.Conflict(ConflictReason.REFUSED, "http401")
    status == UNAUTHORIZED -> SendResult.Offline // the sign-in is repaired in the app; the change waits
    status == FORBIDDEN -> SendResult.Conflict(ConflictReason.REFUSED, "http403")
    status == PRECONDITION_FAILED -> SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "http412", header("ETag").orEmpty())
    else -> SendResult.Conflict(ConflictReason.OTHER, "http$status")
}

/** The detail of a conflict for a write that NoAgenda's own rules do not allow (it is never sent). */
const val WRITE_REFUSED = "write_refused"

private const val SERVER_ERROR = 500
private const val BAD_GATEWAY = 502
private const val SERVICE_UNAVAILABLE = 503
private const val GATEWAY_TIMEOUT = 504
private const val REQUEST_TIMEOUT = 408
private const val TOO_MANY = 429
private const val NOT_FOUND = 404
private const val GONE = 410
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val PRECONDITION_FAILED = 412

/** The headers of a JSON call to NoGantt or NoBoard (the OCS header is what the server's CSRF check wants). */
internal val JSON_HEADERS: Map<String, String> = mapOf("OCS-APIRequest" to "true", "Accept" to "application/json")

internal fun AgendaHttp.sendJson(method: String, path: String, body: JsonElement): HttpResult =
    send(method, path, JSON_HEADERS, body.toString().toByteArray(Charsets.UTF_8), "application/json")
