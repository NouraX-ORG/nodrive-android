/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.util.regex.Matcher

/**
 * Takes secrets out of any text before it goes into a problem report (logs, crash text, the note of the user). Pure and
 * idempotent: running it twice changes nothing more. File and folder names are kept on purpose, because the report needs
 * them to find the problem. Over-scrubbing (for example a long hex etag) is accepted.
 */
object Scrubber {
    private const val SECRET = "<removed>"
    private const val EMAIL = "<email>"
    private const val KEY_MATERIAL = "<removed key>"
    private const val MAX_SLASHES_IN_SECRET = 3
    private const val MAX_LOCAL_PART = 64 // RFC 5321

    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)

    // -----BEGIN ...----- up to -----END ...-----, then a lone BEGIN line with the base64 lines under it.
    private val pemBlock = Regex("-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----")
    private val pemLoose = Regex("-----BEGIN [A-Z0-9 ]+-----(?:[ \\t]*\\r?\\n[A-Za-z0-9+/=]{16,}[ \\t]*)*")

    // Whole header lines.
    private val authHeader = Regex("\\b(Proxy-Authorization|Authorization)\\b(\\s*[:=]\\s*)[^\\r\\n]*", ignoreCase)
    private val cookieHeader = Regex("\\b(Set-Cookie|Cookie)\\b(\\s*[:=]\\s*)[^\\r\\n]*", ignoreCase)

    // "Basic xxxx" and "Bearer xxxx" anywhere.
    private val scheme = Regex("\\b(Basic|Bearer)(\\s+)[A-Za-z0-9._~+/=-]{8,}", ignoreCase)

    // Cookie pairs: the known session cookies, __Host-/__Secure- cookies and the "oc<instance id>" cookies.
    private val namedCookies = Regex(
        "\\b(oc_sessionPassphrase|nc_session_id|nc_sameSiteCookie[A-Za-z]*|nc_username|nc_token|" +
            "__Host-[A-Za-z0-9._-]*|__Secure-[A-Za-z0-9._-]*)=[^;\\s,\"']*",
        ignoreCase
    )
    private val instanceCookie = Regex("\\b(oc[a-z0-9]{10,12})=[^;\\s,\"']+")

    // Passwords in a URL (https://user:secret@host/).
    private val urlUserInfo = Regex("""(?i)\b(https?:/{2})[^/\s:@"]+:[^/\s@"]+@""")

    // URL query values.
    private val queryValue = Regex(
        "([?&])(token|password|pass|secret|key|sig|code|state)=[^&\\s\"']*",
        ignoreCase
    )

    // key=value or key: value for words that name a secret. The name part is shared by the three patterns below.
    private const val SECRET_NAME =
        "(?<![A-Za-z0-9_])((?:(?:access|refresh|id|api|client|app|auth|request|csrf)[_-]?)?" +
            "(?:token|passwd|password|secret|apikey|api_key|app_password|pwd))"

    // A quoted value is taken up to its closing quote, spaces included: "password":"correct horse battery staple".
    // One pattern per quote character, and the value is an unrolled loop (plain characters, then escape pairs). A group
    // that repeats one character per round costs a backtrack frame for each of them, so a very long value overflows the
    // stack (the pass would then stop quietly on Android).
    private fun namedSecretQuoted(quote: String) = Regex(
        """$SECRET_NAME(["']?\s*[:=]\s*)$quote(?!<removed>)""" +
            """(?:[^$quote\\\r\n]+(?:\\.[^$quote\\\r\n]*)*|(?:\\.[^$quote\\\r\n]*)+)""",
        ignoreCase
    )
    private val namedSecretDoubleQuoted = namedSecretQuoted("\"")
    private val namedSecretSingleQuoted = namedSecretQuoted("'")

    // The same inside JSON that is escaped in a string: \"appPassword\":\"two words\". The value is an unrolled loop too.
    private val namedSecretEscaped = Regex(
        """$SECRET_NAME(\\["']\s*[:=]\s*\\)(["'])(?!<removed>)""" +
            """(?:[^\\\r\n]|\\(?!["']))[^\\\r\n]*(?:\\(?!["'])[^\\\r\n]*)*""",
        ignoreCase
    )

    // An unquoted value stops at the first space.
    private val namedSecret = Regex(
        """$SECRET_NAME(["']?\s*[:=]\s*["']?)(?!<)[^\s"',&;<>]+""",
        ignoreCase
    )

    // End-to-end encryption material.
    private val e2eField = Regex(
        "(?<![A-Za-z0-9_])(\"?(?:privateKey|private_key|mnemonic|encryptedKey|metadataKeys?)\"?\\s*[:=]\\s*)" +
            "(\"[^\"]*\"|\\[[^\\]]*\\]|[^\\r\\n,}]*[^\\r\\n,}\\s])",
        ignoreCase
    )

    // Nextcloud app passwords (xxxxx-xxxxx-xxxxx-xxxxx-xxxxx) and runs of 32 or more letters and digits.
    private val appPassword = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{5}(?:-[A-Za-z0-9]{5}){4}(?![A-Za-z0-9])")
    private val longRun = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{32,}(?![A-Za-z0-9])")

    // A JWT without a "Bearer" label: three base64url parts, the first one starting with {" (eyJ).
    private val jwt = Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val base64Run = Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{64,}={0,2}(?![A-Za-z0-9+/=])")

    // The at-sign is "@" or "%40": upstream logs avatar URIs with the user id URL-encoded (Uri.encode("alice@example.com")).
    // Only this part is a regex; the local part in front of it is read backwards by hand (see scrubEmails). A regex that
    // starts at the local part is quadratic on a long run of such characters without an at-sign (80 KB took 90 s), and one
    // long log line could then stall every report.
    private val emailDomain = Regex(
        "(?:@|%40)[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,30}\\.[A-Za-z]{2,}",
        ignoreCase
    )

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = pemBlock.replaceEach(out) { KEY_MATERIAL }
        out = pemLoose.replaceEach(out) { KEY_MATERIAL }
        out = authHeader.replaceEach(out) { "${it.group(1)}${it.group(2)}$SECRET" }
        out = cookieHeader.replaceEach(out) { "${it.group(1)}${it.group(2)}$SECRET" }
        out = scheme.replaceEach(out) { "${it.group(1)}${it.group(2)}$SECRET" }
        out = namedCookies.replaceEach(out) { "${it.group(1)}=$SECRET" }
        out = instanceCookie.replaceEach(out) { "${it.group(1)}=$SECRET" }
        out = urlUserInfo.replaceEach(out) { "${it.group(1)}$SECRET@" }
        out = queryValue.replaceEach(out) { "${it.group(1)}${it.group(2)}=$SECRET" }
        out = namedSecretDoubleQuoted.replaceEach(out) { "${it.group(1)}${it.group(2)}\"$SECRET" }
        out = namedSecretSingleQuoted.replaceEach(out) { "${it.group(1)}${it.group(2)}'$SECRET" }
        out = namedSecretEscaped.replaceEach(out) { "${it.group(1)}${it.group(2)}${it.group(3)}$SECRET" }
        out = namedSecret.replaceEach(out) { "${it.group(1)}${it.group(2)}$SECRET" }
        out = e2eField.replaceEach(out) { "${it.group(1)}$SECRET" }
        out = appPassword.replaceEach(out) { SECRET }
        out = jwt.replaceEach(out) { SECRET }
        out = longRun.replaceEach(out) { if (hasLetterAndDigit(it.group())) SECRET else it.group() }
        out = base64Run.replaceEach(out) { if (looksLikeSecret(it.group())) SECRET else it.group() }
        out = scrubEmails(out)
        return out
    }

    /**
     * Replaces every match of this pattern, with ONE [Matcher] that steps with find(). Kotlin's Regex.replace(text) { } and
     * Regex.find(text, start) make a new Matcher for each match, and on Android a new Matcher copies the whole text into
     * native memory: the cost of a pass would grow with matches times the length of the text.
     */
    private inline fun Regex.replaceEach(text: String, replacement: (Matcher) -> String): String {
        val matcher = toPattern().matcher(text)
        if (!matcher.find()) return text
        val builder = StringBuilder(text.length)
        var copied = 0
        do {
            builder.append(text, copied, matcher.start()).append(replacement(matcher))
            copied = matcher.end()
        } while (matcher.find())
        return builder.append(text, copied, text.length).toString()
    }

    /**
     * Replaces every address (local part of 1 to 64 characters, at-sign, domain) with a placeholder. Linear in the text.
     * A domain match holds no "@" and no "%", so no other match can start inside it: the matcher simply goes on.
     */
    private fun scrubEmails(text: String): String {
        val matcher = emailDomain.toPattern().matcher(text)
        val builder = StringBuilder(text.length)
        var copied = 0
        while (matcher.find()) {
            val atSign = matcher.start()
            var start = atSign
            val floor = maxOf(copied, atSign - MAX_LOCAL_PART)
            while (start > floor && isLocalPartChar(text[start - 1])) start--
            if (start < atSign) {
                builder.append(text, copied, start).append(EMAIL)
                copied = matcher.end()
            }
        }
        return if (copied == 0) text else builder.append(text, copied, text.length).toString()
    }

    private fun isLocalPartChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

    /** Real tokens and app passwords mix letters and digits; a long plain word is a file or folder name (kept). */
    private fun hasLetterAndDigit(run: String): Boolean = run.any { it.isDigit() } && run.any { it.isLetter() }

    /** A 64+ character base64 run is a path (kept) unless it has '+', '=' or a mix of digits and both letter cases. */
    private fun looksLikeSecret(run: String): Boolean {
        if (run.contains('+') || run.endsWith("=")) return true
        val mixed = run.any { it.isDigit() } && run.any { it.isUpperCase() } && run.any { it.isLowerCase() }
        return mixed && run.count { it == '/' } <= MAX_SLASHES_IN_SECRET
    }
}
