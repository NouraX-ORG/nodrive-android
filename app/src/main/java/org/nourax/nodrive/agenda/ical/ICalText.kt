/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ical

class ICalProperty(
    /** Upper case. */
    val name: String,
    /** Keys upper case, values without quotes. */
    val params: Map<String, String>,
    /** The value as written (still escaped). */
    val raw: String
) {
    /** The value as text: \n \, \; \\ unescaped. */
    fun text(): String = ICalText.unescape(raw)

    /** The value split at unescaped commas, each part unescaped (CATEGORIES). */
    fun textList(): List<String> {
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
            if (c == ',') {
                out += ICalText.unescape(current.toString()).trim()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        out += ICalText.unescape(current.toString()).trim()
        return out.filter { it.isNotEmpty() }
    }

    fun param(key: String): String = params[key.uppercase()].orEmpty()
}

class ICalComponent(val type: String) {
    val props = ArrayList<ICalProperty>()
    val children = ArrayList<ICalComponent>()

    fun first(name: String): ICalProperty? {
        val wanted = name.uppercase()
        return props.firstOrNull { it.name == wanted }
    }

    fun all(name: String): List<ICalProperty> {
        val wanted = name.uppercase()
        return props.filter { it.name == wanted }
    }

    fun has(name: String): Boolean = first(name) != null

    fun text(name: String): String = first(name)?.text().orEmpty()
}

/** A small iCalendar (RFC 5545) reader. It reads only what NoAgenda needs. */
object ICalText {
    /** Components of the first level inside VCALENDAR (VEVENT, VTODO, VTIMEZONE), in order. */
    fun parse(text: String): List<ICalComponent> {
        val result = ArrayList<ICalComponent>()
        val stack = ArrayList<ICalComponent>()
        for (line in unfold(text)) {
            if (line.isEmpty()) continue
            if (line.startsWith("BEGIN:", ignoreCase = true)) {
                stack += ICalComponent(line.substring(6).trim().uppercase())
                continue
            }
            if (line.startsWith("END:", ignoreCase = true)) {
                if (stack.isEmpty()) continue
                val done = stack.removeAt(stack.size - 1)
                if (done.type == "VCALENDAR" || stack.isEmpty()) continue
                if (stack.size == 1 && stack[0].type == "VCALENDAR") result += done else stack.last().children += done
                continue
            }
            if (stack.isEmpty()) continue
            parseProperty(line)?.let { stack.last().props += it }
        }
        return result
    }

    /** A line that starts with a space or tab continues the one before. */
    fun unfold(text: String): List<String> {
        val lines = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            if (lines.isNotEmpty() && line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                lines[lines.size - 1] = lines.last() + line.substring(1)
            } else {
                lines += line
            }
        }
        return lines
    }

    fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                if (n == 'n' || n == 'N') out.append('\n') else out.append(n)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun parseProperty(line: String): ICalProperty? {
        var i = 0
        val n = line.length
        while (i < n && line[i] != ':' && line[i] != ';') i++
        if (i == 0 || i >= n) return null
        val name = line.substring(0, i).trim().uppercase()
        val params = LinkedHashMap<String, String>()
        while (i < n && line[i] == ';') {
            i++
            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ':' && line[i] != ';') i++
            val key = line.substring(keyStart, i).trim().uppercase()
            val value = StringBuilder()
            if (i < n && line[i] == '=') {
                i++
                var quoted = false
                while (i < n && (quoted || (line[i] != ':' && line[i] != ';'))) {
                    if (line[i] == '"') quoted = !quoted else value.append(line[i])
                    i++
                }
            }
            if (key.isNotEmpty()) params[key] = value.toString()
        }
        if (i >= n || line[i] != ':') return null
        return ICalProperty(name, params, line.substring(i + 1))
    }
}
