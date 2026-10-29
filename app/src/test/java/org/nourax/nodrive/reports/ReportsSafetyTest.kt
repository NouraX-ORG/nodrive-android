/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data safety and privacy of the problem reports (round 3), as a guard over the sources of the package:
 *  - only [ReportFiles.deleteOwn] deletes a file, and nothing deletes a user file, a Drive file or an upload row;
 *  - no host name or address is written in the code (the reports go through the signed-in account's client only);
 *  - nothing periodic, no analytics or crash SDK.
 */
class ReportsSafetyTest {
    private fun sources(): List<File> {
        val relative = "src/main/java/org/nourax/nodrive/reports"
        val dir = listOf(File(relative), File("app/$relative")).first { it.isDirectory }
        return dir.listFiles { file -> file.extension == "kt" }.orEmpty().toList()
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
        assertTrue("sources not found: ${sources().size}", sources().size >= 15)
    }

    @Test
    fun onlyDeleteOwnDeletesAFile() {
        val deletes = hits(Regex("\\.delete\\("))
        assertEquals("delete( only in ReportFiles.kt: $deletes", listOf("ReportFiles.kt"), deletes.map { it.substringBefore(':') }.distinct())
        assertEquals("exactly one delete call: $deletes", 1, deletes.size)
    }

    @Test
    fun nothingDeletesRemovesOrMovesUserData() {
        val forbidden = listOf(
            "deleteRecursively",
            "Files\\.delete",
            "RemoveFileOperation",
            "RenameFileOperation",
            "MoveFileOperation",
            "CopyFileOperation",
            "removeUpload",
            "removeAllUploads",
            "deleteSyncedFolder",
            "contentResolver\\.delete",
            "resolver\\.delete",
            "removeFile\\(",
            "UnshareOperation",
            "RemoveShare"
        ).joinToString("|").toRegex()
        assertEquals(emptyList<String>(), hits(forbidden))
    }

    @Test
    fun noHostNameOrAddressIsWrittenInTheCode() {
        assertEquals(emptyList<String>(), hits(Regex("https?://")))
        assertEquals(emptyList<String>(), hits(Regex("\\bnourax\\.(org|com)\\b")))
        assertEquals(emptyList<String>(), hits(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")))
    }

    @Test
    fun nothingIsPeriodicAndNoAnalyticsOrCrashSdkIsUsed() {
        assertEquals(emptyList<String>(), hits(Regex("PeriodicWorkRequest|AlarmManager|JobScheduler|setRepeating")))
        assertEquals(
            emptyList<String>(),
            hits(Regex("import (com\\.google\\.firebase|io\\.sentry|com\\.crashlytics|com\\.bugsnag|org\\.acra|com\\.microsoft\\.appcenter|com\\.amplitude)"))
        )
    }

    @Test
    fun theOnlyTextTheWorkerSharesWithIsTheSupportAccountAndTheFolder() {
        val worker = File(sources().first().parentFile, "ReportSender.kt").readText()
        assertTrue(worker.contains("\"nourax-support\""))
        assertTrue(worker.contains("\"/Problem reports\""))
    }

    @Test
    fun theScrubberMakesNoMatcherPerMatch() {
        // Kotlin's Regex.replace(text) { }, find(text, start) and findAll make a new Matcher for each match; on Android a
        // new Matcher copies the whole text. The scrubber steps one java.util.regex.Matcher with find() instead.
        val scrubber = File(sources().first().parentFile, "Scrubber.kt")
        val bad = code(scrubber).filter { (_, line) -> perMatchMatcher.containsMatchIn(line) }.map { "Scrubber.kt:${it.index + 1}" }
        assertEquals("one Matcher per pattern: $bad", emptyList<String>(), bad)
        assertTrue(code(scrubber).any { (_, line) -> line.contains("matcher.find()") })
    }

    private val perMatchMatcher = Regex("\\.replace\\(|\\.find\\([^)]|\\.findAll\\(|\\.split\\(|\\.matches\\(")

    @Test
    fun theGuardWouldCatchWhatItIsFor() {
        assertTrue(perMatchMatcher.containsMatchIn("out = pattern.replace(out) { it.value }"))
        assertTrue(perMatchMatcher.containsMatchIn("var match = emailDomain.find(text, start)"))
        assertTrue(!perMatchMatcher.containsMatchIn("while (matcher.find()) {"))
        assertTrue(Regex("\\.delete\\(").containsMatchIn("file.delete()"))
        assertTrue(Regex("https?://").containsMatchIn("val u = \"https://example.test\""))
        assertTrue(Regex("PeriodicWorkRequest").containsMatchIn("PeriodicWorkRequestBuilder<X>(1, DAYS)"))
    }
}
