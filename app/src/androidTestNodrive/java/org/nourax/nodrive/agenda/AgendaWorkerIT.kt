/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.android.AgendaRefreshWorker
import org.nourax.nodrive.agenda.store.Sources
import org.nourax.nodrive.agenda.store.StoredSource
import java.time.Instant

/**
 * The background refresh itself, run by the real WorkManager (design 9). When the account is gone, the worker deletes the
 * account's saved rows, cancels its periodic work and succeeds. When the account is there, it refreshes the saved copy.
 */
class AgendaWorkerIT {
    private val support = AgendaTestSupport()
    private val context get() = support.context

    @Before
    fun setUp() {
        support.reset()
    }

    @After
    fun tearDown() {
        support.reset()
    }

    private fun runOnce(account: String): WorkInfo {
        val request = OneTimeWorkRequestBuilder<AgendaRefreshWorker>()
            .setInputData(Data.Builder().putString(AgendaRefreshWorker.KEY_ACCOUNT, account).build())
            .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request)
        val deadline = System.currentTimeMillis() + WORK_MILLIS
        var info = checkNotNull(manager.getWorkInfoById(request.id).get())
        while (!info.state.isFinished && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
            info = checkNotNull(manager.getWorkInfoById(request.id).get())
        }
        return info
    }

    @Test
    fun anAccountThatIsGoneHasItsRowsForgottenAndItsWorkCancelled() {
        val gone = "ghost@https://gone.example.test"
        val store = AgendaAccess.store(context)
        store.write(gone, Sources.GANTT, StoredSource("{}", "", 1, "ok"))
        store.saveSettings(gone, "{}")
        AgendaRefreshWorker.schedule(context, gone)

        val info = runOnce(gone)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertNull(store.read(gone, Sources.GANTT))
        assertNull(store.loadSettings(gone))
        val periodic = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AgendaRefreshWorker.uniqueName(gone)).get()
        assertTrue(periodic.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

    @Test
    fun anAccountThatIsThereHasItsSavedCopyRefreshed() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.useClock(Instant.now())
        val account = support.addServerAccount("ali")
        val store = AgendaAccess.store(context)
        assertNull(store.read(account.name, Sources.CALENDAR))

        val info = runOnce(account.name)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertNotNull(store.read(account.name, Sources.CALENDAR))
        assertNotNull(store.read(account.name, Sources.GANTT))
        assertNotNull(store.read(account.name, Sources.DECK))
    }

    private companion object {
        const val WORK_MILLIS = 90_000L
        const val POLL_MILLIS = 500L
    }
}
