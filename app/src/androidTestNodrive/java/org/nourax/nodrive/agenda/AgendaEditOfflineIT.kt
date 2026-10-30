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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Edits in airplane mode (design 9, plan 2.8). tools/ci/agenda-device.sh runs the methods one by one:
 * a1 makes the test data on the throwaway server and reads the day, then the script turns mobile data off;
 * a2 makes three changes on the screens (a new event, a percent done of a Gantt task, a card done): each is on screen at once and
 *    marked "Waiting to send", and the banner counts them; then the script turns data on;
 * a3 opens NoAgenda again (a new start: the queue lives in its own database): all three are sent, in order, and the server holds them;
 * a4 cleans up.
 * The names are fixed so the methods can find each other's data; a run uses a fresh throwaway server.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AgendaEditOfflineIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val taskTitle = "Offline task"
    private val cardTitle = "Offline card"
    private val eventTitle = "Offline event"

    private fun raw(user: String) = LiveRaw(checkNotNull(support.serverUrl), user, checkNotNull(support.token(user)))

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

    private fun answerTheQuestion() {
        compose.waitUntil(LOAD_MILLIS) {
            compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Dentist")).fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { compose.waitUntil(8_000L) { compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty() } }
        if (compose.onAllNodesWithTag("agenda_personal_question").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("agenda_personal_save").performClick()
            waitGoneTag("agenda_personal_question")
        }
    }

    @Test
    fun a1MakeTheDataAndReadTheDay() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
        support.useClock(Instant.now())
        val mara = raw("mara")
        val project = mara.projectId("Website")
        mara.createTask(project, taskTitle, today.toString(), today.plusDays(1).toString(), listOf(mara.resourceId("Ali") to 2.0))
        val board = mara.boardId("Team board")
        mara.createCard(board, mara.stackId(board), cardTitle, today.toString() + "T00:00:00+00:00", "ali")
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            answerTheQuestion()
            see("Dentist")
            see(taskTitle)
            see(cardTitle)
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText("Updated", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            support.backup.sleepForAnimation()
        }
    }

    @Test
    fun a2ThreeChangesWithoutANetworkWaitOnTheScreen() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset(keepSavedCopy = true)
        support.useClock(Instant.now())
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            waitForText("You're offline. Showing what was saved at", OFFLINE_MILLIS)
            see("Dentist")
            // 1. a new event
            compose.onNodeWithTag("agenda_fab").performClick()
            compose.onNodeWithTag("agenda_new_event").performClick()
            waitForTag("agenda_new_sheet")
            compose.onNodeWithTag("edit_title").performTextInput(eventTitle)
            compose.onNodeWithTag("edit_save").performClick()
            waitGoneTag("agenda_new_sheet")
            waitForText("Waiting to send 1 change")
            // 2. 50 percent of a Gantt task
            see(taskTitle)
            compose.onNodeWithText(taskTitle).performClick()
            waitForTag("agenda_detail")
            compose.onNodeWithTag("agenda_edit").performClick()
            waitForTag("edit_percent")
            compose.onNodeWithTag("edit_percent").performTextReplacement("50")
            compose.onNodeWithTag("edit_save").performClick()
            waitGoneTag("agenda_detail")
            waitForText("Waiting to send 2 changes")
            // 3. a card is done
            see(cardTitle)
            compose.onNodeWithText(cardTitle).performClick()
            waitForTag("agenda_detail")
            compose.onNodeWithTag("agenda_mark_done").performClick()
            waitGoneTag("agenda_detail")
            waitForText("Waiting to send 3 changes")
            // the rows with a change waiting are marked; the done card has left the list
            see(eventTitle)
            assertTrue(compose.onAllNodesWithTag("agenda_badge_pending").fetchSemanticsNodes().isNotEmpty())
            compose.waitForIdle()
            support.assertNoServerSoftwareName(compose)
            support.backup.sleepForAnimation()
            support.screenshot("edit-offline-waiting")
        }
    }

    @Test
    fun a3WhenTheNetworkIsBackEverythingIsSentInOrder() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset(keepSavedCopy = true)
        support.useClock(Instant.now())
        support.addServerAccount("ali")
        ActivityScenario.launch(AgendaActivity::class.java).use {
            // a new start of the app: the queue is read from its own database, and the first read sends it before it reads anything
            waitGoneTag("agenda_pending_banner", SEND_MILLIS)
            waitForText("Updated", SEND_MILLIS)
            compose.waitUntil(SEND_MILLIS) { compose.onAllNodes(hasText("You're offline", substring = true)).fetchSemanticsNodes().isEmpty() }
            see(eventTitle)
            val ali = raw("ali")
            val block = ali.block("work", eventTitle) ?: ali.block("personal", eventTitle)
            assertNotNull("the new event reached the server", block)
            val mara = raw("mara")
            val project = mara.projectId("Website")
            val id = mara.createTaskLookup(project, taskTitle)
            assertEquals(50, mara.task(project, id).get("manual_progress").asInt)
            val board = mara.boardId("Team board")
            val stack = mara.stackId(board)
            val cards = mara.request("GET", "index.php/apps/deck/api/v1.0/boards/$board/stacks/$stack").json().asJsonObject.getAsJsonArray("cards")
            val card = cards.map { it.asJsonObject }.first { it.get("title").asString == cardTitle }
            assertTrue("the card is done", card.has("done") && !card.get("done").isJsonNull)
            support.backup.sleepForAnimation()
            support.screenshot("edit-offline-sent")
        }
    }

    @Test
    fun a4CleanUp() {
        support.reset()
    }

    /** The id of the task with that title. */
    private fun LiveRaw.createTaskLookup(projectId: Int, title: String): Int =
        request("GET", "ocs/v2.php/apps/nc_gantt/api/v1/projects/$projectId/tasks?format=json").ocsData().asJsonArray
            .map { it.asJsonObject }.first { it.get("title").asString == title }.get("id").asInt

    private companion object {
        const val LOAD_MILLIS = 90_000L
        const val OFFLINE_MILLIS = 180_000L
        const val SEND_MILLIS = 180_000L
    }
}
