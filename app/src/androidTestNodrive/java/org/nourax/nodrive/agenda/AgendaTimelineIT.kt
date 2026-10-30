/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.LocalDate

/**
 * The Timeline on canned data (no server), plan 3.5: groups by project and phase, % done filling the bar, arrows, diamonds, the agent
 * on each item, my busy time shaded; a tap opens the detail sheet with the date editor; a locked bar can not be changed; a change
 * made offline shows "Waiting to send"; a move can be undone. Light and dark (the script sets the mode).
 */
class AgendaTimelineIT {
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
        ActivityScenario.launch(AgendaActivity::class.java).use { scenario ->
            waitForTag("agenda_tab_timeline")
            compose.onNodeWithTag("agenda_tab_timeline").performClick()
            waitForTag("agenda_timeline")
            then(scenario)
        }
    }

    private fun engine(online: Boolean = true) = CannedEditEngine(CannedTimeline.snapshot(), online)

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGoneTag(tag: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun bar(title: String) = hasTestTag("timeline_bar") and hasContentDescription(title, substring = true)

    private fun diamond(title: String) = hasTestTag("timeline_diamond") and hasContentDescription(title, substring = true)

    private fun waitForBar(title: String) {
        compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(bar(title)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openBar(title: String) {
        waitForBar(title)
        compose.onNode(bar(title)).performScrollTo()
        compose.onNode(bar(title)).performClick()
        waitForTag("agenda_detail")
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun descriptionOf(title: String): String =
        compose.onNode(bar(title)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    private fun shot(name: String) {
        compose.waitForIdle()
        support.assertNoServerSoftwareName(compose)
        support.backup.sleepForAnimation()
        support.screenshot(name)
    }

    @Test
    fun theTimelineShowsGroupsPercentArrowsDiamondsShadingAndAgents() {
        show(engine()) {
            waitForBar("Write homepage copy")
            // the groups: a project, its phase, a list of NoTasks and a board of NoBoard
            waitForText("Website")
            waitForText("Phase 1")
            waitForText("Internal")
            waitForText("NoTasks › Personal")
            waitForText("NoBoard › Team board")
            // the bars: four tasks of the Website (one in the phase), the viewer project's, and the to-do
            val bars = compose.onAllNodesWithTag("timeline_bar").fetchSemanticsNodes().size
            assertEquals(6, bars)
            // the milestone and the card are diamonds
            assertEquals(2, compose.onAllNodesWithTag("timeline_diamond").fetchSemanticsNodes().size)
            compose.onNode(diamond("Launch")).assertExists()
            compose.onNode(diamond("Pay invoice")).assertExists()
            // the agent on each item, and the % done, are in what a screen reader says
            val copy = descriptionOf("Write homepage copy")
            assertTrue(copy, copy.contains("Writer-Bot"))
            assertTrue(copy, copy.contains("40% done"))
            assertTrue(descriptionOf("Prepare client demo").contains("Self"))
            // the legend and the scale
            waitForTag("timeline_legend")
            waitForTag("timeline_scale")
            waitForTag("timeline_canvas")
            shot("timeline")
        }
    }

    @Test
    fun theZoomHasWeeksAndDays() {
        show(engine()) {
            waitForBar("Prepare client demo")
            compose.onNodeWithTag("timeline_zoom_days").performClick()
            waitForBar("Prepare client demo")
            compose.onNodeWithTag("timeline_zoom_weeks").performClick()
            waitForBar("Prepare client demo")
            assertEquals(6, compose.onAllNodesWithTag("timeline_bar").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aTapOpensTheSheetWithTheDateEditorAndTheTasksItFollows() {
        val engine = engine()
        show(engine) {
            openBar("Publish banners")
            waitForTag("timeline_date_editor")
            compose.onNodeWithTag("timeline_earlier").assertExists()
            compose.onNodeWithTag("timeline_later").assertExists()
            compose.onNodeWithTag("timeline_change_dates").assertExists()
            // what the arrows say in words: the task of somebody else is named with its owner
            waitForText("After: Banner design (Sara)")
            waitForText("After: Prepare client demo")
            shot("timeline-date-editor")
            // the range picker opens and closes without a change
            compose.onNodeWithTag("timeline_change_dates").performClick()
            waitForTag("timeline_range_picker")
            support.backup.sleepForAnimation()
            shot("timeline-date-picker")
            androidx.test.espresso.Espresso.pressBack()
            waitGoneTag("timeline_range_picker")
            assertTrue(engine.submitted.isEmpty())
        }
    }

    @Test
    fun oneDayLaterIsAGanttMoveThatCanBeUndone() {
        val engine = engine()
        show(engine) {
            openBar("Prepare client demo")
            waitForTag("timeline_date_editor")
            val before = textOf("timeline_dates")
            compose.onNodeWithTag("timeline_later").performScrollTo()
            compose.onNodeWithTag("timeline_later").performClick()
            waitGoneTag("agenda_detail")
            val command = engine.submitted.single()
            assertEquals(CommandKind.GANTT_UPDATE, command.kind)
            assertEquals("gantt:task:101", command.itemKey)
            assertEquals("D:${CannedAgenda.TODAY}", command.change[EditKeys.START])
            assertEquals("D:${CannedAgenda.TODAY.plusDays(2)}", command.change[EditKeys.END])
            assertEquals(EditKeys.MOVE_PURE, command.change[EditKeys.MOVE])
            // the notice says the dates and offers Undo for a few seconds
            waitForText("Moved to")
            waitForText("Undo")
            shot("timeline-undo")
            compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            waitForText("Change undone.")
            openBar("Prepare client demo")
            waitForTag("timeline_dates")
            assertEquals("the dates are back", before, textOf("timeline_dates"))
        }
    }

    @Test
    fun aTodoAndACardMoveThroughTheSameEditor() {
        val engine = engine()
        show(engine) {
            openBar("Draft report")
            compose.onNodeWithTag("timeline_earlier").performScrollTo()
            compose.onNodeWithTag("timeline_earlier").performClick()
            waitGoneTag("agenda_detail")
            val todo = engine.submitted.single()
            assertEquals(CommandKind.CAL_PATCH, todo.kind)
            assertEquals("D:${CannedAgenda.TODAY.minusDays(2)}", todo.change[EditKeys.START])
            assertEquals("D:${CannedAgenda.TODAY.plusDays(2)}", todo.change[EditKeys.DUE])
            waitForBar("Draft report")
            // a card has only a due date
            compose.waitUntil(WAIT_MILLIS) { compose.onAllNodes(diamond("Pay invoice")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(diamond("Pay invoice")).performScrollTo()
            compose.onNode(diamond("Pay invoice")).performClick()
            waitForTag("agenda_detail")
            compose.onNodeWithTag("timeline_later").performScrollTo()
            compose.onNodeWithTag("timeline_later").performClick()
            waitGoneTag("agenda_detail")
            val card = engine.submitted.last()
            assertEquals(CommandKind.DECK_UPDATE, card.kind)
            assertEquals("D:${CannedAgenda.TODAY.plusDays(3)}", card.change[EditKeys.DUE])
        }
    }

    @Test
    fun aBarOfAViewerProjectAndAMilestoneCannotBeChanged() {
        val engine = engine()
        show(engine) {
            openBar("Internal checklist")
            waitForTag("agenda_detail_locked")
            assertTrue(compose.onAllNodesWithTag("timeline_earlier").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("timeline_later").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("timeline_change_dates").fetchSemanticsNodes().isEmpty())
            waitForTag("timeline_dates")
            shot("timeline-readonly")
            androidx.test.espresso.Espresso.pressBack()
            waitGoneTag("agenda_detail")
            // a milestone is a diamond that opens a sheet without a date editor
            compose.onNode(diamond("Launch")).performScrollTo()
            compose.onNode(diamond("Launch")).performClick()
            waitForTag("agenda_detail")
            assertTrue(compose.onAllNodesWithTag("timeline_later").fetchSemanticsNodes().isEmpty())
            assertTrue(engine.submitted.isEmpty())
        }
    }

    @Test
    fun aChangeMadeOfflineShowsWaitingToSendOnTheBar() {
        val engine = engine(online = false)
        show(engine) {
            openBar("Prepare client demo")
            compose.onNodeWithTag("timeline_later").performScrollTo()
            compose.onNodeWithTag("timeline_later").performClick()
            waitGoneTag("agenda_detail")
            waitForTag("timeline_waiting")
            waitForText("Waiting to send")
            // while it waits, the date editor is not offered for it
            openBar("Prepare client demo")
            assertTrue(compose.onAllNodesWithTag("timeline_later").fetchSemanticsNodes().isEmpty())
            androidx.test.espresso.Espresso.pressBack()
            shot("timeline-waiting")
        }
    }

    @Test
    fun withoutDatedWorkTheTimelineSaysSo() {
        show(CannedEditEngine(CannedTimeline.empty())) {
            waitForText("No dated work in this range.")
            assertFalse(compose.onAllNodesWithTag("timeline_bar").fetchSemanticsNodes().isNotEmpty())
            shot("timeline-empty")
        }
    }

    @Test
    fun theArrowsMoveTheRangeAndTodayComesBack() {
        show(engine()) {
            waitForBar("Prepare client demo")
            compose.onNodeWithTag("agenda_next").performClick()
            // four weeks later nothing of the canned data is in the range
            waitForText("No dated work in this range.")
            compose.onNodeWithTag("agenda_today").performClick()
            waitForBar("Prepare client demo")
            assertEquals(LocalDate.of(2026, 10, 28), CannedAgenda.TODAY)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
    }
}
