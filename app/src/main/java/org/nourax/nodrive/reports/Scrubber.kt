/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

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
    private val urlUserInfo = Regex("(?i)\\b(https?:/{2})[^/\\s:@]+:[^/\\s@]+@")

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
    private val namedSecretQuoted = Regex(
        """$SECRET_NAME(["']?\s*[:=]\s*)(["'])(?!<removed>)((?:\\.|(?!\3)[^\\\r\n])+)""",
        ignoreCase
    )

    // The same inside JSON that is escaped in a string: \"appPassword\":\"two words\".
    private val namedSecretEscaped = Regex(
        """$SECRET_NAME(\\["']\s*[:=]\s*\\)(["'])(?!<removed>)((?:[^\\\r\n]|\\(?!["']))+)""",
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
    // The local part is capped at 64 characters (RFC 5321): without a cap the search is quadratic on a long run of such
    // characters that holds no at-sign (80 KB took 90 s), and one long log line could stall every report.
    private val email = Regex("[A-Za-z0-9._%+-]{1,64}(?:@|%40)[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}", ignoreCase)

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = pemBlock.replace(out, KEY_MATERIAL)
        out = pemLoose.replace(out, KEY_MATERIAL)
        out = authHeader.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$SECRET" }
        out = cookieHeader.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$SECRET" }
        out = scheme.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$SECRET" }
        out = namedCookies.replace(out) { "${it.groupValues[1]}=$SECRET" }
        out = instanceCookie.replace(out) { "${it.groupValues[1]}=$SECRET" }
        out = urlUserInfo.replace(out) { "${it.groupValues[1]}$SECRET@" }
        out = queryValue.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=$SECRET" }
        out = namedSecretQuoted.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}$SECRET" }
        out = namedSecretEscaped.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}$SECRET" }
        out = namedSecret.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$SECRET" }
        out = e2eField.replace(out) { "${it.groupValues[1]}$SECRET" }
        out = appPassword.replace(out, SECRET)
        out = jwt.replace(out, SECRET)
        out = longRun.replace(out) { if (hasLetterAndDigit(it.value)) SECRET else it.value }
        out = base64Run.replace(out) { if (looksLikeSecret(it.value)) SECRET else it.value }
        out = email.replace(out, EMAIL)
        return out
    }

    /** Real tokens and app passwords mix letters and digits; a long plain word is a file or folder name (kept). */
    private fun hasLetterAndDigit(run: String): Boolean = run.any { it.isDigit() } && run.any { it.isLetter() }

    /** A 64+ character base64 run is a path (kept) unless it has '+', '=' or a mix of digits and both letter cases. */
    private fun looksLikeSecret(run: String): Boolean {
        if (run.contains('+') || run.endsWith("=")) return true
        val mixed = run.any { it.isDigit() } && run.any { it.isUpperCase() } && run.any { it.isLowerCase() }
        return mixed && run.count { it == '/' } <= MAX_SLASHES_IN_SECRET
    }
}
