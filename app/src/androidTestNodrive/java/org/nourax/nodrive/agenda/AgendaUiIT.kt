/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.ui.AgendaActivity

/**
 * The NoAgenda screens on canned data (no server): the Day view, Week, Month, My team, Sources, the one-time question, the detail
 * sheet, the empty and error states. Light and dark (the script sets the mode). Screenshots go to the app's files folder.
 */
class AgendaUiIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()

    @Before
    fun setUp() {
        support.reset()
        support.addLocalAccount()
        support.useClock(CannedAgenda.NOW)
    }

    @After
    fun tearDown() {
        support.reset()
    }

    private fun show(snapshot: AgendaSnapshot, then: (ActivityScenario<AgendaActivity>) -> Unit) {
        AgendaAccess.factory = { _, _ -> AgendaRepository(CannedEngine(snapshot)) }
        ActivityScenario.launch(AgendaActivity::class.java).use { scenario ->
            then(scenario)
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Scrolls the list to the row with this text (the rows below the screen are not composed until then). */
    private fun see(text: String, list: String = "agenda_list") {
        compose.waitUntil(WAIT_MILLIS) {
            runCatching { compose.onNodeWithTag(list).performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
    }

    private fun seeTag(tag: String, list: String = "agenda_list") {
        compose.waitUntil(WAIT_MILLIS) {
            runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The screenshot of what is shown, after the check that no text names the server software. */
    private fun shot(name: String) {
        compose.waitForIdle()
        support.assertNoServerSoftwareName(compose)
        support.backup.sleepForAnimation()
        support.screenshot(name)
    }

    @Test
    fun theDayShowsTheThreeKindsOfWorkAndTheFilters() {
        show(CannedAgenda.day()) {
            waitForText("Dentist")
            for (text in listOf("Client call", "Writer-Bot review", "Late bill", "Pay invoice", "Prepare client demo")) see(text)
            // the kinds of work: by myself (Self) and by my agent
            waitForText("Self")
            see("Write homepage copy")
            // sections
            for (tag in listOf("agenda_group_late", "agenda_group_due_today", "agenda_group_in_progress", "agenda_group_agent_problems")) seeTag(tag)
            see("Writer-Bot is on \"Orphan writing\" without you")
            compose.onNodeWithTag("agenda_list").performScrollToNode(hasText("Dentist"))
            compose.onNodeWithTag("agenda_chip_me").assertIsSelected()
            compose.onNodeWithTag("agenda_chip_agents").assertIsSelected()
            compose.onNodeWithTag("agenda_chip_team").assertIsSelected()
            compose.onNodeWithTag("agenda_category_all").assertIsSelected()
            compose.onNodeWithTag("agenda_tab_day").assertIsSelected()
            waitForText("Updated")
            shot("day")

            // All / Work / Personal
            compose.onNodeWithTag("agenda_category_work").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Dentist")).fetchSemanticsNodes().isEmpty() }
            waitForText("Client call")
            compose.onNodeWithTag("agenda_category_personal").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Client call")).fetchSemanticsNodes().isEmpty() }
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_category_all").performClick()
            waitForText("Client call")

            // the chips: My agents off hides the rows of my agent
            compose.onNodeWithTag("agenda_chip_agents").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Writer-Bot review")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("agenda_chip_agents").performClick()
            waitForText("Writer-Bot review")

            // "No date" is folded until it is tapped
            seeTag("agenda_group_no_date")
            assertEquals(0, compose.onAllNodes(hasText("Someday")).fetchSemanticsNodes().size)
            compose.onNodeWithTag("agenda_group_no_date").performClick()
            see("Someday")
        }
    }

    @Test
    fun theWeekAndTheMonthAreLists() {
        show(CannedAgenda.day()) {
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_tab_week").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag("agenda_day_header").fetchSemanticsNodes().size >= 2 }
            compose.onNodeWithTag("agenda_tab_week").assertIsSelected()
            shot("week")
            compose.onNodeWithTag("agenda_tab_month").performClick()
            waitForTag("agenda_month_grid")
            waitForText("Dentist")
            shot("month")
            // a step to the next month and back to today
            compose.onNodeWithTag("agenda_next").performClick()
            compose.onNodeWithTag("agenda_today").performClick()
            waitForText("Dentist")
        }
    }

    @Test
    fun myTeamShowsOnlyProblemsAndTheSwitchShowsMore() {
        show(CannedAgenda.myTeam()) {
            see("Banner design")
            see("Design-Bot")
            see("2 days late")
            see("Blocked by Banner design")
            see("Sara has 1 task in a project not shared with Sara")
            seeTag("agenda_show_all_team")
            compose.onNodeWithTag("agenda_show_all_team").assertIsDisplayed()
            shot("my-team")
            // the chip turns the whole section off
            compose.onNodeWithTag("agenda_chip_team").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Banner design")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun theSourcesScreenListsEverySourceWithItsSetting() {
        show(CannedAgenda.day()) {
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_sources").performClick()
            waitForTag("agenda_sources_screen")
            for (text in listOf("Projects (NoGantt)", "Boards (NoBoard)", "Working hours", "Agents")) see(text, "agenda_sources_list")
            see("Calendars and task lists", "agenda_sources_list")
            waitForText("Local holidays")
            waitForText("Subscription")
            waitForText("Shared by Mara")
            waitForText("Hidden automatically")
            waitForText("Board calendar: NoAgenda reads the board directly")
            shot("sources")
            see("Writer-Bot (Active)", "agenda_sources_list")
            waitForText("Research-Bot (Paused)")
            compose.onNodeWithTag("agenda_sources_back").performClick()
            waitForText("Dentist")
        }
    }

    @Test
    fun theListOfAgentsSaysWhenThereIsNone() {
        show(CannedAgenda.registryMissing()) {
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_sources").performClick()
            waitForTag("agenda_sources_screen")
            see("No agent list found", "agenda_sources_list")
            waitForText("Everything shows as Self.")
            shot("registry-missing")
        }
    }

    @Test
    fun theQuestionAsksWhichCalendarsArePersonalOnce() {
        show(CannedAgenda.question()) {
            waitForText("Which of your calendars are personal?")
            waitForText("Personal events are only ever shown to you. The others count as Work.")
            waitForTag("agenda_personal_question")
            shot("personal-question")
            compose.onNodeWithTag("agenda_personal_save").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Which of your calendars are personal?")).fetchSemanticsNodes().isEmpty() }
            waitForText("Dentist")
        }
    }

    @Test
    fun skippingTheQuestionClosesIt() {
        show(CannedAgenda.question()) {
            waitForText("Which of your calendars are personal?")
            compose.onNodeWithTag("agenda_personal_skip").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Which of your calendars are personal?")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun aRowOpensItsDetailWithOnlyOpenIn() {
        show(CannedAgenda.day()) {
            waitForText("Client call")
            compose.onNodeWithText("Client call").performClick()
            waitForTag("agenda_detail")
            waitForText("Open in NoCalendar")
            waitForText("Who does it")
            waitForText("Self")
            shot("detail")
            compose.onNodeWithTag("agenda_detail").assertIsDisplayed()
            // nothing in the sheet changes anything in this step
            assertTrue(compose.onAllNodes(hasText("Delete")).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodes(hasText("Save")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun aTaskOpensInNoGantt() {
        show(CannedAgenda.day()) {
            see("Prepare client demo")
            compose.onNodeWithText("Prepare client demo").performClick()
            waitForText("Open in NoGantt")
            waitForText("10% done")
        }
    }

    @Test
    fun aCardOpensInNoBoard() {
        show(CannedAgenda.day()) {
            see("Pay invoice")
            compose.onNodeWithText("Pay invoice").performClick()
            waitForText("Open in NoBoard")
        }
    }

    @Test
    fun theEmptyStateForAPersonNotLinkedInNoGanttIsPlain() {
        show(CannedAgenda.notLinked()) {
            waitForText("You're not linked to a person in NoGantt yet. Ask your project manager to link you.")
            shot("empty-not-linked")
        }
    }

    @Test
    fun whenEverythingIsOfflineTheSavedCopyIsShownWithItsTime() {
        show(CannedAgenda.offline()) {
            waitForText("You're offline. Showing what was saved at")
            waitForText("Dentist")
            shot("offline")
        }
    }

    @Test
    fun oneSourceDownIsNamedAndTheOthersStayFresh() {
        show(CannedAgenda.ganttDown()) {
            waitForText("NoGantt can't be reached; showing what was saved at")
            waitForText("Dentist")
            shot("gantt-down")
        }
    }

    private companion object {
        const val WAIT_MILLIS = 20_000L
    }
}
