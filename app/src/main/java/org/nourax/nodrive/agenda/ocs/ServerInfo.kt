/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ocs

import androidx.annotation.Keep
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.getOcs

/** Who the account is and which Deck version the server has (design 5, version guard). */
@Keep
data class ServerInfo(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val groups: List<String> = emptyList(),
    val isServerAdmin: Boolean = false,
    /** From the server's capabilities; empty when the board app is not on the server. */
    val deckVersion: String = "",
    /** The capabilities were read at least once. Without it an empty [deckVersion] means "not known yet", not "not installed". */
    val capabilitiesKnown: Boolean = false,
    val fetchedAtMs: Long = 0,
    val loaded: Boolean = false
) {
    /** Whether "the board app is (not) on the server" is known: the capabilities were read, or a Deck version was saved earlier. */
    val deckKnown: Boolean get() = capabilitiesKnown || deckVersion.isNotEmpty()
}

class ServerInfoResult(val ok: Boolean, val status: Int, val networkError: Boolean, val info: ServerInfo)

object ServerInfoClient {
    fun applyUser(data: JsonObject, previous: ServerInfo): ServerInfo {
        val uid = data.str("id")
        var display = data.str("displayname")
        if (display.isEmpty()) display = data.str("display-name")
        if (display.isEmpty()) display = uid
        val groups = data.arr("groups").mapNotNull { if (it.isString()) it.asString else null }
        return previous.copy(
            uid = uid,
            displayName = display,
            email = data.str("email"),
            groups = groups,
            isServerAdmin = "admin" in groups
        )
    }

    fun applyCapabilities(data: JsonObject, previous: ServerInfo): ServerInfo =
        previous.copy(deckVersion = data.obj("capabilities").obj("deck").str("version"), capabilitiesKnown = true)

    /** `cloud/user` for the id, name, e-mail and groups; `cloud/capabilities` for the Deck version. */
    fun fetch(http: AgendaHttp, previous: ServerInfo, nowMs: Long): ServerInfoResult {
        val userReply = OcsJson.parse(http.getOcs("ocs/v2.php/cloud/user?format=json"))
        val userData = userReply.data as? JsonObject
        if (!userReply.ok || userData == null) {
            return ServerInfoResult(false, userReply.httpStatus, userReply.networkError, previous)
        }
        var info = applyUser(userData, previous)
        val capsReply = OcsJson.parse(http.getOcs("ocs/v1.php/cloud/capabilities?format=json"))
        val capsData = capsReply.data as? JsonObject
        // A failed capabilities read keeps what was known (or "not known yet"); it never turns into "not installed".
        if (capsReply.ok && capsData != null) info = applyCapabilities(capsData, info)
        info = info.copy(loaded = true, fetchedAtMs = nowMs)
        return ServerInfoResult(true, capsReply.httpStatus, false, info)
    }
}
