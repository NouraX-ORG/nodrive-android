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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val files by lazy { ReportFiles(File(temp.root, "nodrive-reports")).also { it.ensure() } }
    private var autoSend = false
    private var asked: String? = null
    private var signedIn = true
    private var makeWorks = true
    private val made = mutableListOf<String>()
    private var uploads = 0

    private val flow by lazy {
        ReportFlow(
            files = files,
            autoSend = { autoSend },
            askedFor = { asked },
            rememberAsked = { asked = it },
            makeReport = {
                made += "${it.kind.wire}:${it.name}"
                if (makeWorks) File(files.outbox, "r-${made.size}.zip").writeText("zip")
                makeWorks
            },
            startUpload = { uploads++ },
            signedIn = { signedIn }
        )
    }

    private fun crash(name: String = "crash-100.txt") = File(files.pending, name).also { it.writeText("boom") }

    @Test
    fun withTheSwitchOnACrashIsSentAtStartWithoutAQuestion() {
        autoSend = true
        crash()
        flow.atStart()
        assertEquals(listOf("crash:crash-100.txt"), made)
        assertEquals(1, uploads)
        assertTrue("the raw file is consumed", PendingStore(files).all().isEmpty())
        assertNull("no question", flow.atMainScreen(flushOutbox = false))
    }

    @Test
    fun withTheSwitchOffNothingIsSentAtStartAndTheUserIsAskedOnce() {
        crash()
        flow.atStart()
        assertTrue(made.isEmpty())
        assertEquals(0, uploads)

        val question = flow.atMainScreen(flushOutbox = false)
        assertNotNull(question)
        assertEquals("crash-100.txt", question?.name)
        assertNull("asked once", flow.atMainScreen(flushOutbox = false))
        assertNull("still once on the next screen", flow.atMainScreen(flushOutbox = false))
        assertTrue("nothing was sent", made.isEmpty() && uploads == 0)
    }

    @Test
    fun aQuestionThatCouldNotBeShownIsAskedAtTheNextChance() {
        crash()
        assertNull(flow.atMainScreen(flushOutbox = false, mayAsk = false))
        assertNull("not counted as asked", asked)
        assertNotNull(flow.atMainScreen(flushOutbox = false, mayAsk = true))
    }

    @Test
    fun sendAnsweredYesSendsThisOneReportAndLeavesTheSwitchOff() {
        crash()
        flow.atMainScreen(flushOutbox = false)
        flow.send()
        assertEquals(1, made.size)
        assertEquals(1, uploads)
        assertFalse("the switch stays off", autoSend)
        assertTrue(PendingStore(files).all().isEmpty())
    }

    @Test
    fun notNowSendsNothingAndRemovesTheRawFile() {
        crash()
        flow.atMainScreen(flushOutbox = false)
        flow.notNow()
        assertTrue(made.isEmpty())
        assertEquals(0, uploads)
        assertTrue(PendingStore(files).all().isEmpty())
        assertNull(flow.atMainScreen(flushOutbox = false))
    }

    @Test
    fun aNewProblemIsAskedAboutEvenAfterAnEarlierQuestion() {
        crash("crash-100.txt")
        flow.atMainScreen(flushOutbox = false)
        flow.notNow()
        crash("crash-200.txt")
        assertEquals("crash-200.txt", flow.atMainScreen(flushOutbox = false)?.name)
    }

    @Test
    fun withoutAnAccountTheReportWaitsInTheOutboxAndNothingIsStarted() {
        autoSend = true
        signedIn = false
        crash()
        flow.atStart()
        assertEquals(1, made.size)
        assertEquals("no upload without an account", 0, uploads)
        assertEquals(1, Outbox(files).list().size)

        signedIn = true
        flow.atMainScreen(flushOutbox = true)
        assertEquals("sent after the sign-in", 1, uploads)
    }

    @Test
    fun theOutboxIsFlushedOnlyWhenAskedTo() {
        File(files.outbox, "a.zip").writeText("zip")
        flow.atMainScreen(flushOutbox = false)
        assertEquals(0, uploads)
        flow.atMainScreen(flushOutbox = true)
        assertEquals(1, uploads)
    }

    @Test
    fun aReportThatCouldNotBeMadeKeepsTheRawFile() {
        autoSend = true
        makeWorks = false
        crash()
        flow.atStart()
        assertEquals(1, PendingStore(files).all().size)
        assertEquals(0, uploads)
    }

    @Test
    fun theOutboxKeepsTheNewestFiveWhenNothingCanBeSent() {
        (1..7).forEach { File(files.outbox, "2026-10-28_13-05-0${it}_android_1.zip").writeText("z") }
        Outbox(files).trim()
        assertEquals(5, Outbox(files).list().size)
    }
}
