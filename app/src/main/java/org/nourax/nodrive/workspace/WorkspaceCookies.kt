/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A cookie of the server's answer, ready for CookieManager.setCookie (host-only: no Domain attribute). */
data class WorkspaceCookie(val name: String, val path: String, val setCookie: String)

/** Design rule 5: which Set-Cookie headers of the bootstrap answer are handed to the web view. */
object WorkspaceCookies {
    const val PASSPHRASE = "oc_sessionPassphrase"
    private const val SESSION_PREFIX = "oc"
    private const val DEFAULT_PATH = "/"
    private const val EXPIRED_YEAR = "1970"

    fun fromSetCookieHeaders(
        headers: List<String>,
        now: Instant = Instant.now()
    ): List<WorkspaceCookie> {
        val kept = LinkedHashMap<Pair<String, String>, WorkspaceCookie>()
        for (header in headers) {
            val parts = header.split(";").map(String::trim).filter(String::isNotEmpty)
            val name = parts.firstOrNull()?.substringBefore("=")?.trim()
            if (name.isNullOrEmpty() || !parts.first().contains("=")) continue
            val attributes = parts.drop(1)
            val path = attributes.attribute("path") ?: DEFAULT_PATH
            kept.remove(name to path)
            if (!isExpired(parts.first().substringAfter("="), attributes, now)) {
                val hostOnly = listOf(parts.first()) + attributes.filterNot { it.startsWith("domain=", ignoreCase = true) }
                kept[name to path] = WorkspaceCookie(name, path, hostOnly.joinToString("; "))
            }
        }
        return kept.values.toList()
    }

    /** The server must have granted the session id cookie and the cookie that decrypts the session. */
    fun hasSession(cookies: List<WorkspaceCookie>): Boolean =
        cookies.any { it.name == PASSPHRASE } &&
            cookies.any { it.name != PASSPHRASE && it.name.startsWith(SESSION_PREFIX) }

    private fun List<String>.attribute(key: String): String? =
        firstOrNull { it.startsWith("$key=", ignoreCase = true) }?.substringAfter("=")?.trim()

    private fun isExpired(value: String, attributes: List<String>, now: Instant): Boolean {
        if (value.isEmpty() || value == "deleted") return true
        val maxAge = attributes.attribute("max-age")?.toLongOrNull()
        if (maxAge != null) return maxAge <= 0
        val expires = attributes.attribute("expires") ?: return false
        return try {
            ZonedDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
                .toInstant()
                .isBefore(now)
        } catch (_: DateTimeParseException) {
            expires.contains(EXPIRED_YEAR)
        }
    }
}
