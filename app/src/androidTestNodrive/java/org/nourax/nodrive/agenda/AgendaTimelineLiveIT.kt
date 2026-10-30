/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.ui.AgendaActivity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The real app, signed in as Ali on the throwaway server, shows the Timeline of the seeded work (plan 3.5) and moves a bar through
 * the date editor of the sheet, then takes it back with Undo. What the server holds is read back with plain HTTP (LiveRaw). Light and
 * dark: agenda-live-timeline-{light,dark}.png.
 */
class AgendaTimelineLiveIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = AgendaTestSupport()
    private val tag = UUID.randomUUID().toString().take(5)
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private lateinit var mara: LiveRaw

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
        support.useClock(Instant.now())
        mara = LiveRaw(checkNotNull(support.serverUrl), "mara", checkNotNull(support.token("mara")))
    }

    @After
    fun tearDown() {
        support.reset()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGoneTag(tag: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun bar(title: String) = hasTestTag("timeline_bar") and hasContentDescription(title, substring = true)

    private fun waitForBar(title: String) {
        compose.waitUntil(LOAD_MILLIS) { compose.onAllNodes(bar(title)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + LOAD_MILLIS
        while (System.currentTimeMillis() < end) {
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("the server never held: $what")
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
            compose.onNodeWithTag("agenda_tab_timeline").performClick()
            waitForTag("agenda_timeline")
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

    @Test
    fun theTimelineShowsAlisSeededWorkAndTheDateEditorMovesABarAndUndoTakesItBack() {
        val project = mara.projectId("Website")
        val aliResource = mara.resourceId("Ali")
        // a Monday to Wednesday task that Ali can move by one day without a weekend in the way
        var monday = today.plusDays(1)
        while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.plusDays(1)
        val task = mara.createTask(project, "Live timeline $tag", monday.toString(), monday.plusDays(2).toString(), listOf(aliResource to 4.0))
        open {
            // the seeded work: the project, its phase, the bars of Ali, the task he follows (Sara's) as a stub in words
            waitForBar("Write homepage copy")
            waitForText("Website")
            waitForText("Phase 1")
            waitForBar("Publish banners")
            waitForBar("Live timeline $tag")
            compose.onNode(bar("Publish banners")).performScrollTo()
            compose.onNode(bar("Publish banners")).performClick()
            waitForTag("agenda_detail")
            waitForText("After: Banner design (Sara)")
            androidx.test.espresso.Espresso.pressBack()
            waitGoneTag("agenda_detail")
            shot("live-timeline")

            // one day later through the date editor; NoGantt keeps the working days; Undo takes it back
            compose.onNode(bar("Live timeline $tag")).performScrollTo()
            compose.onNode(bar("Live timeline $tag")).performClick()
            waitForTag("timeline_date_editor")
            compose.onNodeWithTag("timeline_later").performScrollTo()
            compose.onNodeWithTag("timeline_later").performClick()
            waitGoneTag("agenda_detail")
            waitForText("Moved to")
            waitForText("Undo")
            eventually("the start one day later") { mara.task(project, task).get("start_date").asString.startsWith(monday.plusDays(1).toString()) }
            eventually("the end one day later") { mara.task(project, task).get("end_date").asString.startsWith(monday.plusDays(3).toString()) }
            compose.onNode(hasText("Undo") and hasClickAction()).performClick()
            waitForText("Change undone.")
            eventually("the first dates again") {
                val t = mara.task(project, task)
                t.get("start_date").asString.startsWith(monday.toString()) && t.get("end_date").asString.startsWith(monday.plusDays(2).toString())
            }
            assertTrue(compose.onAllNodes(bar("Live timeline $tag")).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private companion object {
        const val LOAD_MILLIS = 60_000L
        const val DIALOG_MILLIS = 5_000L
        const val POLL_MILLIS = 1_000L
    }
}
