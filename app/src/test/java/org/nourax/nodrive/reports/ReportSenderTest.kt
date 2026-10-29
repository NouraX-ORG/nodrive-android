/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportSenderTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A server in memory. */
    private class FakeServer : ReportServer {
        val folders = mutableSetOf<String>()
        val files = mutableMapOf<String, String>()
        var shared = false
        var supportUserExists = false
        var uploadStatus = UploadStatus.DONE
        var networkDown = false
        var createCalls = 0
        var checkShareCalls = 0
        var shareCalls = 0

        override fun kindOf(path: String): RemoteKind {
            if (networkDown) throw ReportNetworkException("down")
            return when (path) {
                in folders -> RemoteKind.FOLDER
                in files -> RemoteKind.FILE
                else -> RemoteKind.MISSING
            }
        }

        override fun createFolder(path: String): Boolean {
            createCalls++
            folders += path
            return true
        }

        override fun upload(local: File, remote: String): UploadStatus {
            if (uploadStatus == UploadStatus.DONE) files[remote] = local.readText()
            return uploadStatus
        }

        override fun hasSupportShare(folder: String): Boolean {
            checkShareCalls++
            if (networkDown) throw ReportNetworkException("down")
            return shared
        }

        override fun shareWithSupport(folder: String): Boolean {
            shareCalls++
            if (!supportUserExists) return false
            shared = true
            return true
        }
    }

    private fun zip(name: String, text: String = name) = File(temp.root, name).also { it.writeText(text) }

    private val deleted = mutableListOf<String>()

    private fun send(server: FakeServer, vararg zips: File) = ReportSender(server).sendAll(zips.toList()) { deleted += it.name }

    @Test
    fun theFolderIsMadeWhenMissingAndTheReportArrives() {
        val server = FakeServer().apply { supportUserExists = true }
        val run = send(server, zip("2026-10-28_13-05-09_android_1.zip", "payload"))
        assertEquals(1, server.createCalls)
        assertEquals("payload", server.files["/Problem reports/2026-10-28_13-05-09_android_1.zip"])
        assertEquals(1, run.sent)
        assertTrue(run.sharedWithSupport)
        assertEquals(listOf("2026-10-28_13-05-09_android_1.zip"), deleted)
    }

    @Test
    fun anExistingFolderIsNotMadeAgain() {
        val server = FakeServer().apply { folders += "/Problem reports" }
        send(server, zip("a.zip"))
        assertEquals(0, server.createCalls)
    }

    @Test
    fun aTakenNameGetsASuffixAndNothingIsOverwritten() {
        val server = FakeServer().apply {
            folders += "/Problem reports"
            files["/Problem reports/a.zip"] = "OLD"
            files["/Problem reports/a_2.zip"] = "OLD2"
        }
        send(server, zip("a.zip", "NEW"))
        assertEquals("OLD", server.files["/Problem reports/a.zip"])
        assertEquals("OLD2", server.files["/Problem reports/a_2.zip"])
        assertEquals("NEW", server.files["/Problem reports/a_3.zip"])
    }

    @Test
    fun whenEveryNameIsTakenTheReportIsKeptAndNothingIsOverwritten() {
        val server = FakeServer().apply {
            folders += "/Problem reports"
            files["/Problem reports/a.zip"] = "OLD"
            (2..99).forEach { files["/Problem reports/a_$it.zip"] = "OLD$it" }
        }
        val before = server.files.toMap()
        val run = send(server, zip("a.zip", "NEW"))
        assertEquals(0, run.sent)
        assertEquals(1, run.kept)
        assertFalse(run.retry)
        assertEquals("nothing was written", before, server.files)
        assertTrue("the zip stays on the phone", deleted.isEmpty())
    }

    @Test
    fun aFileCalledProblemReportsKeepsEveryReport() {
        val server = FakeServer().apply { files["/Problem reports"] = "i am a file" }
        val run = send(server, zip("a.zip"), zip("b.zip"))
        assertEquals(0, run.sent)
        assertEquals(2, run.kept)
        assertFalse(run.retry)
        assertTrue(deleted.isEmpty())
        assertEquals(0, server.createCalls)
    }

    @Test
    fun theShareIsMadeOnceWhenItIsMissing() {
        val server = FakeServer().apply { supportUserExists = true }
        val run = send(server, zip("a.zip"), zip("b.zip"), zip("c.zip"))
        assertEquals(3, run.sent)
        assertEquals("one check and one share for the whole run", 1, server.shareCalls)
        assertEquals(1, server.checkShareCalls)
        assertTrue(run.sharedWithSupport)
    }

    @Test
    fun noShareCallWhenTheShareAlreadyExists() {
        val server = FakeServer().apply { folders += "/Problem reports"; shared = true }
        val run = send(server, zip("a.zip"))
        assertEquals(0, server.shareCalls)
        assertTrue(run.sharedWithSupport)
    }

    @Test
    fun aFailedShareIsNotAnErrorAndIsNotRepeatedInTheSameRun() {
        val server = FakeServer() // the support user does not exist yet
        val run = send(server, zip("a.zip"), zip("b.zip"))
        assertEquals("the reports arrived", 2, run.sent)
        assertFalse("not shared", run.sharedWithSupport)
        assertFalse("no retry because of the share", run.retry)
        assertEquals("one attempt only", 1, server.shareCalls)
        assertEquals(2, deleted.size)
    }

    @Test
    fun anUploadThatFailsKeepsTheZipAndAsksForARetry() {
        val server = FakeServer().apply { uploadStatus = UploadStatus.RETRY }
        val run = send(server, zip("a.zip"), zip("b.zip"))
        assertEquals(0, run.sent)
        assertTrue(run.retry)
        assertTrue(deleted.isEmpty())
        assertEquals("no share is tried when nothing arrived", 0, server.shareCalls)
    }

    @Test
    fun aRefusedUploadIsKeptWithoutARetry() {
        val server = FakeServer().apply { uploadStatus = UploadStatus.KEEP }
        val run = send(server, zip("a.zip"))
        assertEquals(1, run.kept)
        assertFalse(run.retry)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun noNetworkMeansARetryAndNothingIsDeleted() {
        val server = FakeServer().apply { networkDown = true }
        val run = send(server, zip("a.zip"))
        assertTrue(run.retry)
        assertEquals(0, run.sent)
        assertTrue(deleted.isEmpty())
    }
}
