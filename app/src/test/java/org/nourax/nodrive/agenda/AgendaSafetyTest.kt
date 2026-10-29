/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.net.AccountAgendaHttp
import java.io.File

/**
 * Data safety and privacy of NoAgenda's core step, as a guard over the sources of the package:
 *  - nothing is deleted (no file, no Drive file, no upload row) and nothing is written to the server (no write verb);
 *  - no host name is written in the code (the requests go through the signed-in account's client only);
 *  - no analytics or crash SDK.
 */
class AgendaSafetyTest {
    private fun sources(): List<File> {
        val relative = "src/main/java/org/nourax/nodrive/agenda"
        val dir = listOf(File(relative), File("app/$relative")).first { it.isDirectory }
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun code(file: File) = file.readLines().withIndex().filterNot { (_, line) ->
        val trimmed = line.trim()
        trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
    }

    private fun hits(pattern: Regex, except: (File) -> Boolean = { false }): List<String> =
        sources().filterNot(except).flatMap { file ->
            code(file).filter { (_, line) -> pattern.containsMatchIn(line) }.map { "${file.name}:${it.index + 1}" }
        }

    @Test
    fun theSourcesAreFound() {
        assertTrue("sources not found: ${sources().size}", sources().size >= 40)
    }

    @Test
    fun nothingIsDeletedOnThePhoneOrTheDrive() {
        assertEquals(emptyList<String>(), hits(Regex("""\.delete\(|deleteRecursively|RemoveFileOperation|RemoveFilesOperation|FileUtils\.delete""")))
        assertEquals(emptyList<String>(), hits(Regex("""\bFile\(.*\)\.delete""")))
    }

    @Test
    fun noWriteVerbIsSentToTheServer() {
        assertEquals(emptyList<String>(), hits(Regex(""""(DELETE|PUT|POST|PATCH|MOVE|COPY|MKCOL|PROPPATCH)"""")))
    }

    @Test
    fun theTransportRefusesEveryWriteVerb() {
        val http = AccountAgendaHttp("https://drive.example.test", "Basic x", okhttp3.OkHttpClient())
        for (verb in listOf("PUT", "POST", "DELETE", "MOVE", "COPY", "MKCOL", "PATCH", "PROPPATCH")) {
            val result = http.send(verb, "remote.php/dav/files/ali/x.txt", emptyMap(), null, null)
            assertEquals(verb, 0, result.status)
            assertTrue(verb, result.networkError)
        }
    }

    @Test
    fun theTransportNeverLeavesTheAccountsOrigin() {
        val http = AccountAgendaHttp("https://drive.example.test/sub", "Basic x", okhttp3.OkHttpClient())
        assertEquals("https://drive.example.test/sub/remote.php/dav/", http.urlFor("remote.php/dav/"))
        assertEquals("https://drive.example.test/remote.php/dav/calendars/ali/", http.urlFor("/remote.php/dav/calendars/ali/"))
        assertEquals("https://drive.example.test/x", http.urlFor("https://drive.example.test/x"))
        assertEquals(null, http.urlFor("https://evil.example.org/x"))
        assertEquals(null, http.urlFor("http://drive.example.test/x")) // another scheme is another origin
        assertEquals(null, http.urlFor("//evil.example.org/x"))
        assertEquals(null, http.urlFor("https://drive.example.test.evil.org/x"))
    }

    @Test
    fun noHostNameOrAddressIsWrittenInTheCode() {
        // The only addresses are XML names (namespaces and a parser feature), never a place to send anything to.
        val allowed = listOf(
            "http://calendarserver.org/ns/", "http://apple.com/ns/ical/", "http://owncloud.org/ns", "http://nextcloud.org/ns",
            "http://apache.org/xml/features/disallow-doctype-decl"
        )
        val found = sources().flatMap { file ->
            code(file).flatMap { (_, line) -> Regex("""https?://[^\s"')]+""").findAll(line).map { it.value }.toList() }
        }.filterNot { it in allowed }
        assertEquals(emptyList<String>(), found)
        assertEquals(emptyList<String>(), hits(Regex("""drive\.nourax\.org|nourax\.org|\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")))
    }

    @Test
    fun noAnalyticsOrCrashSdk() {
        assertEquals(emptyList<String>(), hits(Regex("""firebase|crashlytics|analytics|sentry|bugsnag|appcenter|mixpanel""", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun aMessageNeverCarriesAUserVisibleValue() {
        // AgendaLog is the only logger, and only these calls exist: ids, counts and status codes.
        val logLines = hits(Regex("""Log_OC\.|android\.util\.Log|println\(|Timber"""), except = { it.name == "AgendaLog.kt" })
        assertEquals(emptyList<String>(), logLines)
        assertFalse(sources().none { it.name == "AgendaLog.kt" })
    }
}
