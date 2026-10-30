/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.ui.AgendaActivity

/**
 * The edit screens on canned data (no server): the Day view with a clash and an overbooked day, changing the agent of a Gantt task,
 * the agent that is not in the project, a new event, the edit conflict, the change that waits offline, the delete question, a
 * repeating event and a task of a viewer project. Light and dark (the script sets the mode). Screenshots go to the app's files folder.
 */
class AgendaEditUiIT {
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

    private fun show(engine: CannedEditEngine, then: (ActivityScenario<AgendaActivity>) -> Unit) {
        AgendaAccess.factory = { _, _ -> AgendaRepository(engine) }
        ActivityScenario.launch(AgendaActivity::class.java).use { scenario -> then(scenario) }
    }

    private fun engine(online: Boolean = true) = CannedEditEngine(CannedEdit.snapshot(), online)

    /** Scrolls the sheet to the control and taps it (the sheet is longer than the screen). */
    private fun tap(tag: String) {
        if (tag == "edit_save") runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGoneTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

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

    private fun shot(name: String) {
        compose.waitForIdle()
        support.assertNoServerSoftwareName(compose)
        support.backup.sleepForAnimation()
        support.screenshot(name)
    }

    private fun openRow(title: String) {
        see(title)
        compose.onNodeWithText(title).performClick()
        waitForTag("agenda_detail")
    }

    @Test
    fun theDayShowsAClashAnOverbookedDayAndTheConflictsFilter() {
        show(engine()) {
            waitForText("Dentist")
            // the dentist and the client call are at the same time
            waitForTag("agenda_workload")
            waitForText("12 h planned")
            see("Clash")
            compose.onNodeWithTag("agenda_list").performScrollToNode(hasText("Dentist"))
            shot("day-clash-overbooked")
            // the Conflicts filter keeps only the rows with a badge (a clash, late, blocked, a deadline on a day off)
            compose.onNodeWithTag("agenda_chip_conflicts").performClick()
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Pay invoice")).fetchSemanticsNodes().isEmpty() }
            see("Dentist")
            see("Client call")
            see("Late bill")
            compose.onNodeWithTag("agenda_chip_conflicts").performClick()
            see("Pay invoice")
        }
    }

    @Test
    fun theAgentOfATaskIsChangedPersonFirstThenAgent() {
        val engine = engine()
        show(engine) {
            openRow("Prepare client demo")
            tap("agenda_edit")
            waitForTag("agenda_editor")
            waitForTag("edit_agent")
            // Self first, then Writer-Bot; the paused agent is greyed with its reason
            compose.onNodeWithTag("edit_agent_SELF").assertIsSelected()
            waitForText("Done by you")
            waitForText("Paused")
            shot("edit-agent-gantt")
            tap("edit_agent_AG-0001")
            compose.onNodeWithTag("edit_agent_AG-0001").assertIsSelected()
            tap("edit_save")
            waitGoneTag("agenda_detail")
            assertEquals(listOf(CommandKind.GANTT_AGENT), engine.kinds())
            assertEquals("AG-0001", engine.submitted.single().change[EditKeys.AGENT])
            assertEquals("gantt:task:101", engine.submitted.single().itemKey)
            see("Prepare client demo")
            waitForText("Writer-Bot")
        }
    }

    @Test
    fun anAgentWhoseNameIsNotInTheProjectIsGreyedWithWhatToDo() {
        show(engine()) {
            openRow("Prepare client demo")
            tap("agenda_edit")
            waitForTag("edit_agent")
            waitForText("Ask your project manager to add Invoice-Bot to this project")
            shot("edit-agent-not-in-project")
        }
    }

    @Test
    fun aNewEventHasSelfPreselectedAndNeedsATitle() {
        val engine = engine()
        show(engine) {
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_fab").performClick()
            compose.onNodeWithTag("agenda_new_event").performClick()
            waitForTag("agenda_new_sheet")
            compose.onNodeWithTag("edit_agent_SELF").assertIsSelected()
            // no title yet: Save is not offered
            waitForText("Enter a title.")
            compose.onNodeWithTag("edit_title").performTextInput("Review with the team")
            tap("edit_agent_AG-0001")
            runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
            shot("new-event")
            tap("edit_save")
            waitGoneTag("agenda_new_sheet")
            val command: EditCommand = engine.submitted.single()
            assertEquals(CommandKind.CAL_CREATE, command.kind)
            assertEquals("Review with the team", command.change[EditKeys.SUMMARY])
            assertEquals("AG-0001", command.change[EditKeys.AGENT])
            assertEquals("agent:AG-0001", command.change[EditKeys.AGENT_TAG])
            assertTrue(command.loc("calendarHref").startsWith("/remote.php/dav/calendars/ali/"))
            see("Review with the team")
        }
    }

    @Test
    fun aNewTodoCanBeMadeWithSelf() {
        val engine = engine()
        show(engine) {
            waitForText("Dentist")
            compose.onNodeWithTag("agenda_fab").performClick()
            compose.onNodeWithTag("agenda_new_todo").performClick()
            waitForTag("agenda_new_sheet")
            compose.onNodeWithTag("edit_agent_SELF").assertIsSelected()
            compose.onNodeWithTag("edit_title").performTextInput("Call the bank")
            tap("edit_save")
            waitGoneTag("agenda_new_sheet")
            val command = engine.submitted.single()
            assertEquals(CommandKind.CAL_CREATE, command.kind)
            assertEquals("todo", command.change[EditKeys.NEW_KIND])
            assertEquals(null, command.change[EditKeys.AGENT])
        }
    }

    @Test
    fun anEditConflictIsKeptUntilThePersonDecides() {
        val engine = engine()
        engine.injectConflict(
            EditCommand(
                kind = CommandKind.CAL_PATCH, itemKey = "cal:work/call.ics", title = "Client call",
                home = org.nourax.nodrive.agenda.model.Home.CALENDAR, change = mapOf(EditKeys.SUMMARY to "Client call (mine)")
            )
        )
        show(engine) {
            waitForTag("agenda_conflict_card")
            waitForText("Not sent")
            waitForText("This changed somewhere else while you were editing. NoAgenda kept the other change.")
            waitForText("Apply my change again")
            waitForText("Keep the other change")
            shot("edit-conflict")
            compose.onNodeWithTag("agenda_conflict_apply").performClick()
            waitGoneTag("agenda_conflict_card")
            assertEquals(1, engine.resolved.size)
            assertTrue(engine.resolved.single().second)
            assertEquals(CommandKind.CAL_PATCH, engine.submitted.single().kind)
            assertTrue(engine.submitted.single().rebase)
        }
    }

    @Test
    fun keepingTheOtherChangeSendsNothing() {
        val engine = engine()
        engine.injectConflict(EditCommand(kind = CommandKind.CAL_PATCH, itemKey = "cal:work/call.ics", title = "Client call"))
        show(engine) {
            waitForTag("agenda_conflict_card")
            compose.onNodeWithTag("agenda_conflict_keep").performClick()
            waitGoneTag("agenda_conflict_card")
            assertTrue(engine.submitted.isEmpty())
            assertEquals(false, engine.resolved.single().second)
        }
    }

    @Test
    fun aChangeWithoutANetworkWaitsAndIsMarkedOnItsRow() {
        val engine = engine(online = false)
        show(engine) {
            openRow("Client call")
            tap("agenda_edit")
            waitForTag("edit_title")
            compose.onNodeWithTag("edit_title").performTextReplacement("Client call (moved)")
            tap("edit_save")
            waitGoneTag("agenda_detail")
            waitForText("Saved on this phone. It will be sent when you are back online.")
            waitForTag("agenda_pending_banner")
            waitForText("Waiting to send 1 change")
            see("Client call (moved)")
            see("Waiting to send")
            shot("pending-offline")
            assertEquals(CommandKind.CAL_PATCH, engine.submitted.single().kind)
        }
    }

    @Test
    fun deletingAnEventAsksFirstAndSaysWhereItGoes() {
        val engine = engine()
        show(engine) {
            openRow("Client call")
            tap("agenda_delete")
            waitForTag("agenda_delete_question")
            waitForText("Delete this event? It moves to the calendar's trash bin.")
            shot("delete-confirm")
            // nothing is sent until the person says yes
            assertTrue(engine.submitted.isEmpty())
            compose.onNodeWithTag("agenda_delete_confirm").performClick()
            waitGoneTag("agenda_delete_question")
            assertEquals(listOf(CommandKind.CAL_DELETE), engine.kinds())
        }
    }

    @Test
    fun aRepeatingEventAndAnInvitationAreNotChangedHere() {
        show(engine()) {
            openRow("Stand-up")
            waitForText("This repeats. Change it in NoCalendar.")
            assertEquals(0, compose.onAllNodesWithTag("agenda_edit").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithTag("agenda_delete").fetchSemanticsNodes().size)
            waitForText("Open in NoCalendar")
            shot("repeating-locked")
        }
    }

    @Test
    fun anInvitationIsAlwaysSelfAndOnlyTheOrganiserChangesIt() {
        show(engine()) {
            openRow("Planning meeting")
            waitForText("You were invited. Only the organiser can change this event.")
            assertEquals(0, compose.onAllNodesWithTag("agenda_edit").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aTaskOfAProjectThatIsOnlyViewedIsLocked() {
        show(engine()) {
            // the task of the viewer project is on today's list, in "In progress"
            see("Internal checklist")
            compose.onNodeWithText("Internal checklist").performClick()
            waitForTag("agenda_detail")
            waitForText("You can view this project only. Ask the project manager for editor rights.")
            assertEquals(0, compose.onAllNodesWithTag("agenda_edit").fetchSemanticsNodes().size)
            shot("viewer-locked")
        }
    }

    @Test
    fun tickingATodoDoneSendsOneCommandAndTheRowLeavesTheList() {
        val engine = engine()
        show(engine) {
            openRow("Late bill")
            tap("agenda_mark_done")
            waitGoneTag("agenda_detail")
            assertEquals(listOf(CommandKind.CAL_PATCH), engine.kinds())
            assertEquals("true", engine.submitted.single().change[EditKeys.DONE])
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText("Late bill")).fetchSemanticsNodes().isEmpty() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 20_000L
    }
}
