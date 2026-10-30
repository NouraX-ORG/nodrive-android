/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.calendar

import org.nourax.nodrive.agenda.ical.ICalComponent
import org.nourax.nodrive.agenda.ical.ICalPatch
import org.nourax.nodrive.agenda.ical.ICalProperty
import org.nourax.nodrive.agenda.ical.ICalText
import org.nourax.nodrive.agenda.ical.ICalTime
import org.nourax.nodrive.agenda.ical.ZoneResolver
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PhoneGuest(val name: String, val email: String, val partstat: String)

/**
 * One event as the phone's calendar holds it (plain values, no Android class, so it can be unit-tested). Times are UTC milliseconds;
 * an all-day event starts at UTC midnight and ends (exclusive) at UTC midnight. [exdate] and [rdate] are sorted UTC stamps joined by
 * commas. [reminders] are minutes before the start. Guests and the organiser only travel from the server to the phone.
 */
data class PhoneEvent(
    val uid: String = "",
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val timeZone: String = "UTC",
    val rrule: String = "",
    val rdate: String = "",
    val exdate: String = "",
    /** Android's `STATUS`: 0 tentative, 1 confirmed, 2 cancelled, or null. */
    val status: Int? = null,
    /** Android's `AVAILABILITY`: 0 busy, 1 free. */
    val availability: Int = 0,
    /** Android's `ACCESS_LEVEL`: 0 default, 1 confidential, 2 private, 3 public. */
    val accessLevel: Int = 0,
    val reminders: List<Int> = emptyList(),
    val organizer: String = "",
    val guests: List<PhoneGuest> = emptyList(),
    /** An exception of a repeating event: the start of the occurrence it replaces. */
    val recurrenceIdMs: Long? = null,
    val recurrenceAllDay: Boolean = false
) {
    val recurring: Boolean get() = rrule.isNotEmpty() || rdate.isNotEmpty()
}

/** What one iCalendar object holds: the event, and the changed occurrences of it. */
class ParsedObject(val master: PhoneEvent, val exceptions: List<PhoneEvent>)

/** The event and its changed occurrences from the phone's rows. A cancelled occurrence has `status` 2. */
class PhoneObject(val master: PhoneEvent, val exceptions: List<PhoneEvent>)

object EventMapper {
    private const val DAY_MS = 86_400_000L
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun mail(value: String): String {
        var v = value.trim()
        if (v.startsWith("mailto:", ignoreCase = true)) v = v.substring(7)
        return v.lowercase()
    }

    fun stamp(ms: Long): String = STAMP.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private fun zoneIdOf(prop: ICalProperty, time: ICalTime, floatingZone: ZoneId, resolver: ZoneResolver): String {
        if (prop.raw.trim().endsWith("Z", ignoreCase = true)) return "UTC"
        val tzid = prop.param("TZID")
        if (tzid.isEmpty() || time.floating || time.approximated) return floatingZone.id
        return resolver.zoneFor(tzid)?.id ?: floatingZone.id
    }

    private fun instantMs(prop: ICalProperty, floatingZone: ZoneId, resolver: ZoneResolver): Long? {
        val t = ICalTime.parse(prop, floatingZone, resolver)
        if (!t.valid) return null
        return if (t.isDate) t.date!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else t.utc!!.toEpochMilli()
    }

    /** All values of EXDATE or RDATE lines as a sorted list of UTC stamps. */
    private fun dateList(c: ICalComponent, name: String, floatingZone: ZoneId, resolver: ZoneResolver): String {
        val out = sortedSetOf<Long>()
        for (prop in c.all(name)) {
            val dateOnly = prop.param("VALUE").equals("DATE", ignoreCase = true)
            for (part in prop.raw.split(',')) {
                // A period (RDATE) "start/end": only the start is kept.
                val v = part.substringBefore('/').trim()
                if (v.isEmpty()) continue
                val t = ICalTime.parseValue(v, prop.param("TZID"), dateOnly, floatingZone, resolver)
                if (!t.valid) continue
                out += if (t.isDate) t.date!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else t.utc!!.toEpochMilli()
            }
        }
        return out.joinToString(",") { stamp(it) }
    }

    private fun reminders(c: ICalComponent): List<Int> {
        val out = sortedSetOf<Int>()
        for (alarm in c.children) {
            if (alarm.type != "VALARM") continue
            val trigger = alarm.first("TRIGGER") ?: continue
            if (trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true)) continue
            if (trigger.param("RELATED").equals("END", ignoreCase = true)) continue
            val seconds = ICalTime.parseDurationSeconds(trigger.raw)
            if (!(trigger.raw.trim().startsWith("-P") || seconds == 0L)) continue
            val abs = if (seconds < 0) -seconds else seconds
            out += ((abs + 59) / 60).toInt()
        }
        return out.toList()
    }

    /** One VEVENT as a phone event; null when its start can not be read. */
    fun fromComponent(c: ICalComponent, floatingZone: ZoneId, resolver: ZoneResolver = ZoneResolver.DEFAULT): PhoneEvent? {
        val dtstart = c.first("DTSTART") ?: return null
        val start = ICalTime.parse(dtstart, floatingZone, resolver)
        if (!start.valid) return null
        val allDay = start.isDate
        val startMs = if (allDay) start.date!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.utc!!.toEpochMilli()
        val tz = if (allDay) "UTC" else zoneIdOf(dtstart, start, floatingZone, resolver)
        val dtend = c.first("DTEND")
        val duration = c.first("DURATION")
        var endMs = when {
            dtend != null -> instantMs(dtend, floatingZone, resolver) ?: startMs
            duration != null -> {
                val seconds = ICalTime.parseDurationSeconds(duration.raw)
                startMs + (if (seconds < 0) 0 else seconds * 1000)
            }
            allDay -> startMs + DAY_MS
            else -> startMs
        }
        if (allDay && endMs <= startMs) endMs = startMs + DAY_MS
        if (endMs < startMs) endMs = startMs
        val transp = c.text("TRANSP").uppercase()
        val klass = c.text("CLASS").uppercase()
        val status = when (c.text("STATUS").uppercase()) {
            "TENTATIVE" -> 0
            "CONFIRMED" -> 1
            "CANCELLED", "CANCELED" -> 2
            else -> null
        }
        val guests = c.all("ATTENDEE").map {
            PhoneGuest(it.param("CN"), mail(it.raw), it.param("PARTSTAT").uppercase())
        }
        val recurrence = c.first("RECURRENCE-ID")
        val recurrenceMs = recurrence?.let { instantMs(it, floatingZone, resolver) }
        return PhoneEvent(
            uid = c.text("UID"),
            title = c.text("SUMMARY"),
            location = c.text("LOCATION"),
            description = c.text("DESCRIPTION"),
            allDay = allDay,
            startMs = startMs,
            endMs = endMs,
            timeZone = tz,
            rrule = c.first("RRULE")?.raw?.trim().orEmpty(),
            rdate = dateList(c, "RDATE", floatingZone, resolver),
            exdate = dateList(c, "EXDATE", floatingZone, resolver),
            status = status,
            availability = if (transp == "TRANSPARENT") 1 else 0,
            accessLevel = when (klass) {
                "PUBLIC" -> 3
                "PRIVATE" -> 2
                "CONFIDENTIAL" -> 1
                else -> 0
            },
            reminders = reminders(c),
            organizer = c.first("ORGANIZER")?.let { mail(it.raw) }.orEmpty(),
            guests = guests,
            recurrenceIdMs = recurrenceMs,
            recurrenceAllDay = recurrence != null &&
                (recurrence.param("VALUE").equals("DATE", ignoreCase = true) || recurrence.raw.trim().length == 8)
        )
    }

    /** The events of an object. A text without any event (a to-do only) gives `null`: tasks never go to the phone. */
    fun parse(text: String, floatingZone: ZoneId, resolver: ZoneResolver = ZoneResolver.DEFAULT): ParsedObject? {
        val events = ICalText.parse(text).filter { it.type == "VEVENT" }.mapNotNull { fromComponent(it, floatingZone, resolver) }
        if (events.isEmpty()) return null
        val master = events.firstOrNull { it.recurrenceIdMs == null }
        if (master != null) return ParsedObject(master, events.filter { it.recurrenceIdMs != null })
        // Only changed occurrences (an invitation to one of them): the first one stands for the event.
        val first = events.first().copy(recurrenceIdMs = null, recurrenceAllDay = false)
        return ParsedObject(first, events.drop(1).filter { it.recurrenceIdMs != null })
    }

    // ------------------------------------------------------------------------------------------
    // Phone to server: change only what the person changed
    // ------------------------------------------------------------------------------------------

    private fun writeTime(p: ICalPatch, name: String, ms: Long, timeZone: String) {
        if (timeZone == "UTC") {
            p.setUtc(name, Instant.ofEpochMilli(ms))
        } else {
            val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneOffset.UTC)
            p.setLocal(name, ";TZID=$timeZone", LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))
        }
    }

    private fun writeList(p: ICalPatch, name: String, value: String, allDay: Boolean) {
        p.remove(name)
        if (value.isEmpty()) return
        val stamps = value.split(',').filter { it.isNotEmpty() }
        if (allDay) {
            p.set(name, ";VALUE=DATE", stamps.joinToString(",") { it.substring(0, 8) })
        } else {
            p.set(name, "", stamps.joinToString(","))
        }
    }

    fun alarmLines(minutes: Int, title: String): List<String> = listOf(
        "BEGIN:VALARM",
        "ACTION:DISPLAY",
        "DESCRIPTION:" + ICalPatch.escape(title.ifEmpty { "Reminder" }),
        "TRIGGER:-PT${minutes}M",
        "END:VALARM"
    )

    /**
     * [text] holds ONE event (wrapped in a VCALENDAR). The properties in which [now] differs from [base] are rewritten; every other
     * line stays byte for byte (agent id, categories, attendees, unknown lines, a different alarm). Nothing changes when nothing differs.
     */
    fun patch(text: String, base: PhoneEvent, now: PhoneEvent, at: Instant): String {
        val p = ICalPatch(text, "VEVENT")
        var changed = false
        fun textProp(name: String, old: String, new: String) {
            if (old == new) return
            if (new.isEmpty()) p.remove(name) else p.setText(name, new)
            changed = true
        }
        textProp("SUMMARY", base.title, now.title)
        textProp("LOCATION", base.location, now.location)
        textProp("DESCRIPTION", base.description, now.description)
        if (base.allDay != now.allDay || base.startMs != now.startMs || base.endMs != now.endMs || base.timeZone != now.timeZone) {
            p.remove("DURATION")
            if (now.allDay) {
                val start = Instant.ofEpochMilli(now.startMs).atOffset(ZoneOffset.UTC).toLocalDate()
                var end = Instant.ofEpochMilli(now.endMs).atOffset(ZoneOffset.UTC).toLocalDate()
                if (!end.isAfter(start)) end = start.plusDays(1)
                p.setDate("DTSTART", start)
                p.setDate("DTEND", end)
            } else {
                writeTime(p, "DTSTART", now.startMs, now.timeZone)
                writeTime(p, "DTEND", now.endMs, now.timeZone)
            }
            changed = true
        }
        if (base.rrule != now.rrule) {
            if (now.rrule.isEmpty()) p.remove("RRULE") else p.set("RRULE", "", now.rrule)
            changed = true
        }
        if (base.rdate != now.rdate) {
            writeList(p, "RDATE", now.rdate, now.allDay)
            changed = true
        }
        if (base.exdate != now.exdate) {
            writeList(p, "EXDATE", now.exdate, now.allDay)
            changed = true
        }
        if (base.status != now.status) {
            val name = when (now.status) {
                0 -> "TENTATIVE"
                1 -> "CONFIRMED"
                2 -> "CANCELLED"
                else -> ""
            }
            if (name.isEmpty()) p.remove("STATUS") else p.set("STATUS", "", name)
            changed = true
        }
        if (base.availability != now.availability) {
            p.set("TRANSP", "", if (now.availability == 1) "TRANSPARENT" else "OPAQUE")
            changed = true
        }
        if (base.accessLevel != now.accessLevel) {
            val name = when (now.accessLevel) {
                3 -> "PUBLIC"
                2 -> "PRIVATE"
                1 -> "CONFIDENTIAL"
                else -> ""
            }
            if (name.isEmpty()) p.remove("CLASS") else p.set("CLASS", "", name)
            changed = true
        }
        if (base.reminders != now.reminders) {
            p.removeChildren("VALARM")
            for (m in now.reminders) p.addChild(alarmLines(m, now.title))
            changed = true
        }
        if (changed) p.stamp(at)
        return p.build()
    }

    /**
     * "Apply my change again": the server's event with only what the phone changed (against [base], what both started from) put on top.
     * A field the phone did not change keeps the server's newer value.
     */
    fun rebase(base: PhoneEvent, phone: PhoneEvent, server: PhoneEvent): PhoneEvent {
        fun <T> pick(b: T, p: T, s: T): T = if (p != b) p else s
        val timeChanged = base.allDay != phone.allDay || base.startMs != phone.startMs || base.endMs != phone.endMs || base.timeZone != phone.timeZone
        return server.copy(
            title = pick(base.title, phone.title, server.title),
            location = pick(base.location, phone.location, server.location),
            description = pick(base.description, phone.description, server.description),
            allDay = if (timeChanged) phone.allDay else server.allDay,
            startMs = if (timeChanged) phone.startMs else server.startMs,
            endMs = if (timeChanged) phone.endMs else server.endMs,
            timeZone = if (timeChanged) phone.timeZone else server.timeZone,
            rrule = pick(base.rrule, phone.rrule, server.rrule),
            rdate = pick(base.rdate, phone.rdate, server.rdate),
            exdate = pick(base.exdate, phone.exdate, server.exdate),
            status = pick(base.status, phone.status, server.status),
            availability = pick(base.availability, phone.availability, server.availability),
            accessLevel = pick(base.accessLevel, phone.accessLevel, server.accessLevel),
            reminders = pick(base.reminders, phone.reminders, server.reminders)
        )
    }

    /** An empty object to build a new event from. */
    fun skeleton(uid: String, at: Instant): String = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nourax//NoDrive//EN", "BEGIN:VEVENT",
        "UID:$uid", "DTSTAMP:" + STAMP.format(at.atOffset(ZoneOffset.UTC)), "END:VEVENT", "END:VCALENDAR"
    ).joinToString("\r\n") + "\r\n"

    fun recurrenceIdLine(e: PhoneEvent, masterZone: String): String = when {
        e.recurrenceAllDay -> "RECURRENCE-ID;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(e.recurrenceIdMs ?: 0).atOffset(ZoneOffset.UTC))
        masterZone == "UTC" -> "RECURRENCE-ID:" + stamp(e.recurrenceIdMs ?: 0)
        else -> {
            val zone = runCatching { ZoneId.of(masterZone) }.getOrDefault(ZoneOffset.UTC)
            "RECURRENCE-ID;TZID=$masterZone:" + LOCAL.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(e.recurrenceIdMs ?: 0), zone))
        }
    }
}
