/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup before the first sync (plan 4.6) decides, with a fake phone and a fake server, when the first sync may start: only when
 * both backups have a new file on the server. Offline, a failed job and a timeout all say no.
 */
class BackupFlowTest {
    private class FakeSteps(
        var contacts: Boolean = true,
        var calendars: Boolean = true
    ) : BackupSteps {
        override var lastStatus = 0
        val jobs = ArrayList<String>()
        var listingStatus = 0
        var contactsJobOk = true
        var calendarJobOk = true

        /** Reading the phone throws this (a revoked permission, a provider that died). */
        var readFails: RuntimeException? = null

        /** After this many listings of a folder, a new file is there (never, when negative). */
        var newFileAfter = 1
        private val listings = HashMap<Boolean, Int>()

        override fun phoneHasContacts(): Boolean {
            readFails?.let { throw it }
            return contacts
        }

        override fun phoneHasCalendars(): Boolean {
            readFails?.let { throw it }
            return calendars
        }

        override fun names(contacts: Boolean): Set<String>? {
            if (listingStatus != 0) {
                lastStatus = listingStatus
                return null
            }
            val n = (listings[contacts] ?: 0) + 1
            listings[contacts] = n
            val old = setOf("/backup/old.vcf")
            return if (newFileAfter >= 0 && n > newFileAfter) old + "/backup/new-${if (contacts) "c" else "k"}.vcf" else old
        }

        override suspend fun runJob(contacts: Boolean): Boolean {
            jobs += if (contacts) "contacts" else "calendar"
            return if (contacts) contactsJobOk else calendarJobOk
        }
    }

    private fun run(steps: FakeSteps, timeoutMs: Long = 2000) = runBlocking { BackupFlow(steps, timeoutMs, pollMs = 5).run() }

    private fun failure(result: BackupGate.Result): String = (result as BackupGate.Result.Failed).reason

    @Test
    fun withNothingToSaveTheFirstSyncMayStartAtOnce() {
        val steps = FakeSteps(contacts = false, calendars = false)
        assertTrue(run(steps) is BackupGate.Result.Done)
        assertTrue(steps.jobs.isEmpty())
    }

    @Test
    fun aPhoneThatCanNotBeReadIsNotNothingToSaveTheBackupFailsAndNoJobRuns() {
        val steps = FakeSteps().also { it.readFails = SecurityException("no permission") }
        assertEquals("phone_unreadable", failure(run(steps)))
        assertTrue("no backup job was started", steps.jobs.isEmpty())
    }

    @Test
    fun whenBothBackupsHaveANewFileTheFirstSyncMayStart() {
        val steps = FakeSteps()
        assertTrue(run(steps) is BackupGate.Result.Done)
        assertEquals(listOf("contacts", "calendar"), steps.jobs)
    }

    @Test
    fun aServerThatCanNotBeAskedStopsTheFlowBeforeAnyJobRuns() {
        val steps = FakeSteps().also { it.listingStatus = 503 }
        val result = run(steps)
        assertEquals("offline_503", failure(result))
        assertTrue("no backup job was started", steps.jobs.isEmpty())
    }

    @Test
    fun aContactsJobThatFailedStopsTheFlow() {
        val steps = FakeSteps().also { it.contactsJobOk = false }
        assertEquals("contacts_job", failure(run(steps)))
        assertEquals("the calendar job was not run after it", listOf("contacts"), steps.jobs)
    }

    @Test
    fun aCalendarJobThatFailedStopsTheFlow() {
        val steps = FakeSteps().also { it.calendarJobOk = false }
        assertEquals("calendar_job", failure(run(steps)))
    }

    @Test
    fun jobsThatEndWithoutANewFileOnTheServerTimeOutAndTheFirstSyncDoesNotStart() {
        val steps = FakeSteps().also { it.newFileAfter = -1 }
        assertEquals("upload", failure(run(steps, timeoutMs = 80)))
    }

    @Test
    fun oneBackupWithoutANewFileIsNotEnough() {
        val steps = FakeSteps()
        // contacts get their new file, calendars never do
        val onlyContacts = object : BackupSteps by steps {
            override fun names(contacts: Boolean): Set<String>? = if (contacts) steps.names(true) else setOf("/backup/old.vcf")
        }
        val result = runBlocking { BackupFlow(onlyContacts, 80, pollMs = 5).run() }
        assertEquals("upload", failure(result))
    }

    @Test
    fun aFileThatShowsUpOnlyLaterStillCounts() {
        val steps = FakeSteps().also { it.newFileAfter = 4 }
        assertTrue(run(steps) is BackupGate.Result.Done)
    }
}
