/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.dav

import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.ical.ICalComponent
import org.nourax.nodrive.agenda.ical.ICalText
import org.nourax.nodrive.agenda.ical.ICalTime
import org.nourax.nodrive.agenda.ical.ZoneResolver
import org.nourax.nodrive.agenda.logic.SourceColours
import org.nourax.nodrive.agenda.net.AgendaHttp
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarReadOptions(
    /** The account's user id. */
    val davUser: String,
    val from: LocalDate,
    val to: LocalDate,
    val zone: ZoneId,
    val now: Instant = Instant.now(),
    /** Read every calendar again, even when its sync token has not changed. */
    val forceFull: Boolean = false,
    /** Which calendars are read (a Hidden calendar is not). */
    val wanted: (CalendarInfo) -> Boolean = { true },
    val resolver: ZoneResolver = ZoneResolver.DEFAULT
)

class CalendarReadResult(
    val ok: Boolean,
    val status: Int,
    val networkError: Boolean,
    val snapshot: CalendarSnapshot
)

/** CalDAV: the calendars of the account and their events and to-dos (design 5). Read only. */
object CalendarReader {
    private const val TAG = "AgendaCalendar"
    private const val DESCRIPTION_LIMIT = 2000

    val principalProps: List<Pair<String, String>> = listOf(
        Dav.NS_DAV to "displayname",
        Dav.NS_CALDAV to "calendar-home-set",
        Dav.NS_CALDAV to "calendar-user-address-set",
        Dav.NS_CALDAV to "schedule-default-calendar-URL"
    )

    val calendarProps: List<Pair<String, String>> = listOf(
        Dav.NS_DAV to "resourcetype",
        Dav.NS_DAV to "displayname",
        Dav.NS_APPLE to "calendar-color",
        Dav.NS_CALDAV to "supported-calendar-component-set",
        Dav.NS_DAV to "sync-token",
        Dav.NS_CS to "getctag",
        Dav.NS_OC to "owner-principal",
        Dav.NS_OC to "read-only",
        Dav.NS_NC to "owner-displayname",
        Dav.NS_DAV to "current-user-privilege-set",
        Dav.NS_CALDAV to "schedule-calendar-transp"
    )

    /** The sync window: from the first day of the previous month to 12 months ahead. */
    fun syncWindow(today: LocalDate): Pair<LocalDate, LocalDate> {
        val firstOfMonth = today.withDayOfMonth(1)
        return firstOfMonth.minusMonths(1) to firstOfMonth.plusMonths(13).minusDays(1)
    }

    private fun mailAddress(value: String): String {
        var v = value.trim()
        if (v.startsWith("mailto:", ignoreCase = true)) v = v.substring(7)
        return v.lowercase()
    }

    private fun lastSegment(href: String): String {
        val parts = href.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return decode(parts.last())
    }

    private fun decode(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        segment
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun classifyType(response: DavResponse): Pair<Boolean, Boolean> {
        val type = response.prop(Dav.NS_DAV, "resourcetype")
        return type.has("subscribed") to (type.has("calendar") || type.has("subscribed"))
    }

    fun classify(response: DavResponse, davUser: String): CalendarInfo {
        val href = response.href
        val uri = lastSegment(href)
        val type = response.prop(Dav.NS_DAV, "resourcetype")
        val (subscribed, isCalendar) = classifyType(response)
        val ignored = !isCalendar || type.has("schedule-inbox") || type.has("schedule-outbox") ||
            uri == "trashbin" || uri == "inbox" || uri == "outbox"
        var name = response.prop(Dav.NS_DAV, "displayname").textValue()
        if (name.isEmpty()) name = uri
        val color = SourceColours.display(response.prop(Dav.NS_APPLE, "calendar-color").textValue())

        val comps = response.prop(Dav.NS_CALDAV, "supported-calendar-component-set").compNames
        // Subscriptions and old servers do not list components.
        var hasEvents = comps.isEmpty()
        var hasTodos = false
        for (c in comps) {
            if (c.equals("VEVENT", ignoreCase = true)) hasEvents = true
            if (c.equals("VTODO", ignoreCase = true)) hasTodos = true
        }
        val syncToken = response.prop(Dav.NS_DAV, "sync-token").textValue()
        val ctag = response.prop(Dav.NS_CS, "getctag").textValue()

        val owner = response.prop(Dav.NS_OC, "owner-principal")
        val ownerPrincipal = owner.hrefs.firstOrNull() ?: owner.textValue()
        val ownerName = response.prop(Dav.NS_NC, "owner-displayname").textValue()
        val ownerPath = ownerPrincipal.trimEnd('/')
        val mine = if (ownerPath.isEmpty()) {
            !uri.contains("_shared_by_") && !uri.startsWith("app-generated--")
        } else {
            decode(ownerPath).endsWith("/users/$davUser")
        }

        val readOnlyText = response.prop(Dav.NS_OC, "read-only").textValue()
        var readOnly = readOnlyText == "1" || readOnlyText.equals("true", ignoreCase = true)
        val privileges = response.prop(Dav.NS_DAV, "current-user-privilege-set")
        if (privileges.descendants.isNotEmpty() && !privileges.has("write") && !privileges.has("write-content") && !privileges.has("all")) {
            readOnly = true
        }
        if (subscribed) readOnly = true
        val transparent = response.prop(Dav.NS_CALDAV, "schedule-calendar-transp").has("transparent")
        return CalendarInfo(
            href = href,
            uri = uri,
            name = name,
            color = color,
            ownerPrincipal = ownerPrincipal,
            ownerName = ownerName,
            mine = mine,
            readOnly = readOnly,
            subscribed = subscribed,
            birthdays = uri == "contact_birthdays",
            deckBoard = uri.startsWith("app-generated--deck--"),
            ignored = ignored,
            hasEvents = hasEvents,
            hasTodos = hasTodos,
            transparent = transparent,
            syncToken = syncToken,
            ctag = ctag
        )
    }

    /** The VEVENTs (or VTODOs) of one object. A component without the data it needs is left out. */
    fun parseObject(
        calendarUri: String,
        href: String,
        etag: String,
        ics: String,
        forTodos: Boolean,
        zone: ZoneId,
        resolver: ZoneResolver = ZoneResolver.DEFAULT
    ): List<CalRecord> {
        val wanted = if (forTodos) "VTODO" else "VEVENT"
        return ICalText.parse(ics).filter { it.type == wanted }
            .mapNotNull { fromComponent(calendarUri, href, etag, it, forTodos, zone, resolver) }
    }

    private fun fromComponent(
        calendarUri: String,
        href: String,
        etag: String,
        c: ICalComponent,
        isTodo: Boolean,
        zone: ZoneId,
        resolver: ZoneResolver
    ): CalRecord? {
        val categories = c.all("CATEGORIES").flatMap { it.textList() }
        val recurrenceId = c.first("RECURRENCE-ID")?.raw.orEmpty()
        val repeating = recurrenceId.isNotEmpty() || c.has("RRULE") || c.has("RDATE")
        val organizer = c.first("ORGANIZER")?.let { mailAddress(it.raw) }.orEmpty()
        val attendees = c.all("ATTENDEE").map { CalAttendee(mailAddress(it.raw), it.param("PARTSTAT").uppercase()) }
        var record = CalRecord(
            calendarUri = calendarUri,
            href = href,
            etag = etag,
            isTodo = isTodo,
            uid = c.text("UID"),
            summary = c.text("SUMMARY"),
            description = c.text("DESCRIPTION").take(DESCRIPTION_LIMIT),
            location = c.text("LOCATION"),
            status = c.text("STATUS").uppercase(),
            transp = c.text("TRANSP").uppercase(),
            agentId = c.text("X-NOURAX-AGENT-ID").trim(),
            categories = categories,
            recurrenceId = recurrenceId,
            repeating = repeating,
            organizer = organizer,
            attendees = attendees
        )
        val dtstartProp = c.first("DTSTART")
        val dtstart = if (dtstartProp != null) ICalTime.parse(dtstartProp, zone, resolver) else ICalTime()

        if (!isTodo) {
            if (!dtstart.valid) return null
            val dtendProp = c.first("DTEND")
            val dtend = if (dtendProp != null) ICalTime.parse(dtendProp, zone, resolver) else ICalTime()
            val durationSeconds = c.first("DURATION")?.let { ICalTime.parseDurationSeconds(it.raw) } ?: -1L
            if (dtstart.isDate) {
                val first = dtstart.date!!
                var last = first
                if (dtend.valid && dtend.isDate && dtend.date!!.isAfter(first)) {
                    last = dtend.date.minusDays(1)
                } else if (durationSeconds > 86400) {
                    last = first.plusDays((durationSeconds - 1) / 86400)
                }
                record = record.copy(allDay = true, startDate = first.toString(), endDate = last.toString())
            } else {
                val start = dtstart.utc!!
                var end = start
                if (dtend.valid && !dtend.isDate) {
                    end = dtend.utc!!
                } else if (durationSeconds > 0) {
                    end = start.plusSeconds(durationSeconds)
                }
                record = record.copy(
                    startMs = start.toEpochMilli(),
                    endMs = end.toEpochMilli(),
                    timeApproximated = dtstart.approximated
                )
            }
            return record
        }

        // To-do
        if (dtstart.valid) {
            record = if (dtstart.isDate) {
                record.copy(allDay = true, startDate = dtstart.date!!.toString())
            } else {
                record.copy(startMs = dtstart.utc!!.toEpochMilli(), timeApproximated = dtstart.approximated)
            }
        }
        c.first("DUE")?.let { dueProp ->
            val due = ICalTime.parse(dueProp, zone, resolver)
            if (due.valid) {
                record = if (due.isDate) {
                    record.copy(dueDate = due.date!!.toString())
                } else {
                    record.copy(dueMs = due.utc!!.toEpochMilli(), timeApproximated = record.timeApproximated || due.approximated)
                }
            }
        }
        val percent = c.text("PERCENT-COMPLETE").toIntOrNull() ?: -1
        record = record.copy(
            percent = percent,
            completed = c.has("COMPLETED") || record.status == "COMPLETED" || percent == 100
        )
        return record
    }

    private fun xmlHeaders(depth: Int): Map<String, String> = mapOf("Depth" to depth.toString())

    private fun propfind(http: AgendaHttp, path: String, depth: Int, props: List<Pair<String, String>>) =
        http.send("PROPFIND", path, xmlHeaders(depth), DavXml.propfindBody(props), "application/xml; charset=utf-8")

    /**
     * Reads the account's calendars. A calendar whose sync token has not changed since [previous] is not read again.
     * A calendar that fails keeps its old records, so nothing already shown disappears.
     */
    fun read(http: AgendaHttp, options: CalendarReadOptions, previous: CalendarSnapshot): CalendarReadResult {
        val principalPath = "remote.php/dav/principals/users/${encode(options.davUser)}/"
        val principalReply = propfind(http, principalPath, 0, principalProps)
        if (!principalReply.multistatus) return failed(principalReply.status, principalReply.networkError, previous)
        val principalMs = DavXml.parseMultistatus(principalReply.body)
        val principal = principalMs.responses.firstOrNull() ?: return failed(principalReply.status, false, previous)

        val addresses = principal.prop(Dav.NS_CALDAV, "calendar-user-address-set").hrefs.map { mailAddress(it) }
        val defaultUri = principal.prop(Dav.NS_CALDAV, "schedule-default-calendar-URL").hrefs.firstOrNull()?.let { lastSegment(it) }.orEmpty()
        val home = principal.prop(Dav.NS_CALDAV, "calendar-home-set").hrefs.firstOrNull()
            ?: "remote.php/dav/calendars/${encode(options.davUser)}/"

        val homeReply = propfind(http, home, 1, calendarProps)
        if (!homeReply.multistatus) return failed(homeReply.status, homeReply.networkError, previous)
        val calendars = DavXml.parseMultistatus(homeReply.body).responses
            .map { classify(it, options.davUser) }
            .filter { it.uri.isNotEmpty() && !it.ignored }

        val fromText = options.from.toString()
        val toText = options.to.toString()
        val records = ArrayList<CalRecord>()
        val failedParts = HashSet<Pair<String, Boolean>>()
        val failedCalendars = ArrayList<String>()
        val readUris = ArrayList<String>()
        val rangeFrom = options.from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val rangeTo = options.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

        for (cal in calendars) {
            // A calendar the person turned off, and the board app's own calendars, are not read.
            if (cal.deckBoard || !options.wanted(cal)) continue
            if (!cal.hasEvents && !cal.hasTodos) continue
            val old = previous.calendar(cal.uri)
            val sameWindow = previous.windowFrom == fromText && previous.windowTo == toText && cal.uri !in previous.failedCalendars
            val unchanged = !options.forceFull && old != null && sameWindow && cal.uri in previous.readUris &&
                ((cal.syncToken.isNotEmpty() && old.syncToken == cal.syncToken) ||
                    (cal.syncToken.isEmpty() && cal.ctag.isNotEmpty() && old.ctag == cal.ctag))
            if (unchanged) {
                records += previous.records.filter { it.calendarUri == cal.uri }
                readUris += cal.uri
                continue
            }
            if (cal.hasEvents) {
                val reply = http.send(
                    "REPORT",
                    cal.href,
                    xmlHeaders(1),
                    DavXml.eventQueryBody(rangeFrom, rangeTo),
                    "application/xml; charset=utf-8"
                )
                if (reply.multistatus) {
                    records += recordsOf(cal, reply.body, false, options)
                } else {
                    AgendaLog.i(TAG, "event query answered ${reply.status}")
                    failedParts += cal.uri to false
                    if (cal.uri !in failedCalendars) failedCalendars += cal.uri
                }
            }
            if (cal.hasTodos) {
                val reply = http.send("REPORT", cal.href, xmlHeaders(1), DavXml.todoQueryBody(), "application/xml; charset=utf-8")
                if (reply.multistatus) {
                    records += recordsOf(cal, reply.body, true, options)
                } else {
                    AgendaLog.i(TAG, "to-do query answered ${reply.status}")
                    failedParts += cal.uri to true
                    if (cal.uri !in failedCalendars) failedCalendars += cal.uri
                }
            }
        }
        for (cal in calendars) {
            // Complete for the window: read now, or kept from before after a failed read.
            if (cal.uri in readUris || cal.deckBoard || !options.wanted(cal) || !(cal.hasEvents || cal.hasTodos)) continue
            if (cal.uri !in failedCalendars || cal.uri in previous.readUris) readUris += cal.uri
        }
        // A part (events or to-dos) of a calendar that could not be read keeps its old records. Only that part.
        previous.records.filterTo(records) { (it.calendarUri to it.isTodo) in failedParts }

        val snapshot = CalendarSnapshot(
            fetchedAtMs = options.now.toEpochMilli(),
            principalHref = principal.href,
            displayName = principal.prop(Dav.NS_DAV, "displayname").textValue(),
            addresses = addresses,
            defaultCalendarUri = defaultUri,
            calendars = calendars,
            records = records,
            failedCalendars = failedCalendars,
            readUris = readUris,
            windowFrom = fromText,
            windowTo = toText,
            loaded = true
        )
        return CalendarReadResult(true, 207, false, snapshot)
    }

    private fun recordsOf(cal: CalendarInfo, body: ByteArray, todos: Boolean, options: CalendarReadOptions): List<CalRecord> {
        val ms = DavXml.parseMultistatus(body)
        val out = ArrayList<CalRecord>()
        for (response in ms.responses) {
            val data = response.prop(Dav.NS_CALDAV, "calendar-data").textValue()
            if (data.isEmpty()) continue
            val etag = response.prop(Dav.NS_DAV, "getetag").textValue()
            out += parseObject(cal.uri, response.href, etag, data, todos, options.zone, options.resolver)
        }
        return out
    }

    private fun failed(status: Int, networkError: Boolean, previous: CalendarSnapshot) =
        CalendarReadResult(false, status, networkError, previous)
}
