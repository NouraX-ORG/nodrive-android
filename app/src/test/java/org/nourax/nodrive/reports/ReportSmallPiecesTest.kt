/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The name of a report, the outbox, the pending files, the file guard and the crash recorder. */
class ReportSmallPiecesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun files() = ReportFiles(File(temp.root, "nodrive-reports")).also { it.ensure() }

    // ---- ReportName

    @Test
    fun theNameIsUtcTimeThenPlatformThenVersion() {
        // 2026-10-28 13:05:09 UTC
        assertEquals("2026-10-28_13-05-09_android_35.0.1-nourax.1", ReportName.base(1_793_192_709_000L, "35.0.1-nourax.1"))
        assertEquals("2026-10-28_13-05-09_android_1.2.3_x_y", ReportName.base(1_793_192_709_000L, "1.2.3/x y"))
        assertEquals("a.zip", ReportName.fileName("a", 1))
        assertEquals("a_2.zip", ReportName.fileName("a", 2))
    }

    @Test
    fun aTakenNameGetsTheNextSuffix() {
        val taken = setOf("r.zip", "r_2.zip", "r_3.zip")
        assertEquals("r_4.zip", ReportName.firstFree("r") { it in taken })
        assertEquals("r.zip", ReportName.firstFree("r") { false })
    }

    @Test
    fun whenAllNinetyNineNamesAreTakenThereIsNoName() {
        assertEquals("r_99.zip", ReportName.firstFree("r") { it != "r_99.zip" })
        assertNull(ReportName.firstFree("r") { true })
    }

    // ---- UploadStamps

    @Test
    fun bothUploadTimesAreUnixSeconds() {
        // 2026-10-28 13:05:09.987 UTC
        val stamps = UploadStamps.at(1_793_192_709_987L)
        assertEquals(1_793_192_709L, stamps.modifiedSeconds)
        assertEquals(1_793_192_709L, stamps.createdSeconds)
    }

    // ---- FlushGate

    @Test
    fun theOutboxFlushIsUsedUpOnlyWhileSignedIn() {
        val gate = FlushGate()
        assertFalse("not signed in: not used up", gate.take(signedIn = false))
        assertFalse(gate.take(signedIn = false))
        assertTrue("first screen after the sign-in", gate.take(signedIn = true))
        assertFalse("once per process", gate.take(signedIn = true))
    }

    // ---- shown ids

    @Test
    fun theShownIdsKeepTheNewestInOrder() {
        var ids = ""
        (1..25).forEach { ids = ReportSettings.pushShown(ids, "id$it", 20) }
        val list = ids.split(',')
        assertEquals((6..25).map { "id$it" }, list)
        assertEquals("a repeated id moves to the end", (7..25).map { "id$it" } + "id6", ReportSettings.pushShown(ids, "id6", 20).split(','))
    }

    // ---- Outbox

    @Test
    fun theOutboxKeepsTheNewestFiveOnly() {
        val f = files()
        (1..8).forEach { File(f.outbox, "2026-10-28_13-05-0${it}_android_1.zip").writeText("zip $it") }
        File(f.outbox, "notes.txt").writeText("not a report")
        val outbox = Outbox(f)
        outbox.trim()
        val left = outbox.list().map { it.name }
        assertEquals((4..8).map { "2026-10-28_13-05-0${it}_android_1.zip" }, left)
        assertTrue("only zips are touched", File(f.outbox, "notes.txt").exists())
    }

    // ---- ReportFiles guard

    @Test
    fun deleteOwnRefusesAnythingOutsideTheReportFolder() {
        val f = files()
        val outside = temp.newFile("phone-photo.jpg")
        val inside = File(f.outbox, "a.zip").also { it.writeText("x") }
        val sneaky = File(f.outbox, "../../phone-photo.jpg")
        assertFalse(f.deleteOwn(outside))
        assertFalse(f.deleteOwn(sneaky))
        assertTrue(outside.exists())
        assertTrue(f.deleteOwn(inside))
        assertFalse(inside.exists())
        assertFalse("the root itself is not inside itself", f.deleteOwn(f.root))
    }

    // ---- Pending files

    @Test
    fun pendingFilesKnowTheirKindAndAreConsumedTogether() {
        val f = files()
        val store = PendingStore(f)
        assertNull(store.newest())
        assertFalse(store.hasCrash())
        File(f.pending, "exit-100.txt").writeText("exit")
        File(f.pending, "crash-200.txt").writeText("crash")
        assertTrue(store.hasCrash())
        assertEquals(ReportKind.CRASH, store.all().first { it.name == "crash-200.txt" }.kind)
        assertEquals(ReportKind.UNCLEAN_EXIT, store.all().first { it.name == "exit-100.txt" }.kind)
        store.consume()
        assertTrue(store.all().isEmpty())
    }

    // ---- CrashRecorder

    @Test
    fun theRecorderWritesTheTraceThenCallsThePreviousHandler() {
        val f = files()
        var seen: Throwable? = null
        val recorder = CrashRecorder(f.pending, Thread.UncaughtExceptionHandler { _, e -> seen = e }, { 1234L })
        val boom = IllegalStateException("boom", RuntimeException("root cause"))
        recorder.uncaughtException(Thread.currentThread(), boom)
        assertEquals(boom, seen)
        val text = File(f.pending, "crash-1234.txt").readText()
        assertTrue(text.startsWith("Thread: "))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: root cause"))
    }

    @Test
    fun theRecorderNeverHidesTheCrashEvenWhenItCannotWrite() {
        val blocker = temp.newFile("not-a-folder")
        var called = false
        val recorder = CrashRecorder(File(blocker, "pending"), Thread.UncaughtExceptionHandler { _, _ -> called = true })
        recorder.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue("the previous handler still ran", called)
    }

    @Test
    fun theRecorderKeepsAtMostTenCauses() {
        var error: Throwable = RuntimeException("cause 0")
        for (i in 1..15) error = RuntimeException("cause $i", error)
        val text = CrashRecorder.format(Thread.currentThread(), error)
        assertEquals(10, Regex("RuntimeException: cause").findAll(text).count())
    }
}
