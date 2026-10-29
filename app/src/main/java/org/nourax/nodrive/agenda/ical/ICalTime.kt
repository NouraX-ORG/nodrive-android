/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ical

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Turns a TZID into a zone: IANA names, then Windows names. */
fun interface ZoneResolver {
    fun zoneFor(tzid: String): ZoneId?

    companion object {
        /** IANA ids through `java.time`; Windows ids through a table and, on the phone, the platform's ICU data. */
        val DEFAULT = ZoneResolver { tzid -> ICalTime.zoneFor(tzid) }
    }
}

class ICalTime(
    val valid: Boolean = false,
    /** A DATE value (all day). */
    val isDate: Boolean = false,
    val date: LocalDate? = null,
    val utc: Instant? = null,
    /** No TZID and no Z: the local time of the phone. */
    val floating: Boolean = false,
    /** A TZID that could not be resolved: the time is the phone's local time, flagged. */
    val approximated: Boolean = false
) {
    companion object {
        private val WINDOWS = mapOf(
            "UTC" to "UTC",
            "GMT Standard Time" to "Europe/London",
            "W. Europe Standard Time" to "Europe/Berlin",
            "Central Europe Standard Time" to "Europe/Budapest",
            "Romance Standard Time" to "Europe/Paris",
            "E. Europe Standard Time" to "Europe/Chisinau",
            "Eastern Standard Time" to "America/New_York",
            "Central Standard Time" to "America/Chicago",
            "Mountain Standard Time" to "America/Denver",
            "Pacific Standard Time" to "America/Los_Angeles",
            "Arabian Standard Time" to "Asia/Dubai",
            "Arab Standard Time" to "Asia/Riyadh",
            "Pakistan Standard Time" to "Asia/Karachi",
            "India Standard Time" to "Asia/Kolkata",
            "China Standard Time" to "Asia/Shanghai",
            "Tokyo Standard Time" to "Asia/Tokyo",
            "AUS Eastern Standard Time" to "Australia/Sydney"
        )

        fun zoneFor(tzid: String): ZoneId? {
            if (tzid.isEmpty()) return null
            var name = tzid
            // Some clients write "/mozilla.org/20070129_1/Europe/London".
            if (name.startsWith("/")) {
                val parts = name.split('/').filter { it.isNotEmpty() }
                if (parts.size >= 2) name = parts.takeLast(2).joinToString("/")
            }
            try {
                return ZoneId.of(name)
            } catch (_: DateTimeException) {
                // not an IANA id
            }
            WINDOWS[name]?.let { return runCatching { ZoneId.of(it) }.getOrNull() }
            val icu = runCatching { android.icu.util.TimeZone.getIDForWindowsID(name, "001") }.getOrNull()
            return icu?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        }

        fun parseValue(
            valueIn: String,
            tzid: String,
            dateOnly: Boolean,
            floatingZone: ZoneId,
            resolver: ZoneResolver = ZoneResolver.DEFAULT
        ): ICalTime {
            val value = valueIn.trim()
            if (value.length < 8) return ICalTime()
            val date = parseDate(value.substring(0, 8)) ?: return ICalTime()
            if (dateOnly || value.length == 8) return ICalTime(valid = true, isDate = true, date = date)
            if (value.length >= 15 && value[8] == 'T') {
                val time = parseTime(value.substring(9, 15)) ?: return ICalTime()
                val local = LocalDateTime.of(date, time)
                if (value.endsWith("Z") || value.endsWith("z")) {
                    return ICalTime(valid = true, date = date, utc = local.atZone(java.time.ZoneOffset.UTC).toInstant())
                }
                var zone = resolver.zoneFor(tzid)
                var approximated = false
                var floating = false
                if (zone == null) {
                    floating = tzid.isEmpty()
                    approximated = tzid.isNotEmpty()
                    zone = floatingZone
                }
                return ICalTime(
                    valid = true,
                    date = date,
                    utc = local.atZone(zone).toInstant(),
                    floating = floating,
                    approximated = approximated
                )
            }
            return ICalTime()
        }

        fun parse(property: ICalProperty, floatingZone: ZoneId, resolver: ZoneResolver = ZoneResolver.DEFAULT): ICalTime {
            val dateOnly = property.param("VALUE").equals("DATE", ignoreCase = true)
            return parseValue(property.raw, property.param("TZID"), dateOnly, floatingZone, resolver)
        }

        /** "P1DT2H30M" -> seconds; -1 when it is not a duration. */
        fun parseDurationSeconds(valueIn: String): Long {
            var value = valueIn.trim()
            var sign = 1L
            if (value.startsWith("-")) {
                sign = -1
                value = value.substring(1)
            } else if (value.startsWith("+")) {
                value = value.substring(1)
            }
            if (!value.startsWith("P")) return -1
            var total = 0L
            var inTime = false
            var number = ""
            for (i in 1 until value.length) {
                val c = value[i]
                when {
                    c == 'T' -> inTime = true
                    c.isDigit() -> number += c
                    else -> {
                        if (number.isEmpty()) return -1
                        val n = number.toLongOrNull() ?: return -1
                        number = ""
                        when (c) {
                            'W' -> total += n * 7 * 86400
                            'D' -> total += n * 86400
                            'H' -> total += n * 3600
                            'M' -> if (inTime) total += n * 60
                            'S' -> total += n
                            else -> return -1
                        }
                    }
                }
            }
            return sign * total
        }

        private fun parseDate(text: String): LocalDate? {
            if (text.length != 8 || !text.all { it.isDigit() }) return null
            return try {
                LocalDate.of(text.substring(0, 4).toInt(), text.substring(4, 6).toInt(), text.substring(6, 8).toInt())
            } catch (_: DateTimeException) {
                null
            }
        }

        private fun parseTime(text: String): java.time.LocalTime? {
            if (text.length != 6 || !text.all { it.isDigit() }) return null
            return try {
                java.time.LocalTime.of(text.substring(0, 2).toInt(), text.substring(2, 4).toInt(), text.substring(4, 6).toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}
