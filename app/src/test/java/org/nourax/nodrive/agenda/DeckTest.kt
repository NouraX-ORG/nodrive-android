/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.deck.DeckFetchOptions
import org.nourax.nodrive.agenda.deck.DeckSnapshot
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.BoardRelation
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.ocs.ServerInfo
import java.time.LocalDate

class DeckTest {
    private val deck = "index.php/apps/deck/api/v1.0"

    @Test
    fun theVersionGuardKnowsOnlyTheTestedMinor() {
        assertTrue(DeckClient.versionIsTested("1.19.1"))
        assertTrue(DeckClient.versionIsTested("1.19.7"))
        assertFalse(DeckClient.versionIsTested("1.20.0"))
        assertFalse(DeckClient.versionIsTested("2.0.0"))
        assertFalse(DeckClient.versionIsTested("1.18.6"))
        assertFalse(DeckClient.versionIsTested(""))
    }

    @Test
    fun readsTheBoardAndItsStacks() {
        val http = FakeHttp()
        val input = loadScenario("ali", http)
        assertTrue(input.deck.loaded)
        assertEquals("1.19.1", input.deck.version)
        assertEquals(1, input.deck.boards.size())
        val board = input.deck.boards.get(0).asJsonObject
        assertEquals(1, board.getAsJsonArray("_stacks").size())
        assertEquals(8, board.getAsJsonArray("_stacks").get(0).asJsonObject.getAsJsonArray("cards").size())
        // every Deck request carries the OCS header, so the server takes the path that does not log the session out
        for (request in http.requests.filter { it.path.contains("apps/deck") }) {
            assertEquals(request.path, "true", request.headers["OCS-APIRequest"])
        }
    }

    @Test
    fun aServerWithoutTheBoardAppIsUnavailable() {
        val http = FakeHttp()
        val result = DeckClient.fetch(http, DeckFetchOptions("ali", "", NOW.toEpochMilli()), DeckSnapshot())
        assertTrue(result.ok)
        assertTrue(result.snapshot.unavailable)
        assertEquals(0, http.requests.size) // not even asked
    }

    @Test
    fun aFailureKeepsThePreviousBoards() {
        val http = FakeHttp()
        val input = loadScenario("ali", http)
        http.offline = true
        val result = DeckClient.fetch(http, DeckFetchOptions("ali", "1.19.1", NOW.toEpochMilli()), input.deck)
        assertFalse(result.ok)
        assertEquals(1, result.snapshot.boards.size())
    }

    @Test
    fun aHiddenBoardStaysInTheSourcesAndCanBeShownAgain() {
        val http = FakeHttp()
        val input = loadScenario("ali", http)
        val stacksPath = "$deck/boards/10/stacks"
        val before = http.count("GET", stacksPath)
        val hidden = DeckClient.fetch(http, DeckFetchOptions("ali", "1.19.1", NOW.toEpochMilli(), wantBoard = { it.id != 10 }), input.deck)
        assertTrue(hidden.ok)
        assertEquals(before, http.count("GET", stacksPath))
        assertEquals(1, hidden.snapshot.boards.size())
        assertFalse(hidden.snapshot.boards.get(0).asJsonObject.has("_stacks"))
        val out = AgendaBuilder.build(input.copy(deck = hidden.snapshot))
        val entry = out.sources.firstOrNull { it.key == "10" && it.type == org.nourax.nodrive.agenda.model.SourceType.BOARD }
        assertNotNull("the hidden board must stay on the Sources screen", entry)
        assertTrue(out.items.none { it.boardId == 10 })
        val shown = DeckClient.fetch(http, DeckFetchOptions("ali", "1.19.1", NOW.toEpochMilli()), hidden.snapshot)
        assertEquals(before + 1, http.count("GET", stacksPath))
        assertTrue(shown.snapshot.boards.get(0).asJsonObject.has("_stacks"))
    }

    @Test
    fun myCardsHaveTheirAgentFromTheLabel() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        val call = items.byKey("deck:card:502")!!
        assertEquals(WorkKind.MY_AGENT, call.workKind)
        assertEquals("Writer-Bot", call.agent.name)
        val pay = items.byKey("deck:card:503")!!
        assertTrue(pay.agent.isSelf)
        assertTrue(pay.dueToday)
        assertEquals(LocalDate.of(2026, 10, 28), pay.due)
    }

    @Test
    fun aCardWithoutADueDateHasNoDate() {
        val idea = AgendaBuilder.build(loadScenario("ali")).items.byTitle("Someday idea")!!
        assertFalse(idea.hasDate)
    }

    @Test
    fun anUnknownRobotLabelIsFlagged() {
        val stranger = AgendaBuilder.build(loadScenario("ali")).items.byTitle("Stranger bot")!!
        assertTrue(stranger.agent.unknown)
        assertTrue(stranger.has(ProblemKind.UNKNOWN_AGENT))
    }

    @Test
    fun doneUnassignedAndOtherPeoplesCardsAreNotInMyList() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        assertNull(items.byKey("deck:card:506")) // done
        assertNull(items.byKey("deck:card:508")) // nobody
        assertNull(items.byKey("deck:card:504")) // Sara's: Ali does not run this board
    }

    @Test
    fun aLinkedGanttTaskAndCardAreOneRow() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        assertNotNull(items.byKey("gantt:task:111"))
        assertNull(items.byKey("deck:card:501"))
        assertTrue(items.byKey("gantt:task:111")!!.boardBadge)
    }

    @Test
    fun aBoardIAmOnlyAMemberOfIsDoAndOneIRunIsDoOversee() {
        val ali = BoardRelation.of(loadScenario("ali").deck.boards.get(0).asJsonObject, "ali")
        assertFalse(ali.owned)
        assertFalse(ali.canManage)
        assertEquals(WorkMode.DO, ali.defaultMode())
        val mara = BoardRelation.of(loadScenario("mara").deck.boards.get(0).asJsonObject, "mara")
        assertTrue(mara.owned)
        assertTrue(mara.canManage)
        assertEquals(WorkMode.DO_OVERSEE, mara.defaultMode())
    }

    @Test
    fun theManagerSeesTheProblemsOfTheBoard() {
        val items = AgendaBuilder.build(loadScenario("mara")).items
        val late = items.byKey("deck:card:504")!!
        assertEquals(WorkKind.TEAM, late.workKind)
        assertEquals("Sara", late.personName)
        assertEquals("Design-Bot", late.agent.name)
        assertTrue(late.has(ProblemKind.LATE))
        assertTrue(items.byKey("deck:card:503")!!.has(ProblemKind.DUE_TODAY))
        val backlog = items.byTitle("Unassigned backlog")!!
        assertTrue(backlog.has(ProblemKind.NOT_ASSIGNED))
        assertTrue(items.byKey("deck:card:502")!!.problems.isEmpty())
    }

    @Test
    fun aNewerDeckMakesTheCardsReadOnly() {
        val input = loadScenario("ali")
        val newer = input.copy(deck = input.deck.copy(version = "1.20.0"))
        val out = AgendaBuilder.build(newer)
        assertTrue(out.boardReadOnlyByGuard)
        assertTrue(out.items.byKey("deck:card:502")!!.versionReadOnly)
        assertFalse(AgendaBuilder.build(loadAliAnswered()).boardReadOnlyByGuard)
        // the capabilities of the newer server are told apart by the same version string
        val info = org.nourax.nodrive.agenda.ocs.ServerInfoClient.applyCapabilities(Fx.obj("server/capabilities-newer-deck.json"), ServerInfo())
        assertFalse(DeckClient.versionIsTested(info.deckVersion))
    }

    @Test
    fun theSnapshotSurvivesItsCacheForm() {
        val input = loadScenario("ali")
        val gson = com.google.gson.Gson()
        val copy = gson.fromJson(gson.toJson(input.deck), DeckSnapshot::class.java)
        assertTrue(copy.loaded)
        assertEquals("1.19.1", copy.version)
        assertEquals(1, copy.boards.size())
        assertEquals(SourceSettings(), SourceSettings.fromJson(SourceSettings().toJson()))
    }
}
