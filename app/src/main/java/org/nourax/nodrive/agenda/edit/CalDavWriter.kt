/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.ical.ICalText
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import java.time.Instant

/**
 * Events and to-dos through CalDAV, with ETags (design 9, plan 2.4). A change is: read the object (with its ETag), compare the
 * ETag with the one the person saw, change only the named properties, and write it back with `If-Match`. A different ETag, or
 * a 412 answer, is a conflict: the other change wins and nothing is overwritten. Delete is the only DELETE NoAgenda ever sends
 * (the server keeps the object in the calendar's trash bin).
 */
class CalDavWriter(private val http: AgendaHttp) {
    private val textHeaders = mapOf("Accept" to "text/calendar")

    fun send(command: EditCommand, now: Instant): SendResult = when (command.kind) {
        CommandKind.CAL_PATCH -> patch(command, now)
        CommandKind.CAL_CREATE -> create(command, now)
        CommandKind.CAL_DELETE -> delete(command)
        else -> SendResult.Conflict(ConflictReason.OTHER, "not_a_calendar_command")
    }

    private fun etagOf(result: HttpResult): String = result.header("ETag").orEmpty()

    private fun patch(command: EditCommand, now: Instant): SendResult {
        val href = command.loc("objectHref")
        if (href.isEmpty()) return SendResult.Conflict(ConflictReason.OTHER, "no_address")
        val got = http.send("GET", href, textHeaders)
        got.failure()?.let { return it }
        val etag = etagOf(got)
        if (!command.rebase && command.baseVersion.isNotEmpty() && etag.isNotEmpty() && !sameVersion(etag, command.baseVersion)) {
            return SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "etag", etag)
        }
        val text = got.text()
        val isTodo = command.loc("isTodo") == "true"
        val wanted = if (isTodo) "VTODO" else "VEVENT"
        val components = ICalText.parse(text).filter { it.type == wanted }
        if (components.size != 1 || components[0].has("RRULE") || components[0].has("RDATE") || components[0].has("RECURRENCE-ID")) {
            return SendResult.Conflict(ConflictReason.NOW_REPEATING)
        }
        val patched = CalendarEdit.patch(text, isTodo, command.change, now)
        val put = http.send(
            "PUT", href, mapOf("If-Match" to etag.ifEmpty { command.baseVersion }),
            patched.toByteArray(Charsets.UTF_8), "text/calendar; charset=utf-8"
        )
        put.failure()?.let { return it }
        return SendResult.Done(newVersion = etagOf(put).ifEmpty { currentEtag(href) })
    }

    private fun create(command: EditCommand, now: Instant): SendResult {
        val calendar = command.loc("calendarHref")
        val name = command.change[EditKeys.OBJECT_NAME].orEmpty()
        val uid = command.change[EditKeys.UID].orEmpty()
        if (calendar.isEmpty() || name.isEmpty() || uid.isEmpty()) return SendResult.Conflict(ConflictReason.OTHER, "no_address")
        val href = calendar.trimEnd('/') + "/" + name
        val isTodo = command.change[EditKeys.NEW_KIND] == "todo"
        val text = CalendarEdit.create(isTodo, uid, command.change, now)
        val put = http.send("PUT", href, mapOf("If-None-Match" to "*"), text.toByteArray(Charsets.UTF_8), "text/calendar; charset=utf-8")
        if (put.status == PRECONDITION_FAILED) {
            // A first try may have reached the server although its answer was lost: the same UID there means it is done.
            val existing = http.send("GET", href, textHeaders)
            if (existing.ok && ICalText.parse(existing.text()).any { it.text("UID") == uid }) return SendResult.Done(etagOf(existing))
            return SendResult.Conflict(ConflictReason.OTHER, "http412")
        }
        put.failure()?.let { return it }
        return SendResult.Done(newVersion = etagOf(put).ifEmpty { currentEtag(href) })
    }

    private fun delete(command: EditCommand): SendResult {
        val href = command.loc("objectHref")
        if (href.isEmpty()) return SendResult.Conflict(ConflictReason.OTHER, "no_address")
        var etag = command.baseVersion
        if (command.rebase || etag.isEmpty()) {
            val got = http.send("GET", href, textHeaders)
            if (got.status == NOT_FOUND) return SendResult.Done()
            got.failure()?.let { return it }
            etag = etagOf(got)
        }
        val headers = if (etag.isEmpty()) emptyMap() else mapOf("If-Match" to etag)
        val result = http.send("DELETE", href, headers)
        // Gone already is what the person wanted.
        if (result.status == NOT_FOUND) return SendResult.Done()
        result.failure()?.let { return it }
        return SendResult.Done()
    }

    private fun currentEtag(href: String): String {
        val got = http.send("GET", href, textHeaders)
        return if (got.ok) etagOf(got) else ""
    }

    private companion object {
        const val PRECONDITION_FAILED = 412
        const val NOT_FOUND = 404
    }
}
