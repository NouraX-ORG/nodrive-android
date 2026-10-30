/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.dav

import org.nourax.nodrive.agenda.dav.Dav
import org.nourax.nodrive.agenda.dav.DavXml
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import java.net.URLDecoder
import java.net.URLEncoder

const val NS_CARDDAV = "urn:ietf:params:xml:ns:carddav"
private const val XML_TYPE = "application/xml; charset=utf-8"

/** One object the server listed: its address and ETag. */
class RemoteEntry(val href: String, val etag: String)

/** One object with its text. */
class RemoteObject(val href: String, val etag: String, val data: String)

/** What a collection reports since the last token (RFC 6578 `sync-collection`). */
class CollectionDelta(
    val ok: Boolean,
    val status: Int = 0,
    val networkError: Boolean = false,
    /** The server did not know the token: [DavSync.delta] starts again without one. */
    val tokenWasInvalid: Boolean = false,
    val newToken: String = "",
    val changed: List<RemoteEntry> = emptyList(),
    /** Addresses the server says are gone. Never set by a listing from an empty token. */
    val removed: List<String> = emptyList(),
    /** The listing is complete (no token was given). */
    val full: Boolean = false
)

object DavSync {
    fun xmlEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun encodeSegment(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    fun decodeSegment(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        segment
    }

    private fun headers(depth: Int?): Map<String, String> = if (depth == null) emptyMap() else mapOf("Depth" to depth.toString())

    /** `sync-collection` (level 1) with the stored token. An empty token lists everything. */
    fun delta(http: AgendaHttp, collectionHref: String, token: String): CollectionDelta {
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:sync-collection xmlns:d=\"DAV:\">" +
            "<d:sync-token>" + xmlEscape(token) + "</d:sync-token><d:sync-level>1</d:sync-level>" +
            "<d:prop><d:getetag/></d:prop></d:sync-collection>").toByteArray(Charsets.UTF_8)
        val reply = http.send("REPORT", collectionHref, headers(1), body, XML_TYPE)
        if (!reply.multistatus) {
            if (token.isNotEmpty() && reply.status in 400..409) {
                // An unknown or expired token (403 "valid-sync-token"): list everything again.
                val again = delta(http, collectionHref, "")
                return CollectionDelta(
                    again.ok, again.status, again.networkError, true, again.newToken, again.changed, again.removed, again.full
                )
            }
            return CollectionDelta(false, reply.status, reply.networkError)
        }
        val ms = DavXml.parseMultistatus(reply.body)
        if (!ms.valid) return CollectionDelta(false, reply.status)
        val changed = ArrayList<RemoteEntry>()
        val removed = ArrayList<String>()
        for (r in ms.responses) {
            if (r.href.isEmpty() || r.href.endsWith("/")) continue
            if (r.status == 404) {
                removed += r.href
                continue
            }
            changed += RemoteEntry(r.href, r.prop(Dav.NS_DAV, "getetag").textValue())
        }
        return CollectionDelta(true, reply.status, newToken = ms.syncToken, changed = changed, removed = removed, full = token.isEmpty())
    }

    /** The objects with their text, [batch] addresses per request. A missing object is simply not in the answer. */
    fun multiget(http: AgendaHttp, collectionHref: String, hrefs: List<String>, calendar: Boolean, batch: Int = 100): MultigetResult {
        val out = LinkedHashMap<String, RemoteObject>()
        for (chunk in hrefs.chunked(batch)) {
            val list = chunk.joinToString("") { "<d:href>" + xmlEscape(it) + "</d:href>" }
            val xml = if (calendar) {
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-multiget xmlns:d=\"DAV:\" xmlns:c=\"${Dav.NS_CALDAV}\">" +
                    "<d:prop><d:getetag/><c:calendar-data/></d:prop>$list</c:calendar-multiget>"
            } else {
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><card:addressbook-multiget xmlns:d=\"DAV:\" xmlns:card=\"$NS_CARDDAV\">" +
                    "<d:prop><d:getetag/><card:address-data/></d:prop>$list</card:addressbook-multiget>"
            }
            val reply = http.send("REPORT", collectionHref, headers(1), xml.toByteArray(Charsets.UTF_8), XML_TYPE)
            if (!reply.multistatus) return MultigetResult(false, out, reply.status, reply.networkError)
            val ms = DavXml.parseMultistatus(reply.body)
            if (!ms.valid) return MultigetResult(false, out, reply.status, false)
            for (r in ms.responses) {
                if (r.status == 404 || r.href.isEmpty()) continue
                val data = if (calendar) r.prop(Dav.NS_CALDAV, "calendar-data") else r.prop(NS_CARDDAV, "address-data")
                val text = data.text.toString()
                if (text.isEmpty()) continue
                out[r.href] = RemoteObject(r.href, r.prop(Dav.NS_DAV, "getetag").textValue(), text)
            }
        }
        return MultigetResult(true, out, 207, false)
    }

    class MultigetResult(val ok: Boolean, val objects: Map<String, RemoteObject>, val status: Int, val networkError: Boolean)

    /** One object by GET (with its ETag header). */
    fun get(http: AgendaHttp, href: String, accept: String): HttpResult = http.send("GET", href, mapOf("Accept" to accept))

    /** The ETag of one object now (PROPFIND, depth 0). Empty when it can not be read. */
    fun etagOf(http: AgendaHttp, href: String): String {
        val reply = http.send("PROPFIND", href, headers(0), DavXml.propfindBody(listOf(Dav.NS_DAV to "getetag")), XML_TYPE)
        if (!reply.multistatus) return ""
        return DavXml.parseMultistatus(reply.body).responses.firstOrNull()?.prop(Dav.NS_DAV, "getetag")?.textValue().orEmpty()
    }

    /** An ETag without the weak mark and the quotes, to compare two of them. */
    fun normalEtag(etag: String): String = etag.trim().removePrefix("W/").trim('"')

    /** An ETag as the `If-Match` header wants it. */
    fun quoted(etag: String): String {
        val t = etag.trim()
        return if (t.isEmpty() || t.startsWith("\"") || t.startsWith("W/")) t else "\"$t\""
    }

    /** The last segment of an address, decoded. */
    fun lastSegment(href: String): String {
        val parts = href.split('/').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "" else decodeSegment(parts.last())
    }
}
