/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.Instant

/**
 * The real app, signed in with an app password, reads the throwaway server (the seed of tools/ci/agenda-seed.py) and shows what
 * each person sees: Ali's day with his agents, the manager's team problems, and nothing of another organisation's agents for Olaf.
 * "Open in ..." opens the page of the account's own server, signed in.
 */
class AgendaLiveIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
        // The seeded events are in UTC; the screen runs in UTC too, so "today" is the same day.
        support.useClock(Instant.now())
    }

    @After
    fun tearDown() {
        support.reset()
    }

    private fun see(text: String, list: String = "agenda_list") {
        compose.waitUntil(LOAD_MILLIS) {
            runCatching { compose.onNodeWithTag(list).performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The first time, the person is asked which calendars are personal; the test answers the way the owner would. */
    private fun answerTheQuestion(save: Boolean) {
        compose.waitUntil(LOAD_MILLIS) {
            compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Dentist")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("agenda_empty").fetchSemanticsNodes().isNotEmpty()
        }
        // The dialog opens a moment after the rows are there (it is a window of its own).
        runCatching {
            compose.waitUntil(DIALOG_MILLIS) { compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(if (save) "agenda_personal_save" else "agenda_personal_skip").performClick()
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        support.assertNoServerSoftwareName(compose)
        support.backup.sleepForAnimation()
        support.screenshot(name)
    }

    private fun openIn(row: String, label: String, pathPart: String) {
        see(row)
        compose.onNodeWithText(row).performClick()
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("agenda_open_in").performClick()
        val web = support.backup.waitFor("the in-app page for $label", PAGE_MILLIS) { support.workspace.workspaceActivity() }
        val webView = support.workspace.webViewOf(web)
        val path = support.backup.waitFor("the page of $row", PAGE_MILLIS) {
            val current = support.workspace.onMainResult { Uri.parse(webView.url ?: "").path.orEmpty() }
            current.takeIf { it.contains(pathPart) }
        }
        assertTrue(path, path.contains(pathPart))
        support.backup.onMain { web.finish() }
        // back on NoAgenda
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("agenda_list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aliSeesHisDayWithHisAgentsAndCanOpenEveryHome() {
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion(save = true)
            // events of the two calendars, NoGantt tasks with their agent or Self, a card with its agent label
            for (text in listOf("Dentist", "Client call")) see(text)
            see("Write homepage copy")
            see("Prepare client demo")
            see("Pay invoice")
            waitForText("Writer-Bot")
            waitForText("Self")
            // tomorrow: the card with the agent label
            compose.onNodeWithTag("agenda_next").performClick()
            see("Call supplier")
            compose.onNodeWithTag("agenda_today").performClick()
            see("Dentist")
            // integrity: two agents for one person; an agent that sits on a task without him
            see("Two agents for Ali: choose one")
            see("without you")
            compose.onNodeWithTag("agenda_list").performScrollToNode(hasText("Dentist"))
            shot("live-day")

            openIn("Client call", "Open in NoCalendar", "/apps/calendar/")
            openIn("Late bill", "Open in NoTasks", "/apps/tasks/")
            openIn("Prepare client demo", "Open in NoGantt", "/apps/nc_gantt")
            openIn("Pay invoice", "Open in NoBoard", "/apps/deck/")
            support.assertNoServerSoftwareName(compose)
        }
    }

    @Test
    fun theManagerSeesTheProblemsInWhatSheRuns() {
        support.addServerAccount("mara")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion(save = false)
            see("Banner design")
            see("2 days late")
            see("Design-Bot")
            see("Blocked by Banner design")
            see("Not assigned to a person")
            see("Agent with no person")
            seeAndShot("live-my-team", "Banner design")
        }
    }

    private fun seeAndShot(name: String, text: String) {
        see(text)
        shot(name)
    }

    @Test
    fun saraWithoutAReadableAgentListSeesEverythingAsSelf() {
        support.addServerAccount("sara")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion(save = false)
            compose.onNodeWithTag("agenda_sources").performClick()
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("agenda_sources_screen").fetchSemanticsNodes().isNotEmpty() }
            see("The agent list in your NoDrive can't be read.", "agenda_sources_list")
            shot("live-sources-invalid-registry")
        }
    }

    @Test
    fun olafNeverSeesAnAgentOfAnotherOrganisation() {
        support.addServerAccount("olaf")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion(save = false)
            waitForText("You're not linked to a person in NoGantt yet.")
            compose.waitForIdle()
            for (text in support.allTexts(compose)) {
                assertFalse("\"$text\" names an agent of another organisation", text.contains("Writer-Bot") || text.contains("Design-Bot"))
            }
            shot("live-olaf")
        }
    }

    private companion object {
        const val LOAD_MILLIS = 90_000L
        const val DIALOG_MILLIS = 8_000L
        const val PAGE_MILLIS = 120_000L
    }
}
