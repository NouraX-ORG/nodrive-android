/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipInputStream

class ReportBuilderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val meta = ReportMeta("35.0.1-nourax.1", "350001", "Android 15 (API 35)", "Google Pixel 6", "en-GB", "2026-10-28T13:05:09Z", "drive.example.org")

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return out
    }

    private fun files() = ReportFiles(File(temp.root, "nodrive-reports")).also { it.ensure() }

    private fun logFile(name: String, vararg lines: String) = File(temp.root, name).also { it.writeText(lines.joinToString("\n") + "\n") }

    private fun input(kind: ReportKind, crash: String?, logs: List<File>, note: String = "", at: Long = 1_793_192_709_000L) =
        ReportInput(kind, meta, note, crash, logs, at, "35.0.1-nourax.1")

    @Test
    fun aManualReportHasReportJsonAndLogsButNoCrashText() {
        val log = logFile("log.txt", "line one", "line two")
        val zip = ReportBuilder(files()).build(input(ReportKind.MANUAL, null, listOf(log), "what happened"))
        assertEquals("2026-10-28_13-05-09_android_35.0.1-nourax.1.zip", zip.name)
        val entries = unzip(zip.readBytes())
        assertEquals(listOf("report.json", "logs/log.txt"), entries.keys.toList())
        assertEquals("line one\nline two\n", entries["logs/log.txt"])
        assertTrue(entries.getValue("report.json").contains("\"user_note\": \"what happened\""))
    }

    @Test
    fun aCrashReportHasTheCrashText() {
        val zip = ReportBuilder(files()).build(input(ReportKind.CRASH, "Thread: main\njava.lang.IllegalStateException: boom\n", emptyList()))
        val entries = unzip(zip.readBytes())
        assertEquals(listOf("report.json", "crash.txt"), entries.keys.toList())
        assertTrue(entries.getValue("crash.txt").contains("IllegalStateException: boom"))
        assertTrue(entries.getValue("report.json").contains("\"kind\": \"crash\""))
    }

    @Test
    fun everythingIsScrubbedBeforeItIsZipped() {
        val log = logFile(
            "log.txt",
            "PROPFIND /remote.php/dav/files/anna/Phone backup/IMG_1.jpg",
            "Authorization: Bearer PLANTEDBEARER9999",
            "cookie jar oc_sessionPassphrase=PLANTEDPASSPHRASE",
            "login url ?token=PLANTEDTOKEN&dir=/Photos by anna@example.org"
        )
        val zip = ReportBuilder(files()).build(
            input(ReportKind.CRASH, "crash with app password abcde-fghij-klmno-pqrst-uvwxy", listOf(log), "my password=PLANTEDNOTE")
        )
        val all = unzip(zip.readBytes()).values.joinToString("\n")
        listOf("PLANTEDBEARER", "PLANTEDPASSPHRASE", "PLANTEDTOKEN", "PLANTEDNOTE", "abcde-fghij", "anna@example.org").forEach {
            assertFalse("$it survived", all.contains(it))
        }
        assertTrue("file and folder names are kept", all.contains("/Phone backup/IMG_1.jpg"))
        assertTrue(all.contains("dir=/Photos"))
        assertTrue(all.contains("<email>"))
    }

    @Test
    fun logFilesAreJoinedOldestFirstAndMissingOnesAreSkipped() {
        val old = logFile("log.txt.2", "oldest")
        val newest = logFile("log.txt", "newest")
        val zip = ReportBuilder(files()).build(input(ReportKind.MANUAL, null, listOf(old, File(temp.root, "missing"), newest)))
        assertEquals("oldest\nnewest\n", unzip(zip.readBytes())["logs/log.txt"])
    }

    @Test
    fun twoReportsInTheSameSecondGetDifferentNames() {
        val f = files()
        val log = logFile("log.txt", "x")
        val first = ReportBuilder(f).build(input(ReportKind.MANUAL, null, listOf(log)))
        val second = ReportBuilder(f).build(input(ReportKind.MANUAL, null, listOf(log)))
        assertEquals("2026-10-28_13-05-09_android_35.0.1-nourax.1_2.zip", second.name)
        assertTrue(first.exists() && second.exists())
    }

    @Test
    fun theReportIsAtMostFiveMegabytesWithTwentyMegabytesOfLogs() {
        // About 20 MB of text that does not compress well: the oldest lines must go, the newest must stay.
        val random = Random(7)
        val lines = ArrayList<String>(200_000)
        val builder = StringBuilder()
        for (i in 0 until 180_000) {
            builder.setLength(0)
            builder.append("2026-10-28 12:00:00.").append(String.format("%03d", i % 1000)).append(" D/Tag#").append(i).append(": ")
            repeat(12) {
                repeat(5 + random.nextInt(5)) { builder.append(('a'.code + random.nextInt(26)).toChar()) }
                builder.append(' ')
            }
            lines += builder.toString()
        }
        lines += "NEWEST LINE"
        assertTrue("test data is about 20 MB", lines.sumOf { it.length + 1L } > 18L * 1024 * 1024)
        val json = ReportJson.build(ReportKind.MANUAL, meta, "")
        val bytes = ReportZip.assemble(json, null, lines)
        assertTrue("zip is ${bytes.size} bytes", bytes.size <= ReportZip.MAX_BYTES)
        val log = unzip(bytes).getValue("logs/log.txt")
        assertNotNull(log)
        assertTrue("the newest line stays", log.endsWith("NEWEST LINE\n"))
        assertFalse("the oldest line goes", log.contains("D/Tag#0: "))
    }

    @Test
    fun aHugeCrashTextIsCutFromTheEndWhenThereAreNoLogsLeft() {
        val random = Random(9)
        val crash = buildString { repeat(9_000_000) { append(('a'.code + random.nextInt(26)).toChar()) } }
        val bytes = ReportZip.assemble("{}", crash, emptyList(), maxBytes = 1_000_000)
        assertTrue(bytes.size <= 1_000_000)
        val text = unzip(bytes).getValue("crash.txt")
        assertTrue(text.isNotEmpty() && crash.startsWith(text))
    }
}
