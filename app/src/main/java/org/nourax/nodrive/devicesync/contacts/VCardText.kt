/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

import org.nourax.nodrive.agenda.ical.ICalPatch
import org.nourax.nodrive.agenda.ical.ICalText

/** One line of a vCard: `group.NAME;PARAM=a,b;BARE:value`. */
class VProp(val group: String, val name: String, val params: List<Pair<String, String>>, val raw: String) {
    /** The TYPE values in upper case: `TYPE=CELL,VOICE`, `TYPE=CELL;TYPE=VOICE` and the old bare `;CELL` are all read. */
    val types: Set<String> by lazy {
        val out = LinkedHashSet<String>()
        for ((key, value) in params) {
            if (key == "TYPE") value.split(',').forEach { if (it.isNotBlank()) out += it.trim().uppercase() }
            if (value.isEmpty() && key != "TYPE") out += key
        }
        out
    }

    fun param(key: String): String = params.firstOrNull { it.first == key.uppercase() }?.second.orEmpty()

    /** The value as text (`\n`, `\,`, `\;` and `\\` read). */
    fun text(): String = ICalText.unescape(raw)

    /** The parts of a value with `;` between them (N, ADR, ORG), each unescaped. */
    fun parts(): List<String> = split(raw, ';')

    /** The parts of a value with `,` between them (CATEGORIES, NICKNAME), each unescaped; empty parts are dropped. */
    fun list(): List<String> = split(raw, ',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun split(raw: String, separator: Char): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    current.append(c).append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == separator) {
                    out += ICalText.unescape(current.toString())
                    current.setLength(0)
                } else {
                    current.append(c)
                }
                i++
            }
            out += ICalText.unescape(current.toString())
            return out
        }
    }
}

object VCardText {
    /** Reads a content line. Null when it has no value separator. */
    fun parseLine(line: String): VProp? {
        var colon = -1
        var quoted = false
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val raw = line.substring(colon + 1)
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        quoted = false
        for (c in head) {
            if (c == '"') quoted = !quoted
            if (c == ';' && !quoted) {
                pieces += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        pieces += current.toString()
        val nameFull = pieces[0].trim()
        val group = if (nameFull.contains('.')) nameFull.substringBefore('.') else ""
        val name = (if (nameFull.contains('.')) nameFull.substringAfter('.') else nameFull).uppercase()
        val params = ArrayList<Pair<String, String>>()
        for (piece in pieces.drop(1)) {
            if (piece.isBlank()) continue
            val eq = piece.indexOf('=')
            if (eq < 0) {
                params += piece.trim().uppercase() to ""
            } else {
                params += piece.substring(0, eq).trim().uppercase() to piece.substring(eq + 1).trim().trim('"')
            }
        }
        return VProp(group, name, params, raw)
    }

    /** The properties of the first card of the text, in order. */
    fun parse(text: String): List<VProp> {
        val out = ArrayList<VProp>()
        var inside = false
        for (line in ICalText.unfold(text)) {
            val upper = line.uppercase().trim()
            if (upper == "BEGIN:VCARD") {
                if (inside) break
                inside = true
                continue
            }
            if (upper == "END:VCARD") break
            if (!inside || line.isBlank()) continue
            parseLine(line)?.let { out += it }
        }
        return out
    }

    fun version(props: List<VProp>): String = props.firstOrNull { it.name == "VERSION" }?.raw?.trim().orEmpty()

    fun escape(text: String): String = ICalPatch.escape(text)
}

/**
 * Changes only the named properties of ONE vCard. Every other line (an unknown `X-` line, the photo, a group label, the way a line
 * was folded) stays byte for byte. The line ending and the version are kept.
 */
class VCardPatch(source: String) {
    private class Line(val physical: MutableList<String>, val prop: VProp?) {
        val name: String get() = prop?.name.orEmpty()
    }

    private val eol: String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\n') -> "\n"
        else -> "\r\n"
    }
    private val endsWithEol: Boolean = source.endsWith("\n")
    private val lines = ArrayList<Line>()

    init {
        val physical = source.split("\n").map { it.removeSuffix("\r") }.let { if (endsWithEol) it.dropLast(1) else it }
        for (text in physical) {
            if ((text.startsWith(" ") || text.startsWith("\t")) && lines.isNotEmpty()) {
                val last = lines.last()
                last.physical += text
                continue
            }
            lines += Line(mutableListOf(text), null)
        }
        // parse every logical line once (the continuation lines are part of it)
        for (i in lines.indices) {
            val l = lines[i]
            val unfolded = l.physical.first() + l.physical.drop(1).joinToString("") { it.substring(1) }
            val upper = unfolded.uppercase().trim()
            val prop = if (upper == "BEGIN:VCARD" || upper == "END:VCARD") null else VCardText.parseLine(unfolded)
            lines[i] = Line(l.physical, prop)
        }
    }

    val version: String get() = lines.firstNotNullOfOrNull { l -> l.prop?.takeIf { it.name == "VERSION" }?.raw?.trim() }.orEmpty()

    fun props(name: String): List<VProp> = lines.mapNotNull { l -> l.prop?.takeIf { it.name == name.uppercase() } }

    private fun endIndex(): Int = lines.indexOfLast { it.physical.first().uppercase().trim() == "END:VCARD" }

    /** Removes the lines of that name for which [predicate] is true. */
    fun removeWhere(name: String, predicate: (VProp) -> Boolean): VCardPatch {
        val upper = name.uppercase()
        lines.removeAll { l -> l.prop != null && l.prop.name == upper && predicate(l.prop) }
        return this
    }

    fun remove(name: String): VCardPatch = removeWhere(name) { true }

    /** Adds a property line before the end of the card. [text] must already be escaped. */
    fun add(text: String): VCardPatch {
        val at = endIndex().let { if (it < 0) lines.size else it }
        lines.add(at, Line(ICalPatch.fold(text).toMutableList(), VCardText.parseLine(text)))
        return this
    }

    /** The first line of that name is replaced (the others are removed); without one the line is added. */
    fun set(name: String, text: String): VCardPatch {
        val upper = name.uppercase()
        val first = lines.indexOfFirst { it.prop?.name == upper }
        if (first < 0) return add(text)
        lines[first] = Line(ICalPatch.fold(text).toMutableList(), VCardText.parseLine(text))
        var i = lines.size - 1
        while (i > first) {
            if (lines[i].prop?.name == upper) lines.removeAt(i)
            i--
        }
        return this
    }

    fun build(): String {
        val body = lines.joinToString(eol) { it.physical.joinToString(eol) }
        return if (endsWithEol) body + eol else body
    }
}
