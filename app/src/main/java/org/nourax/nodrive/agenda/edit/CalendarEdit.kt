/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.ical.ICalPatch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The semantic change of an event or a to-do (EditKeys) turned into properties (plan 2.4). Times are written in UTC with `Z`, so
 * no time zone definition has to be made. The agent id is written as `X-NOURAX-AGENT-ID` and, as a second copy that survives
 * programs which drop unknown properties, as the registry's calendar tag in `CATEGORIES` (design 4.2).
 *
 * A to-do's start and due date are always written in the same value type (both a day, or both a day and time): sabre/vobject
 * refuses a to-do whose DTSTART and DUE differ in type, and the server then answers 415. The one that is not being changed
 * decides, and the time of day of a timed value is kept ([zone] is the person's, for values written in UTC).
 */
object CalendarEdit {
    const val AGENT_PROPERTY = "X-NOURAX-AGENT-ID"
    private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Applies [change] to the one event or to-do of [text]. Returns the new text. */
    fun patch(text: String, isTodo: Boolean, change: Map<String, String>, now: Instant, zone: ZoneId = ZoneOffset.UTC): String {
        val patch = ICalPatch(text, if (isTodo) "VTODO" else "VEVENT")
        apply(patch, isTodo, change, zone)
        patch.stamp(now)
        return patch.build()
    }

    /** True when [text] already has every change: only the stamps (`DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`) would differ. */
    fun alreadyApplied(text: String, isTodo: Boolean, change: Map<String, String>, zone: ZoneId = ZoneOffset.UTC): Boolean =
        withoutStamps(patch(text, isTodo, change, Instant.EPOCH, zone)) == withoutStamps(text)

    private fun withoutStamps(text: String): String = text.replace("\r\n", "\n").split("\n").filterNot { line ->
        val upper = line.uppercase()
        upper.startsWith("DTSTAMP") || upper.startsWith("LAST-MODIFIED") || upper.startsWith("SEQUENCE")
    }.joinToString("\n")

    private fun apply(patch: ICalPatch, isTodo: Boolean, change: Map<String, String>, zone: ZoneId) {
        change[EditKeys.SUMMARY]?.let { patch.setText("SUMMARY", it) }
        change[EditKeys.DESCRIPTION]?.let { if (it.isEmpty()) patch.remove("DESCRIPTION") else patch.setText("DESCRIPTION", it) }
        if (!isTodo) {
            val start = EditKeys.parse(change[EditKeys.START])
            val end = EditKeys.parse(change[EditKeys.END])
            if (start != null || end != null) {
                patch.remove("DURATION")
                when (start) {
                    is EditKeys.Value.Day -> {
                        patch.setDate("DTSTART", start.date)
                        // The last day is written; DTEND is the day after (an all day event ends at the start of the next day).
                        val last = (end as? EditKeys.Value.Day)?.date ?: start.date
                        patch.setDate("DTEND", last.plusDays(1))
                    }
                    is EditKeys.Value.Moment -> {
                        patch.setUtc("DTSTART", start.instant)
                        val stop = (end as? EditKeys.Value.Moment)?.instant ?: start.instant.plusSeconds(HOUR_SECONDS)
                        patch.setUtc("DTEND", stop)
                    }
                    else -> Unit
                }
            }
        } else {
            applyTodoDates(patch, change, zone)
            change[EditKeys.DONE]?.let { done ->
                if (done == "true") {
                    patch.set("STATUS", "", "COMPLETED")
                    patch.set("COMPLETED", "", UTC_FORMAT.format(Instant.ofEpochMilli(completedAt(change)).atOffset(ZoneOffset.UTC)))
                    patch.set("PERCENT-COMPLETE", "", "100")
                } else {
                    patch.set("STATUS", "", "NEEDS-ACTION")
                    patch.remove("COMPLETED")
                    patch.remove("PERCENT-COMPLETE")
                }
            }
        }
        applyAgent(patch, change)
    }

    private enum class Kind { NONE, DATE, UTC, LOCAL }

    /** A to-do date as it stands: a day, or a day and time (in UTC, or local with the TZID text in [params]). */
    private data class Stamp(val kind: Kind, val date: LocalDate? = null, val time: LocalTime? = null, val params: String = "") {
        val timed: Boolean get() = kind == Kind.UTC || kind == Kind.LOCAL
        val exists: Boolean get() = kind != Kind.NONE
    }

    private fun stampOf(patch: ICalPatch, name: String, zone: ZoneId): Stamp {
        val raw = patch.rawValue(name)?.trim() ?: return Stamp(Kind.NONE)
        val params = patch.paramText(name).orEmpty()
        val date = runCatching { LocalDate.parse(raw.take(DATE_DIGITS), DATE_PARSE) }.getOrNull() ?: return Stamp(Kind.NONE)
        if (raw.length < DATE_TIME_DIGITS || raw[DATE_DIGITS] != 'T') return Stamp(Kind.DATE, date)
        val time = runCatching { LocalTime.parse(raw.substring(DATE_DIGITS + 1, DATE_TIME_DIGITS), TIME_PARSE) }.getOrNull()
            ?: return Stamp(Kind.DATE, date)
        if (raw.endsWith("Z") || raw.endsWith("z")) {
            val local = date.atTime(time).atZone(ZoneOffset.UTC).withZoneSameInstant(zone)
            return Stamp(Kind.UTC, local.toLocalDate(), local.toLocalTime())
        }
        return Stamp(Kind.LOCAL, date, time, params)
    }

    // A value of the type of [like] on the day of [target]; the time of day comes from [target], else from [fallbackTime].
    private fun follow(target: Stamp, like: Stamp, fallbackTime: LocalTime): Stamp {
        val date = target.date ?: return target
        return when (like.kind) {
            Kind.DATE -> Stamp(Kind.DATE, date)
            Kind.UTC -> Stamp(Kind.UTC, date, target.time ?: fallbackTime)
            Kind.LOCAL -> Stamp(Kind.LOCAL, date, target.time ?: fallbackTime, like.params)
            Kind.NONE -> target
        }
    }

    private fun write(patch: ICalPatch, name: String, old: Stamp, new: Stamp, zone: ZoneId) {
        if (new == old) return
        val date = new.date
        val time = new.time
        when {
            new.kind == Kind.NONE -> patch.remove(name)
            new.kind == Kind.DATE && date != null -> patch.setDate(name, date)
            new.kind == Kind.UTC && date != null && time != null -> patch.setUtc(name, date.atTime(time).atZone(zone).toInstant())
            new.kind == Kind.LOCAL && date != null && time != null -> patch.setLocal(name, new.params, date.atTime(time))
        }
    }

    private fun momentStamp(instant: Instant, zone: ZoneId): Stamp = instant.atZone(zone).let { Stamp(Kind.UTC, it.toLocalDate(), it.toLocalTime()) }

    private fun applyTodoDates(patch: ICalPatch, change: Map<String, String>, zone: ZoneId) {
        val startChange = EditKeys.parse(change[EditKeys.START])
        val dueChange = EditKeys.parse(change[EditKeys.DUE])
        if (startChange == null && dueChange == null) return
        val oldStart = stampOf(patch, "DTSTART", zone)
        val oldDue = stampOf(patch, "DUE", zone)
        var due = when (dueChange) {
            null -> oldDue
            EditKeys.Value.None -> Stamp(Kind.NONE)
            is EditKeys.Value.Day -> Stamp(Kind.DATE, dueChange.date)
            is EditKeys.Value.Moment -> momentStamp(dueChange.instant, zone)
        }
        var start = when (startChange) {
            null -> oldStart
            EditKeys.Value.None -> Stamp(Kind.NONE)
            // a start that had a time keeps it on the new day
            is EditKeys.Value.Day ->
                if (oldStart.timed) oldStart.copy(date = startChange.date) else Stamp(Kind.DATE, startChange.date)
            is EditKeys.Value.Moment -> momentStamp(startChange.instant, zone)
        }
        if (start.exists && due.exists && start.timed != due.timed) {
            // One of them takes the type of the other. A due DAY typed next to a timed start follows the start (and its time of day);
            // in every other case the start follows the due date (a due time that was typed is kept, the start gets midnight).
            if (dueChange is EditKeys.Value.Day) {
                due = follow(due, start, start.time ?: LocalTime.MIDNIGHT)
            } else {
                start = follow(start, due, LocalTime.MIDNIGHT)
            }
        }
        write(patch, "DTSTART", oldStart, start, zone)
        write(patch, "DUE", oldDue, due, zone)
    }

    private fun completedAt(change: Map<String, String>): Long = change[COMPLETED_AT]?.toLongOrNull() ?: System.currentTimeMillis()

    private fun applyAgent(patch: ICalPatch, change: Map<String, String>) {
        val agent = change[EditKeys.AGENT] ?: return
        val allTags = change[EditKeys.ALL_TAGS].orEmpty().split(EditKeys.SEP).filter { it.isNotEmpty() }.toSet()
        if (agent == EditKeys.SELF || agent.isEmpty()) {
            patch.remove(AGENT_PROPERTY)
            patch.changeCategories(allTags, null)
        } else {
            patch.setText(AGENT_PROPERTY, agent)
            val tag = change[EditKeys.AGENT_TAG].orEmpty()
            patch.changeCategories(allTags - tag, tag.ifEmpty { null })
        }
    }

    /** The text of a new event or to-do (a calendar object with one component). */
    fun create(isTodo: Boolean, uid: String, change: Map<String, String>, now: Instant, zone: ZoneId = ZoneOffset.UTC): String {
        val component = if (isTodo) "VTODO" else "VEVENT"
        val skeleton = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nourax//NoAgenda//EN\r\nBEGIN:").append(component).append("\r\nUID:")
            append(ICalPatch.escape(uid)).append("\r\nEND:").append(component).append("\r\nEND:VCALENDAR\r\n")
        }
        val patch = ICalPatch(skeleton, component)
        patch.setText("SUMMARY", change[EditKeys.SUMMARY].orEmpty())
        if (isTodo) {
            patch.set("STATUS", "", "NEEDS-ACTION")
        } else {
            patch.set("TRANSP", "", "OPAQUE")
        }
        apply(patch, isTodo, change - EditKeys.SUMMARY, zone)
        patch.set("CREATED", "", UTC_FORMAT.format(now.atOffset(ZoneOffset.UTC)))
        patch.stamp(now)
        return patch.build()
    }

    const val COMPLETED_AT = "completedAt"
    private const val HOUR_SECONDS = 3600L
    private const val DATE_DIGITS = 8
    private const val DATE_TIME_DIGITS = 15
    private val DATE_PARSE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val TIME_PARSE: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
}
