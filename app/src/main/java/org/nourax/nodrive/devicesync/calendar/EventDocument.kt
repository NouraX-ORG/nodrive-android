/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.calendar

import org.nourax.nodrive.agenda.ical.ICalPatch
import org.nourax.nodrive.agenda.ical.ICalText
import java.time.Instant
import java.time.ZoneId

/**
 * One iCalendar object cut into its events (the VEVENT blocks) and everything around them (time zones, the header, the end). Every
 * line of a block that is not changed stays as it was, so a phone edit of one occurrence leaves the other blocks byte for byte.
 */
class EventDocument(source: String) {
    class Segment(val lines: MutableList<String>, val isEvent: Boolean) {
        val isException: Boolean get() = isEvent && lines.any { it.uppercase().startsWith("RECURRENCE-ID") }
    }

    val eol: String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\n') -> "\n"
        else -> "\r\n"
    }
    private val endsWithEol: Boolean = source.endsWith("\n")
    val segments = ArrayList<Segment>()

    init {
        val physical = source.split("\n").map { it.removeSuffix("\r") }.let { if (endsWithEol) it.dropLast(1) else it }
        var current = Segment(ArrayList(), false)
        val stack = ArrayList<String>()
        for (line in physical) {
            val continuation = line.startsWith(" ") || line.startsWith("\t")
            if (!continuation) {
                val upper = line.uppercase()
                if (upper.startsWith("BEGIN:")) {
                    val type = upper.substring(6).trim()
                    if (type == "VEVENT" && stack.size == 1 && stack[0] == "VCALENDAR") {
                        if (current.lines.isNotEmpty()) segments += current
                        current = Segment(ArrayList(), true)
                    }
                    stack += type
                    current.lines += line
                    continue
                }
                if (upper.startsWith("END:")) {
                    current.lines += line
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    if (current.isEvent && stack.size == 1 && stack[0] == "VCALENDAR" && upper.substring(4).trim() == "VEVENT") {
                        segments += current
                        current = Segment(ArrayList(), false)
                    }
                    continue
                }
            }
            current.lines += line
        }
        if (current.lines.isNotEmpty()) segments += current
    }

    val events: List<Segment> get() = segments.filter { it.isEvent }

    fun master(): Segment? = events.firstOrNull { !it.isException } ?: events.firstOrNull()

    /** The segment as a text with one event inside a VCALENDAR (what [ICalPatch] and [ICalText] work on). */
    fun wrapped(segment: Segment): String =
        "BEGIN:VCALENDAR" + eol + segment.lines.joinToString(eol) + eol + "END:VCALENDAR" + eol

    /** The event of a segment as a phone event. */
    fun eventOf(segment: Segment, zone: ZoneId): PhoneEvent? =
        ICalText.parse(wrapped(segment)).firstOrNull { it.type == "VEVENT" }?.let { EventMapper.fromComponent(it, zone) }

    /** Puts the changed text of a wrapped segment back. */
    fun replace(segment: Segment, wrappedText: String) {
        val lines = wrappedText.split("\n").map { it.removeSuffix("\r") }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        // first line BEGIN:VCALENDAR, last line END:VCALENDAR
        if (lines.size >= 2) {
            lines.removeAt(0)
            lines.removeAt(lines.size - 1)
        }
        segment.lines.clear()
        segment.lines.addAll(lines)
    }

    fun remove(segment: Segment) {
        segments.remove(segment)
    }

    /** Adds a new event block after the last event (or before the end line when there is none). */
    fun addEvent(blockLines: List<String>) {
        val seg = Segment(blockLines.toMutableList(), true)
        val last = segments.indexOfLast { it.isEvent }
        if (last >= 0) {
            segments.add(last + 1, seg)
            return
        }
        // before the segment that holds END:VCALENDAR
        val endIndex = segments.indexOfLast { s -> s.lines.any { it.uppercase() == "END:VCALENDAR" } }
        if (endIndex >= 0) {
            val endSeg = segments[endIndex]
            val at = endSeg.lines.indexOfLast { it.uppercase() == "END:VCALENDAR" }
            val tail = ArrayList(endSeg.lines.subList(at, endSeg.lines.size))
            while (endSeg.lines.size > at) endSeg.lines.removeAt(endSeg.lines.size - 1)
            segments.add(endIndex + 1, seg)
            segments.add(endIndex + 2, Segment(tail, false))
        } else {
            segments += seg
        }
    }

    fun build(): String {
        val body = segments.joinToString(eol) { it.lines.joinToString(eol) }
        return if (endsWithEol) body + eol else body
    }

    companion object {
        /** The block lines of a new event: UID, a stamp and the id of the occurrence it replaces. */
        fun newException(uid: String, recurrenceLine: String, at: Instant): List<String> = listOf(
            "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:" + EventMapper.stamp(at.toEpochMilli()), recurrenceLine, "END:VEVENT"
        )

        /** The object to send for the phone's event and its changed occurrences, built from the server's text [original] (or a new one). */
        fun upload(original: String?, uid: String, phone: PhoneObject, zone: ZoneId, at: Instant): String {
            val doc = EventDocument(original ?: EventMapper.skeleton(uid, at))
            val masterSeg = doc.master() ?: return upload(null, uid, phone, zone, at)
            val baseMaster = doc.eventOf(masterSeg, zone) ?: PhoneEvent(uid = uid)
            // A cancelled occurrence is an EXDATE of the master, not a block.
            val cancelled = phone.exceptions.filter { it.status == 2 && it.recurrenceIdMs != null }.map { EventMapper.stamp(it.recurrenceIdMs!!) }
            val exdate = (phone.master.exdate.split(',').filter { it.isNotEmpty() } + cancelled).distinct().sorted().joinToString(",")
            val nowMaster = phone.master.copy(exdate = exdate)
            doc.replace(masterSeg, EventMapper.patch(doc.wrapped(masterSeg), baseMaster, nowMaster, at))
            for (ex in phone.exceptions) {
                val id = ex.recurrenceIdMs ?: continue
                val existing = doc.events.firstOrNull { it.isException && doc.eventOf(it, zone)?.recurrenceIdMs == id }
                if (ex.status == 2) {
                    if (existing != null) doc.remove(existing)
                    continue
                }
                if (existing != null) {
                    val base = doc.eventOf(existing, zone) ?: PhoneEvent(uid = uid)
                    doc.replace(existing, EventMapper.patch(doc.wrapped(existing), base, ex, at))
                } else {
                    doc.addEvent(newException(uid, EventMapper.recurrenceIdLine(ex, nowMaster.timeZone), at))
                    val seg = doc.events.last()
                    doc.replace(seg, EventMapper.patch(doc.wrapped(seg), PhoneEvent(uid = uid, recurrenceIdMs = id), ex, at))
                }
            }
            return doc.build()
        }
    }
}
