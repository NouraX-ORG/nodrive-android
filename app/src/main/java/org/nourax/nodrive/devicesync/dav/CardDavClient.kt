/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.dav

import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.dav.Dav
import org.nourax.nodrive.agenda.dav.DavResponse
import org.nourax.nodrive.agenda.dav.DavXml
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.devicesync.model.RemoteCollection

/** How a write ended. */
sealed class WriteResult {
    /** Done; [etag] is the object's ETag now (may be empty when the server did not say). */
    class Ok(val etag: String) : WriteResult()

    /** 412: the object changed (or is gone, or exists) on the server. */
    object Precondition : WriteResult()

    /** 404: the object is not on the server. */
    object Gone : WriteResult()

    /** Anything else; [status] 0 means no network. */
    class Failed(val status: Int, val network: Boolean) : WriteResult()
}

/**
 * CalDAV and CardDAV objects of the person's own account: the lists of calendars and address books, and writes with `If-Match` /
 * `If-None-Match: *`. Written for NoDrive from the RFCs (4791, 6352, 6578); no code of any GPL sync library.
 */
class CardDavClient(private val http: AgendaHttp, val davUser: String) {
    private val xmlType = "application/xml; charset=utf-8"

    fun calendarHome(): String = "remote.php/dav/calendars/${DavSync.encodeSegment(davUser)}/"

    fun addressBookHome(): String = "remote.php/dav/addressbooks/users/${DavSync.encodeSegment(davUser)}/"

    /** The calendars that can hold events. `null` when the server could not be asked. */
    fun calendars(exclude: Set<String> = emptySet()): List<RemoteCollection>? {
        val reply = http.send(
            "PROPFIND", calendarHome(), mapOf("Depth" to "1"), DavXml.propfindBody(CalendarReader.calendarProps), xmlType
        )
        if (!reply.multistatus) return null
        val ms = DavXml.parseMultistatus(reply.body)
        if (!ms.valid) return null
        val out = ArrayList<RemoteCollection>()
        for (response in ms.responses) {
            val info = CalendarReader.classify(response, davUser)
            if (info.uri.isEmpty() || info.ignored) continue
            // Not offered: lists with only to-dos (tasks never go to the phone), the board app's calendars and NoGantt's copy calendar.
            // The birthday calendar and subscriptions are offered but off by default.
            if (!info.hasEvents || info.deckBoard || info.uri in exclude) continue
            val off = when {
                info.birthdays -> "birthdays"
                info.subscribed -> "subscription"
                info.readOnly -> "readonly"
                else -> ""
            }
            out += RemoteCollection(
                href = info.href, kind = RemoteCollection.CALENDAR, name = info.name, colorText = info.color,
                readOnly = info.readOnly || info.subscribed, offReason = off
            )
        }
        return out
    }

    /** The address books (the system address book is read only and off by default). `null` when the server could not be asked. */
    fun addressBooks(): List<RemoteCollection>? {
        val props = listOf(
            Dav.NS_DAV to "resourcetype", Dav.NS_DAV to "displayname", Dav.NS_DAV to "current-user-privilege-set",
            Dav.NS_OC to "read-only", Dav.NS_DAV to "sync-token"
        )
        val reply = http.send("PROPFIND", addressBookHome(), mapOf("Depth" to "1"), DavXml.propfindBody(props), xmlType)
        if (!reply.multistatus) return null
        val ms = DavXml.parseMultistatus(reply.body)
        if (!ms.valid) return null
        val out = ArrayList<RemoteCollection>()
        for (response in ms.responses) {
            if (!isAddressBook(response)) continue
            val uri = DavSync.lastSegment(response.href)
            val name = response.prop(Dav.NS_DAV, "displayname").textValue().ifEmpty { uri }
            val readOnlyText = response.prop(Dav.NS_OC, "read-only").textValue()
            var readOnly = readOnlyText == "1" || readOnlyText.equals("true", ignoreCase = true)
            val privileges = response.prop(Dav.NS_DAV, "current-user-privilege-set")
            if (privileges.descendants.isNotEmpty() && !privileges.has("write") && !privileges.has("write-content") && !privileges.has("all")) {
                readOnly = true
            }
            if (uri == "z-server-generated--system") readOnly = true
            out += RemoteCollection(
                href = response.href, kind = RemoteCollection.CONTACTS, name = name, colorText = "",
                readOnly = readOnly, offReason = if (readOnly) "readonly" else ""
            )
        }
        return out
    }

    /** NoGantt's copy calendar (it holds copies of tasks): its name, or empty. Asked of the account's own server; any failure means none. */
    fun ganttCopyCalendar(): String {
        val reply = http.send(
            "GET", "ocs/v2.php/apps/nc_gantt/api/v1/task-sync", mapOf("OCS-APIRequest" to "true", "Accept" to "application/json")
        )
        if (!reply.ok) return ""
        val text = reply.text()
        if (!Regex("\"enabled\"\s*:\s*(true|1)").containsMatchIn(text)) return ""
        return Regex("\"calendar_uri\"\s*:\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
    }

    private fun isAddressBook(response: DavResponse): Boolean = response.prop(Dav.NS_DAV, "resourcetype").has("addressbook")

    /** PUT a new object: fails with 412 when the address is taken. */
    fun create(href: String, data: String, contentType: String): WriteResult =
        put(href, data, contentType, mapOf("If-None-Match" to "*"))

    /** PUT a changed object: fails with 412 when the ETag moved on. */
    fun replace(href: String, etag: String, data: String, contentType: String): WriteResult =
        put(href, data, contentType, if (etag.isEmpty()) emptyMap() else mapOf("If-Match" to DavSync.quoted(etag)))

    private fun put(href: String, data: String, contentType: String, conditions: Map<String, String>): WriteResult {
        val reply = http.send("PUT", href, conditions, data.toByteArray(Charsets.UTF_8), contentType)
        return when {
            reply.ok -> WriteResult.Ok(reply.header("ETag").orEmpty())
            reply.status == 412 -> WriteResult.Precondition
            reply.status == 404 -> WriteResult.Gone
            else -> WriteResult.Failed(reply.status, reply.networkError)
        }
    }

    /** DELETE an object, only with its ETag (`If-Match`). The server keeps a calendar object in its trash bin. */
    fun delete(href: String, etag: String): WriteResult {
        val reply = http.send("DELETE", href, if (etag.isEmpty()) emptyMap() else mapOf("If-Match" to DavSync.quoted(etag)))
        return when {
            reply.ok -> WriteResult.Ok("")
            reply.status == 404 -> WriteResult.Gone
            reply.status == 412 -> WriteResult.Precondition
            else -> WriteResult.Failed(reply.status, reply.networkError)
        }
    }
}
