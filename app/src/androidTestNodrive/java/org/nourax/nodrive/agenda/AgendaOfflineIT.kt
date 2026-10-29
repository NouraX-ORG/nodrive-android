/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.Instant

/**
 * Offline (design 9): the last data stays readable, marked with its time. tools/ci/agenda-device.sh runs the methods one by one:
 * a1 reads the day from the throwaway server and leaves the saved copy, then the script turns mobile data off, a2 opens NoAgenda
 * without a network, and a3 cleans up after the script turned data on again.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AgendaOfflineIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()

    private fun see(text: String) {
        compose.waitUntil(LOAD_MILLIS) {
            runCatching { compose.onNodeWithTag("agenda_list").performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
    }

    @Test
    fun a1ReadTheDayAndKeepTheSavedCopy() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
        support.useClock(Instant.now())
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            compose.waitUntil(LOAD_MILLIS) {
                compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasText("Dentist")).fetchSemanticsNodes().isNotEmpty()
            }
            runCatching {
                compose.waitUntil(8_000L) { compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() }
            }
            if (compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("agenda_personal_save").performClick()
            }
            see("Dentist")
            see("Client call")
            see("Write homepage copy")
            // the refresh that follows the first read has ended before the data goes off
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText("Updated", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            support.backup.sleepForAnimation()
        }
    }

    @Test
    fun a2WithoutANetworkTheSavedDayIsShownWithItsTime() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset(keepSavedCopy = true)
        support.useClock(Instant.now())
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            compose.waitUntil(OFFLINE_MILLIS) {
                compose.onAllNodes(hasText("You're offline. Showing what was saved at", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            see("Dentist")
            see("Client call")
            compose.waitForIdle()
            support.assertNoServerSoftwareName(compose)
            support.backup.sleepForAnimation()
            support.screenshot("offline-live")
        }
    }

    @Test
    fun a3CleanUp() {
        support.reset()
    }

    private companion object {
        const val LOAD_MILLIS = 90_000L
        const val OFFLINE_MILLIS = 180_000L
    }
}
