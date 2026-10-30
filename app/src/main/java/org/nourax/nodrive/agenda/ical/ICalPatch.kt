/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ical

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Changes only the named properties of ONE event or to-do inside the raw iCalendar text (plan 2.2). Every other line stays as it
 * was, byte for byte: alarms, attendees, unknown `X-` properties, time zones, even the way a line was folded. Properties inside
 * a nested component (an alarm) are never touched. `DTSTAMP`, `LAST-MODIFIED` and `SEQUENCE` are bumped by [stamp].
 *
 * The line ending of the text (CRLF or LF) is kept. A text that mixes both is written with the first one it used.
 */
class ICalPatch(source: String, private val component: String) {
    private class Line(val physical: MutableList<String>, val name: String, val inTarget: Boolean) {
        fun unfolded(): String = physical.first() + physical.drop(1).joinToString("") { if (it.isNotEmpty()) it.substring(1) else "" }
    }

    private val eol: String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\n') -> "\n"
        else -> "\r\n"
    }
    private val endsWithEol: Boolean = source.endsWith("\n")
    private val lines = ArrayList<Line>()

    /** Whether the text has the component at all. */
    val found: Boolean

    init {
        val physical = source.split("\n").map { it.removeSuffix("\r") }.let { if (endsWithEol) it.dropLast(1) else it }
        val stack = ArrayList<String>()
        var targetDepth = -1
        var targetDone = false
        for (text in physical) {
            if ((text.startsWith(" ") || text.startsWith("\t")) && lines.isNotEmpty()) {
                lines.last().physical += text
                continue
            }
            val upper = text.uppercase()
            var name = ""
            var inTarget = false
            when {
                upper.startsWith("BEGIN:") -> {
                    val type = upper.substring(6).trim()
                    if (!targetDone && targetDepth < 0 && type == component.uppercase() && stack.lastOrNull() == "VCALENDAR") {
                        targetDepth = stack.size + 1
                    }
                    stack += type
                    name = "BEGIN"
                }
                upper.startsWith("END:") -> {
                    if (targetDepth == stack.size) {
                        targetDone = true
                        targetDepth = -1
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    name = "END"
                }
                else -> {
                    var i = 0
                    while (i < text.length && text[i] != ':' && text[i] != ';') i++
                    name = text.substring(0, i).trim().uppercase()
                    inTarget = targetDepth > 0 && stack.size == targetDepth
                }
            }
            lines += Line(mutableListOf(text), name, inTarget)
        }
        found = lines.any { it.name == "BEGIN" && it.unfolded().uppercase() == "BEGIN:${component.uppercase()}" }
    }

    // The index of the END line of the target component (new lines go before it).
    private fun endOfTarget(): Int {
        var depth = 0
        var inside = false
        for ((i, line) in lines.withIndex()) {
            val upper = line.unfolded().uppercase()
            if (!inside && line.name == "BEGIN" && upper == "BEGIN:${component.uppercase()}") {
                inside = true
                depth = 1
                continue
            }
            if (inside) {
                if (line.name == "BEGIN") depth++
                if (line.name == "END") {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun indexOf(name: String): Int = lines.indexOfFirst { it.inTarget && it.name == name.uppercase() }

    /** The value (still escaped) of the first property of that name, or null. */
    fun rawValue(name: String): String? {
        val i = indexOf(name)
        if (i < 0) return null
        val text = lines[i].unfolded()
        return text.substring(valueStart(text) + 1)
    }

    private fun valueStart(text: String): Int {
        var quoted = false
        for ((i, c) in text.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return i
        }
        return text.length - 1
    }

    fun has(name: String): Boolean = indexOf(name) >= 0

    /** The parameter text (for example ";TZID=Europe/Berlin") of the first property of that name, or null when there is none. */
    fun paramText(name: String): String? {
        val i = indexOf(name)
        if (i < 0) return null
        val text = lines[i].unfolded()
        return text.substring(name.length, valueStart(text).coerceAtLeast(name.length))
    }

    /** Removes every property of that name. */
    fun remove(name: String): ICalPatch {
        val upper = name.uppercase()
        lines.removeAll { it.inTarget && it.name == upper }
        return this
    }

    /**
     * Writes the property: the first one of that name is replaced (the others are removed), or a new line is added at the end of
     * the component. [params] is written as it is, for example ";VALUE=DATE", and [value] must already be escaped.
     */
    fun set(name: String, params: String, value: String): ICalPatch {
        val upper = name.uppercase()
        val line = Line(fold("$upper$params:$value").toMutableList(), upper, true)
        val first = indexOf(upper)
        if (first >= 0) {
            lines[first] = line
            var i = lines.size - 1
            while (i > first) {
                if (lines[i].inTarget && lines[i].name == upper) lines.removeAt(i)
                i--
            }
        } else {
            val end = endOfTarget()
            if (end < 0) lines += line else lines.add(end, line)
        }
        return this
    }

    fun setText(name: String, text: String): ICalPatch = set(name, "", escape(text))

    fun setUtc(name: String, instant: Instant): ICalPatch = set(name, "", UTC_FORMAT.format(instant.atOffset(ZoneOffset.UTC)))

    fun setDate(name: String, date: LocalDate): ICalPatch = set(name, ";VALUE=DATE", DATE_FORMAT.format(date))

    /** A local date and time written as it is, with [params] (for example ";TZID=Europe/Berlin", or empty for a floating time). */
    fun setLocal(name: String, params: String, dateTime: LocalDateTime): ICalPatch = set(name, params, LOCAL_FORMAT.format(dateTime))

    /** The values of every CATEGORIES line, unescaped. */
    fun categories(): List<String> =
        lines.filter { it.inTarget && it.name == "CATEGORIES" }.flatMap { splitList(it.unfolded().let { t -> t.substring(valueStart(t) + 1) }) }

    /**
     * Takes the categories in [remove] away and puts [add] in (when it is not there yet). Other categories stay. A line that
     * becomes empty is deleted. The first CATEGORIES line receives the new one.
     */
    fun changeCategories(remove: Set<String>, add: String?): ICalPatch {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.inTarget && line.name == "CATEGORIES") {
                val text = line.unfolded()
                val start = valueStart(text)
                val values = splitList(text.substring(start + 1))
                val kept = values.filter { it !in remove }
                if (kept.size != values.size) {
                    if (kept.isEmpty()) {
                        lines.removeAt(i)
                        continue
                    }
                    lines[i] = Line(fold(text.substring(0, start + 1) + kept.joinToString(",") { escape(it) }).toMutableList(), "CATEGORIES", true)
                }
            }
            i++
        }
        if (add != null && add !in categories()) {
            val first = indexOf("CATEGORIES")
            if (first >= 0) {
                val text = lines[first].unfolded()
                lines[first] = Line(fold(text + "," + escape(add)).toMutableList(), "CATEGORIES", true)
            } else {
                set("CATEGORIES", "", escape(add))
            }
        }
        return this
    }

    /** `DTSTAMP` and `LAST-MODIFIED` become [now]; `SEQUENCE` goes up by one (or becomes 1). */
    fun stamp(now: Instant): ICalPatch {
        setUtc("DTSTAMP", now)
        setUtc("LAST-MODIFIED", now)
        val sequence = rawValue("SEQUENCE")?.trim()?.toIntOrNull() ?: 0
        set("SEQUENCE", "", (sequence + 1).toString())
        return this
    }

    fun build(): String {
        val body = lines.joinToString(eol) { it.physical.joinToString(eol) }
        return if (endsWithEol) body + eol else body
    }

    companion object {
        private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private const val FOLD_OCTETS = 75

        fun escape(text: String): String {
            val out = StringBuilder(text.length + 8)
            for (c in text) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    ';' -> out.append("\\;")
                    ',' -> out.append("\\,")
                    '\n' -> out.append("\\n")
                    '\r' -> Unit
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /** Splits at commas that are not escaped; each part is unescaped and trimmed. Empty parts are dropped. */
        fun splitList(raw: String): List<String> = ICalProperty("CATEGORIES", emptyMap(), raw).textList()

        /** A line of at most 75 octets per physical line; the next ones start with one space. Never cuts a character. */
        fun fold(line: String): List<String> {
            val out = ArrayList<String>()
            var current = StringBuilder()
            var octets = 0
            var index = 0
            while (index < line.length) {
                val cp = line.codePointAt(index)
                val size = Character.charCount(cp)
                val length = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (octets + length > FOLD_OCTETS) {
                    out += current.toString()
                    current = StringBuilder(" ")
                    octets = 1
                }
                current.appendCodePoint(cp)
                octets += length
                index += size
            }
            out += current.toString()
            return out
        }
    }
}
