/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.net

/**
 * The answer of one request. A request that never got an answer has [status] 0 and [networkError]. A write that NoAgenda's own rules
 * do not allow (WritePolicy, another origin) is never sent and has [refused]: it is not a lost network, and waiting does not help.
 */
class HttpResult(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val networkError: Boolean = false,
    val refused: Boolean = false
) {
    val ok: Boolean get() = status in 200..299
    val multistatus: Boolean get() = status == 207

    fun text(): String = String(body, Charsets.UTF_8)

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * The one way NoAgenda talks to the server (a seam for tests). [path] is relative to the account's server
 * ("remote.php/dav/...") or an absolute path ("/remote.php/dav/calendars/ali/work/") as DAV hrefs are written.
 * It never leaves the account's own origin.
 */
interface AgendaHttp {
    fun send(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null
    ): HttpResult
}

/** GET with the headers every NoGantt, Deck and OCS call needs. */
fun AgendaHttp.getOcs(path: String): HttpResult = send(
    "GET",
    path,
    mapOf("OCS-APIRequest" to "true", "Accept" to "application/json")
)
