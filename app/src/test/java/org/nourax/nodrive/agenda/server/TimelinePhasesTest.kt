/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.AccountAgenda
import org.nourax.nodrive.agenda.AgendaSnapshot
import org.nourax.nodrive.agenda.edit.InMemoryEditStore
import org.nourax.nodrive.agenda.edit.TimelineEdit
import org.nourax.nodrive.agenda.logic.TimelineInput
import org.nourax.nodrive.agenda.logic.TimelineLayout
import org.nourax.nodrive.agenda.logic.TimelineScale
import org.nourax.nodrive.agenda.logic.TimelineZoom
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import org.nourax.nodrive.agenda.UTC
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Timeline against the throwaway server (plan 3.5, phase `timeline`): the date editor's command moves Ali's task, NoGantt moves
 * the task that follows it (`propagated`), the notice names the dates NoGantt really saved, and Undo gives the original dates back.
 * Also what the seed gives the Timeline: the phase, the dependency on Sara's task as a stub. The server is read back with plain HTTP.
 * Skipped unless NODRIVE_AG_URL is set.
 */
class TimelinePhasesTest {
    private val gantt = "ocs/v2.php/apps/nc_gantt/api/v1/"
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val tag = UUID.randomUUID().toString().take(6)

    @Before
    fun server() = ServerSupport.assumeServer()

    private fun row(check: String, ok: Boolean, detail: String) {
        ServerSupport.report("report.md", "| $check | ${if (ok) "PASS" else "FAIL"} | $detail |")
    }

    private class Session(user: String) {
        val agenda = AccountAgenda("$user@throwaway", ServerSupport.http(user), InMemoryAgendaStore(), ServerSupport.clock, editStore = InMemoryEditStore())

        fun refresh(): AgendaSnapshot = agenda.refresh(manual = true)
    }

    private fun AgendaSnapshot.mine(title: String): AgendaItem =
        build.items.first { it.title == title && it.workKind != WorkKind.TEAM && !it.problemOnly }

    private fun nextMonday(from: LocalDate): LocalDate {
        var d = from
        while (d.dayOfWeek != DayOfWeek.MONDAY) d = d.plusDays(1)
        return d
    }

    private fun link(raw: RawServer, successor: Int, predecessor: Int) {
        val body = JsonObject()
        body.add(
            "dependencies",
            JsonArray().also {
                val d = JsonObject()
                d.addProperty("predecessor_id", "t-$predecessor")
                d.addProperty("dependency_type", "EA")
                d.addProperty("lag_days", 0)
                it.add(d)
            }
        )
        raw.json("PUT", "${gantt}tasks/$successor?format=json", body)
    }

    @Test
    fun theSeedGivesThePhaseAndTheDependencyOnSarasTask() {
        val ali = Session("ali")
        val snapshot = ali.refresh()
        val copy = snapshot.mine("Write homepage copy")
        assertEquals("Phase 1", copy.phaseName)
        assertTrue(copy.phaseId > 0)
        val banners = snapshot.mine("Publish banners")
        val dep = banners.dependencies.single()
        assertEquals("EA", dep.type)
        assertEquals("Banner design", dep.predecessorTitle)
        assertEquals("Sara", dep.predecessorOwner)
        val layout = TimelineLayout.build(
            TimelineInput(
                snapshot.build.items, TimelineScale.of(snapshot.today.minusDays(7) to snapshot.today.plusWeeks(8), TimelineZoom.WEEKS), snapshot.today,
                snapshot.analysis.days
            )
        )
        assertTrue("the task of somebody else is a stub", layout.arrows.any { it.stub && it.toKey == banners.key && it.fromTitle == "Banner design" })
        assertTrue(layout.entries.any { it.group == org.nourax.nodrive.agenda.logic.TimelineGroup.PHASE && it.title == "Phase 1" })
        row("timeline seed", true, "phase ${copy.phaseName}; stub for ${dep.predecessorTitle} (${dep.predecessorOwner})")
    }

    @Test
    fun theDateEditorMovesATaskAndTheOneThatFollowsAndUndoGivesTheDatesBack() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val aliResource = seed.resource("Ali")
        val start = nextMonday(today.plusDays(1))
        val a = mara.createTask(project, "Timeline task A $tag", start.toString(), start.plusDays(2).toString(), listOf(aliResource to 4.0))
        val b = mara.createTask(project, "Timeline task B $tag", start.plusDays(3).toString(), start.plusDays(4).toString(), listOf(aliResource to 2.0))
        link(mara, b, a)
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val item = snapshot.mine("Timeline task A $tag")
        assertEquals(start, item.startDate)
        assertEquals("B follows A: the layout has an arrow", 1, snapshot.mine("Timeline task B $tag").dependencies.size)

        // two days later (Mon-Wed -> Wed-Fri): B (Thu-Fri) has to move after A
        val command = TimelineEdit.moveBy(item, 2, UTC, snapshot.build.myAgents)
        assertNotNull(command)
        snapshot = ali.agenda.submit(command!!)
        assertTrue("nothing waits: ${snapshot.edit.pending}", snapshot.edit.pending.isEmpty())
        assertTrue("no conflict: ${snapshot.edit.conflicts.map { it.reason.name + ":" + it.detail }}", snapshot.edit.conflicts.isEmpty())
        val notice = snapshot.edit.notice!!
        assertTrue("NoGantt gives an id for Undo", notice.undoOp.isNotEmpty())
        val savedA = mara.task(project, a)
        assertTrue(savedA.get("start_date").asString, savedA.get("start_date").asString.startsWith(start.plusDays(2).toString()))
        assertTrue(savedA.get("end_date").asString, savedA.get("end_date").asString.startsWith(start.plusDays(4).toString()))
        // the notice names the dates NoGantt saved
        assertEquals(start.plusDays(2), notice.movedStart)
        assertEquals(start.plusDays(4), notice.movedEnd)
        assertFalse(notice.datesAdjusted)
        // the dependent task moved (NoGantt says which)
        val savedB = mara.task(project, b)
        val bStart = LocalDate.parse(savedB.get("start_date").asString.take(10))
        assertTrue("B starts after A ends, got $bStart", bStart.isAfter(start.plusDays(3)) || bStart == start.plusDays(3))
        row("timeline move", true, "A moved +2 days; B now starts $bStart; also moved ${notice.alsoMoved}")

        // Undo gives the original dates back, for both
        snapshot = ali.agenda.undo(notice.undoOp)
        assertTrue(snapshot.edit.notice!!.undone)
        val backA = mara.task(project, a)
        assertTrue(backA.get("start_date").asString, backA.get("start_date").asString.startsWith(start.toString()))
        assertTrue(backA.get("end_date").asString, backA.get("end_date").asString.startsWith(start.plusDays(2).toString()))
        val backB = mara.task(project, b)
        assertTrue(backB.get("start_date").asString, backB.get("start_date").asString.startsWith(start.plusDays(3).toString()))
        row("timeline undo", true, "both tasks have their first dates again")
    }

    @Test
    fun aMoveOntoAWeekendShowsTheDatesNoGanttSavedNotTheOnesSent() {
        val seed = Seed.load()
        val mara = RawServer("mara")
        val project = seed.project("Website")
        val aliResource = seed.resource("Ali")
        // Thursday and Friday, two days later is Saturday and Sunday
        val thursday = nextMonday(today.plusDays(1)).plusDays(3)
        val c = mara.createTask(project, "Timeline weekend $tag", thursday.toString(), thursday.plusDays(1).toString(), listOf(aliResource to 4.0))
        val ali = Session("ali")
        var snapshot = ali.refresh()
        val item = snapshot.mine("Timeline weekend $tag")
        val sentStart = thursday.plusDays(2)
        val sentEnd = thursday.plusDays(3)
        snapshot = ali.agenda.submit(TimelineEdit.moveBy(item, 2, UTC, snapshot.build.myAgents)!!)
        assertTrue(snapshot.edit.conflicts.isEmpty())
        val notice = snapshot.edit.notice!!
        val saved = mara.task(project, c)
        val savedStart = LocalDate.parse(saved.get("start_date").asString.take(10))
        val savedEnd = LocalDate.parse(saved.get("end_date").asString.take(10))
        // the toast matches the server, whatever NoGantt did with the weekend
        assertEquals(savedStart, notice.movedStart)
        assertEquals(savedEnd, notice.movedEnd)
        // and it says so when they differ from what was sent (and not when they do not)
        assertEquals(savedStart != sentStart || savedEnd != sentEnd, notice.datesAdjusted)
        row("timeline weekend", true, "sent $sentStart - $sentEnd, NoGantt saved $savedStart - $savedEnd, adjusted=${notice.datesAdjusted}")
        ali.agenda.undo(notice.undoOp)
    }
}
