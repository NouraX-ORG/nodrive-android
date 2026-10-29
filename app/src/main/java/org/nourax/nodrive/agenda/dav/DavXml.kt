/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.dav

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

object Dav {
    const val NS_DAV = "DAV:"
    const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
    const val NS_CS = "http://calendarserver.org/ns/"
    const val NS_APPLE = "http://apple.com/ns/ical/"
    const val NS_OC = "http://owncloud.org/ns"
    const val NS_NC = "http://nextcloud.org/ns"

    fun key(ns: String, local: String): String = "$ns|$local"
}

/** One property of a response. Only the answers the readers need are kept. */
class DavProp {
    val text = StringBuilder()
    val hrefs = ArrayList<String>()

    /** Local names of every element inside the property (for resourcetype, privilege sets). */
    val descendants = ArrayList<String>()
    val compNames = ArrayList<String>()

    fun has(local: String): Boolean = descendants.contains(local)

    fun textValue(): String = text.toString().trim()
}

class DavResponse(
    var href: String = "",
    var status: Int = 0,
    val props: MutableMap<String, DavProp> = LinkedHashMap()
) {
    fun prop(ns: String, local: String): DavProp = props[Dav.key(ns, local)] ?: DavProp()
}

class DavMultistatus(
    val valid: Boolean,
    val responses: List<DavResponse>,
    val syncToken: String,
    /** Local names of the elements of an `<error>` body (for example "valid-sync-token"). */
    val errorConditions: List<String>
)

object DavXml {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val NAMESPACES = listOf(
        "d" to Dav.NS_DAV, "c" to Dav.NS_CALDAV, "cs" to Dav.NS_CS, "ic" to Dav.NS_APPLE, "oc" to Dav.NS_OC, "nc" to Dav.NS_NC
    )

    private fun prefixOf(ns: String): String = NAMESPACES.first { it.second == ns }.first

    private fun root(name: String, body: StringBuilder.() -> Unit): ByteArray {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append('<').append(name)
        NAMESPACES.forEach { (prefix, ns) -> sb.append(" xmlns:").append(prefix).append("=\"").append(ns).append('"') }
        sb.append('>')
        sb.body()
        sb.append("</").append(name).append('>')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** PROPFIND with the named properties ((namespace, local name) pairs). */
    fun propfindBody(props: List<Pair<String, String>>): ByteArray = root("d:propfind") {
        append("<d:prop>")
        props.forEach { (ns, local) -> append('<').append(prefixOf(ns)).append(':').append(local).append("/>") }
        append("</d:prop>")
    }

    /** The events of a time range; the server expands repeating events into one VEVENT per occurrence. */
    fun eventQueryBody(from: Instant, to: Instant): ByteArray = root("c:calendar-query") {
        val f = STAMP.format(from)
        val t = STAMP.format(to)
        append("<d:prop><d:getetag/><c:calendar-data><c:expand start=\"$f\" end=\"$t\"/></c:calendar-data></d:prop>")
        append("<c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VEVENT\">")
        append("<c:time-range start=\"$f\" end=\"$t\"/>")
        append("</c:comp-filter></c:comp-filter></c:filter>")
    }

    /** The not completed to-dos. Never with `expand`: the server drops every VTODO on expand. */
    fun todoQueryBody(): ByteArray = root("c:calendar-query") {
        append("<d:prop><d:getetag/><c:calendar-data/></d:prop>")
        append("<c:filter><c:comp-filter name=\"VCALENDAR\"><c:comp-filter name=\"VTODO\">")
        append("<c:prop-filter name=\"COMPLETED\"><c:is-not-defined/></c:prop-filter>")
        append("</c:comp-filter></c:comp-filter></c:filter>")
    }

    fun statusCodeOf(statusLine: String): Int {
        val parts = statusLine.trim().split(' ').filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
    }

    /** Parses a 207 body. A DOCTYPE is refused (no entity expansion, no external files). */
    fun parseMultistatus(xml: ByteArray): DavMultistatus {
        val handler = MultistatusHandler()
        // A server never sends a DOCTYPE. Refusing it here works on every XML parser (Android's has no such feature).
        if (String(xml, Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true)) {
            return DavMultistatus(false, emptyList(), "", emptyList())
        }
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            // The JVM parser knows these features; Android's parser may not, and then the pre-check above is the guard.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
        } catch (_: Exception) {
            return DavMultistatus(false, emptyList(), "", handler.errorConditions)
        }
        return DavMultistatus(handler.valid, handler.responses, handler.syncToken, handler.errorConditions)
    }

    private class MultistatusHandler : DefaultHandler() {
        var valid = false
        val responses = ArrayList<DavResponse>()
        var syncToken = ""
        val errorConditions = ArrayList<String>()

        private class Frame(val ns: String, val local: String)

        private val stack = ArrayList<Frame>()
        private var response: DavResponse? = null
        private var propstatProps = LinkedHashMap<String, DavProp>()
        private var propstatStatus = 0
        private var inPropstat = false
        private var propKey = ""
        private var prop: DavProp? = null
        private var propDepth = -1
        private val text = StringBuilder()
        private var collecting = ""
        private var nestedHref = false

        override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
            stack += Frame(uri, localName)
            val depth = stack.size
            val isDav = uri == Dav.NS_DAV
            if (depth == 1) {
                valid = localName == "multistatus" && isDav
                return
            }
            val current = prop
            if (propDepth > 0 && depth > propDepth && current != null) {
                current.descendants += localName
                if (localName == "href" && isDav) {
                    nestedHref = true
                    text.setLength(0)
                }
                if (localName == "comp") current.compNames += atts.getValue("name").orEmpty()
                return
            }
            if (depth == 2 && isDav && localName == "response") {
                response = DavResponse()
            } else if (depth == 2 && isDav && localName == "sync-token") {
                collecting = "sync-token"
                text.setLength(0)
            } else if (depth == 2 && !valid) {
                errorConditions += localName
            } else if (response != null && depth == 3 && isDav && localName == "href") {
                collecting = "href"
                text.setLength(0)
            } else if (response != null && depth == 3 && isDav && localName == "status") {
                collecting = "status"
                text.setLength(0)
            } else if (response != null && depth == 3 && isDav && localName == "propstat") {
                inPropstat = true
                propstatProps = LinkedHashMap()
                propstatStatus = 0
            } else if (inPropstat && depth == 4 && isDav && localName == "status") {
                collecting = "propstat-status"
                text.setLength(0)
            } else if (inPropstat && depth == 5 && stack[3].local == "prop") {
                propKey = Dav.key(uri, localName)
                prop = DavProp()
                propDepth = depth
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collecting.isNotEmpty() || nestedHref) {
                text.append(ch, start, length)
            } else {
                val current = prop
                if (current != null && stack.size == propDepth) current.text.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val depth = stack.size
            if (nestedHref && localName == "href" && uri == Dav.NS_DAV && depth > propDepth) {
                prop?.hrefs?.add(text.toString().trim())
                nestedHref = false
            }
            if (depth == propDepth) {
                prop?.let { propstatProps[propKey] = it }
                prop = null
                propDepth = -1
            } else if (collecting.isNotEmpty() && depth <= 4) {
                val value = text.toString().trim()
                when (collecting) {
                    "sync-token" -> syncToken = value
                    "href" -> response?.href = value
                    "status" -> response?.status = statusCodeOf(value)
                    "propstat-status" -> propstatStatus = statusCodeOf(value)
                }
                collecting = ""
            } else if (inPropstat && depth == 3 && localName == "propstat") {
                // Only the properties the server answered with success (200) count.
                if (propstatStatus in 200..299 || propstatStatus == 0) {
                    response?.props?.putAll(propstatProps)
                }
                inPropstat = false
            } else if (depth == 2 && localName == "response") {
                response?.let { responses += it }
                response = null
            }
            stack.removeAt(stack.size - 1)
        }
    }
}
