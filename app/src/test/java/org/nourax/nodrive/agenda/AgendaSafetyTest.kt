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
import org.nourax.nodrive.agenda.net.WritePolicy
import java.io.File

/**
 * Data safety and privacy of NoAgenda's core step, as a guard over the sources of the package:
 *  - nothing is deleted (no file, no Drive file, no upload row); the server is written only by the edit step (events, to-dos, NoGantt
 *    task updates, NoBoard card updates) and only an event or a to-do can be removed;
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

    private val writers = setOf("CalDavWriter.kt", "GanttWriter.kt", "DeckWriter.kt")

    @Test
    fun onlyTheWritersOfTheEditStepSendWrites() {
        // Writes are sent by the three writers and checked by WritePolicy; nowhere else (design 9, plan 2.4).
        val allowed = writers + setOf("WritePolicy.kt", "AccountAgendaHttp.kt")
        assertEquals(emptyList<String>(), hits(Regex(""""(PUT|POST)""""), except = { it.name in allowed }))
        // A verb that NoAgenda never uses at all.
        assertEquals(emptyList<String>(), hits(Regex(""""(PATCH|MOVE|COPY|MKCOL|PROPPATCH)"""")))
    }

    @Test
    fun deleteIsSentOnlyForAnEventOrAToDoThroughCalDav() {
        // The one DELETE of the app: a calendar object, with If-Match; the server keeps it in the calendar trash bin. Never a file of the
        // Drive, a NoGantt task or a card.
        assertEquals(emptyList<String>(), hits(Regex(""""DELETE""""), except = { it.name == "CalDavWriter.kt" || it.name == "WritePolicy.kt" }))
        assertTrue(hits(Regex(""""DELETE"""")).isNotEmpty())
        assertTrue(File(sourceRoot(), "edit/CalDavWriter.kt").readText().contains("If-Match"))
    }

    private fun sourceRoot(): File {
        val relative = "src/main/java/org/nourax/nodrive/agenda"
        return listOf(File(relative), File("app/$relative")).first { it.isDirectory }
    }

    @Test
    fun noDriveFileIsWritten() {
        // Only the agent list is read from the Drive (a GET); nothing there is written.
        assertEquals(emptyList<String>(), hits(Regex("""dav/files"""), except = { it.name == "RegistrySource.kt" }))
        assertFalse(WritePolicy.allows("PUT", "remote.php/dav/files/ali/NoAgenda/agent-registry.json"))
        assertFalse(WritePolicy.allows("DELETE", "remote.php/dav/files/ali/x.txt"))
    }

    @Test
    fun theWritePolicyAllowsOnlyTheEditsOfDesignSection9() {
        val cal = "remote.php/dav/calendars/ali/work/abc.ics"
        assertTrue(WritePolicy.allows("PUT", "/$cal"))
        assertTrue(WritePolicy.allows("DELETE", "/$cal"))
        assertFalse(WritePolicy.allows("DELETE", "/remote.php/dav/calendars/ali/work/"))
        assertFalse(WritePolicy.allows("DELETE", "/remote.php/dav/calendars/ali/work"))
        assertFalse(WritePolicy.allows("PUT", "/remote.php/dav/calendars/ali/work/../../files/ali/x.ics"))
        val gantt = "ocs/v2.php/apps/nc_gantt/api/v1"
        assertTrue(WritePolicy.allows("PUT", "$gantt/tasks/12?format=json"))
        assertTrue(WritePolicy.allows("POST", "$gantt/changes/op_ab-12/revert?format=json"))
        assertFalse(WritePolicy.allows("DELETE", "$gantt/tasks/12"))
        assertFalse(WritePolicy.allows("POST", "$gantt/projects/1/tasks"))
        assertFalse(WritePolicy.allows("PUT", "$gantt/projects/1"))
        val deck = "index.php/apps/deck/api/v1.0"
        assertTrue(WritePolicy.allows("PUT", "$deck/boards/3/stacks/4/cards/5"))
        assertTrue(WritePolicy.allows("PUT", "$deck/boards/3/stacks/4/cards/5/assignLabel"))
        assertTrue(WritePolicy.allows("PUT", "$deck/boards/3/stacks/4/cards/5/removeLabel"))
        assertTrue(WritePolicy.allows("POST", "$deck/boards/3/labels"))
        assertFalse(WritePolicy.allows("DELETE", "$deck/boards/3/stacks/4/cards/5"))
        assertFalse(WritePolicy.allows("POST", "$deck/boards/3/stacks/4/cards"))
        assertFalse(WritePolicy.allows("DELETE", "$deck/boards/3"))
        for (verb in listOf("PATCH", "MOVE", "COPY", "MKCOL", "PROPPATCH")) assertFalse(verb, WritePolicy.allows(verb, "/$cal"))
        assertTrue(WritePolicy.allows("GET", "anything"))
    }

    @Test
    fun theTransportRefusesEveryOtherWrite() {
        val http = AccountAgendaHttp("https://drive.example.test", "Basic x", okhttp3.OkHttpClient())
        for ((verb, path) in listOf(
            "PUT" to "remote.php/dav/files/ali/x.txt", "DELETE" to "remote.php/dav/files/ali/x.txt", "POST" to "ocs/v2.php/cloud/users",
            "MOVE" to "remote.php/dav/files/ali/x.txt", "COPY" to "remote.php/dav/files/ali/x.txt", "MKCOL" to "remote.php/dav/files/ali/d",
            "PATCH" to "remote.php/dav/files/ali/x.txt", "PROPPATCH" to "remote.php/dav/files/ali/x.txt",
            "DELETE" to "ocs/v2.php/apps/nc_gantt/api/v1/tasks/1", "DELETE" to "index.php/apps/deck/api/v1.0/boards/1/stacks/2/cards/3"
        )) {
            val result = http.send(verb, path, emptyMap(), null, null)
            assertEquals("$verb $path", 0, result.status)
            assertTrue("$verb $path", result.refused)
            assertFalse("$verb $path: a refused write is not a lost network", result.networkError)
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
