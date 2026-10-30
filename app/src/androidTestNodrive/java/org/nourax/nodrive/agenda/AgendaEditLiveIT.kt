/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * The real app, signed in as Ali on the throwaway server, makes every kind of change through the SCREENS (plan 2.8): a new event with
 * Writer-Bot, a changed event, a ticked to-do, a moved end date of a Gantt task (with Undo), the agent of a Gantt task and of a card.
 * What the server holds afterwards is read back with plain HTTP (LiveRaw), not through the app. Test data is made by the test with
 * names of its own, so the seed stays as it was.
 */
class AgendaEditLiveIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()
    private val tag = UUID.randomUUID().toString().take(5)
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val robot = AgentRegistry.robot

    private lateinit var ali: LiveRaw
    private lateinit var mara: LiveRaw

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
        support.useClock(Instant.now())
        ali = LiveRaw(checkNotNull(support.serverUrl), "ali", checkNotNull(support.token("ali")))
        mara = LiveRaw(checkNotNull(support.serverUrl), "mara", checkNotNull(support.token("mara")))
    }

    @After
    fun tearDown() {
        support.reset()
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------------
    /** Scrolls the sheet to the control and taps it (the sheet is longer than the screen). */
    private fun tap(tag: String) {
        if (tag == "edit_save") runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performClick()
    }

    private fun waitForText(text: String, millis: Long = LOAD_MILLIS) {
        compose.waitUntil(millis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(tag: String, millis: Long = LOAD_MILLIS) {
        compose.waitUntil(millis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGoneTag(tag: String, millis: Long = LOAD_MILLIS) {
        compose.waitUntil(millis) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun see(text: String) {
        compose.waitUntil(LOAD_MILLIS) {
            runCatching { compose.onNodeWithTag("agenda_list").performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
    }

    /** The first time, the person is asked which calendars are personal; the test answers the way the owner would. */
    private fun answerTheQuestion() {
        compose.waitUntil(LOAD_MILLIS) {
            compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Dentist")).fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { compose.waitUntil(DIALOG_MILLIS) { compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() } }
        if (compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("agenda_personal_save").performClick()
            waitGoneTag("agenda_personal_question")
        }
    }

    private fun open(then: () -> Unit) {
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion()
            waitForText("Dentist")
            then()
        }
    }

    private fun shot(name: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("agenda_refreshing").fetchSemanticsNodes().isEmpty() }
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


    /** The change is sent in the background: waits until the server holds it (the screen has no banner when the network is fine). */
    private fun eventually(what: String, millis: Long = LOAD_MILLIS, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("the server never held: $what")
    }

    /** Waits until nothing waits to be sent any more. */
    private fun sent() {
        waitGoneTag("agenda_pending_banner")
        waitGoneTag("agenda_conflict_card", 1_000)
    }

    /** Picks [target] in the date picker that the field [fieldTag] opens (the picker opens at the month of [current]). */
    private fun pickDay(fieldTag: String, current: LocalDate, target: LocalDate) {
        tap(fieldTag)
        waitForTag("${fieldTag}_ok")
        if (YearMonth.from(current) != YearMonth.from(target)) {
            compose.onNode(hasContentDescription("next month", substring = true, ignoreCase = true)).performClick()
            compose.waitForIdle()
        }
        val byDescription = hasContentDescription(" ${target.dayOfMonth}, ${target.year}", substring = true) and hasClickAction()
        val byText = hasText(target.dayOfMonth.toString()) and hasClickAction()
        try {
            compose.waitUntil(DIALOG_MILLIS * 2) {
                compose.onAllNodes(byDescription).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(byText).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("day ${target.dayOfMonth} not found in the picker; on screen: ${support.allTexts(compose)}", e)
        }
        if (compose.onAllNodes(byDescription).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(byDescription)[0].performClick()
        } else {
            compose.onAllNodes(byText)[0].performClick()
        }
        compose.onNodeWithTag("${fieldTag}_ok").performClick()
        waitGoneTag("${fieldTag}_ok")
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------------------
    @Test
    fun anEventIsMadeWithAnAgentChangedAndDeleted() {
        val title = "Live event $tag"
        open {
            compose.onNodeWithTag("agenda_fab").performClick()
            compose.onNodeWithTag("agenda_new_event").performClick()
            waitForTag("agenda_new_sheet")
            compose.onNodeWithTag("edit_title").performTextInput(title)
            tap("edit_agent_AG-0001")
            shot("edit-live-new-event")
            tap("edit_save")
            waitGoneTag("agenda_new_sheet")
            sent()
            see(title)
            // the server holds the event with the agent id twice (property and category)
            eventually("the new event") { ali.blockAny(title) != null }
            val created = ali.blockAny(title)
            assertNotNull("the event is in one of the calendars", created)
            assertTrue(created, created!!.contains("X-NOURAX-AGENT-ID:AG-0001"))
            assertTrue(created, created.contains("agent:AG-0001"))

            // change it: a new title, and all day
            openRow(title)
            tap("agenda_edit")
            waitForTag("edit_title")
            compose.onNodeWithTag("edit_title").performTextReplacement("$title edited")
            tap("edit_all_day")
            tap("edit_save")
            waitGoneTag("agenda_detail")
            sent()
            see("$title edited")
            eventually("the changed event") { ali.blockAny("$title edited") != null }
            val edited = ali.blockAny("$title edited")
            assertNotNull(edited)
            assertTrue(edited, edited!!.contains("DTSTART;VALUE=DATE:"))
            assertTrue("the agent is still there", edited.contains("X-NOURAX-AGENT-ID:AG-0001"))

            // delete it: it asks first, and the server no longer has it
            openRow("$title edited")
            tap("agenda_delete")
            waitForTag("agenda_delete_question")
            shot("edit-live-delete")
            compose.onNodeWithTag("agenda_delete_confirm").performClick()
            waitGoneTag("agenda_delete_question")
            sent()
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText("$title edited")).fetchSemanticsNodes().isEmpty() }
            eventually("the event is deleted") { !ali.exportAll().contains("$title edited") }
        }
    }

    @Test
    fun aTodoIsTickedDone() {
        val title = "Live todo $tag"
        val yesterday = today.minusDays(1).toString().replace("-", "")
        val created = ali.putObject(
            "personal", "live-$tag.ics",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CI//EN\r\nBEGIN:VTODO\r\nUID:live-$tag\r\nDTSTAMP:20261001T080000Z\r\nSUMMARY:$title\r\n" +
                "DUE;VALUE=DATE:$yesterday\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        )
        assertTrue("the to-do was made: ${created.status}", created.status in 200..299)
        open {
            openRow(title)
            shot("edit-live-todo")
            tap("agenda_mark_done")
            waitGoneTag("agenda_detail")
            sent()
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty() }
            eventually("the to-do is done") { ali.block("personal", title)?.contains("STATUS:COMPLETED") == true }
            val block = ali.block("personal", title)
            assertNotNull(block)
            assertTrue(block, block!!.contains("STATUS:COMPLETED"))
            assertTrue(block, block.contains("PERCENT-COMPLETE:100"))
        }
    }

    @Test
    fun aGanttEndDateIsMovedAndUndone() {
        val project = mara.projectId("Website")
        val aliResource = mara.resourceId("Ali")
        // all inside the current month, so the date picker needs no month change (a task in the past shows under "Late")
        val monthStart = today.withDayOfMonth(1)
        val first = mara.createTask(project, "Live task A $tag", monthStart.toString(), monthStart.plusDays(2).toString(), listOf(aliResource to 4.0))
        val second = mara.createTask(project, "Live task B $tag", monthStart.plusDays(4).toString(), monthStart.plusDays(5).toString(), listOf(aliResource to 2.0))
        mara.link(second, first)
        val target = monthStart.plusDays(3)
        open {
            openRow("Live task A $tag")
            tap("agenda_edit")
            waitForTag("edit_end_date")
            pickDay("edit_end_date", monthStart.plusDays(2), target)
            tap("edit_save")
            waitGoneTag("agenda_detail")
            // NoGantt took the new date; the screen says so and offers Undo for a few seconds
            waitForText("Saved.")
            waitForText("Undo")
            shot("edit-live-gantt-date")
            eventually("the new end date") { mara.task(project, first).get("end_date").asString.startsWith(target.toString()) }
            compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            waitForText("Change undone.")
            eventually("the old end date again") { mara.task(project, first).get("end_date").asString.startsWith(monthStart.plusDays(2).toString()) }
        }
    }

    @Test
    fun theAgentOfATaskAndOfACardIsChanged() {
        val project = mara.projectId("Website")
        val aliResource = mara.resourceId("Ali")
        val writerBot = mara.resourceId("$robot Writer-Bot (Ali)")
        val task = mara.createTask(project, "Live agent task $tag", today.toString(), today.plusDays(1).toString(), listOf(aliResource to 4.0))
        val board = mara.boardId("Team board")
        val stack = mara.stackId(board)
        val card = mara.createCard(board, stack, "Live agent card $tag", today.toString() + "T00:00:00+00:00", "ali")
        open {
            // the task: Self to Writer-Bot, person first
            openRow("Live agent task $tag")
            tap("agenda_edit")
            waitForTag("edit_agent")
            waitForText("Done by you")
            shot("edit-live-agent-task")
            tap("edit_agent_AG-0001")
            tap("edit_save")
            waitGoneTag("agenda_detail")
            sent()
            eventually("the agent row") { mara.task(project, task).getAsJsonArray("resources").map { it.asJsonObject.get("resource_id").asInt } == listOf(aliResource, writerBot) }
            see("Live agent task $tag")

            // the card: Self to Writer-Bot (the label of the board)
            openRow("Live agent card $tag")
            tap("agenda_edit")
            waitForTag("edit_agent")
            tap("edit_agent_AG-0001")
            tap("edit_save")
            waitGoneTag("agenda_detail")
            sent()
            eventually("the agent label") { mara.card(board, stack, card).getAsJsonArray("labels").map { it.asJsonObject.get("title").asString } == listOf("$robot Writer-Bot") }
        }
    }

    private companion object {
        const val LOAD_MILLIS = 90_000L
        const val DIALOG_MILLIS = 8_000L
        const val POLL_MILLIS = 700L
    }
}
