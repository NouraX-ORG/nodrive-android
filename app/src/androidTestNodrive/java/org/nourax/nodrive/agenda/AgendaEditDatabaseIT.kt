/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.CommandState
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditConflict
import org.nourax.nodrive.agenda.edit.EditDatabase
import org.nourax.nodrive.agenda.edit.EditKeys
import org.nourax.nodrive.agenda.edit.RoomEditStore
import org.nourax.nodrive.agenda.model.Home

/** The offline queue in a real Room database file: it keeps its order, survives closing and opening (a restart), and keeps conflicts. */
class AgendaEditDatabaseIT {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "nodrive_agenda_edit_test.db"

    private fun open(): EditDatabase = Room.databaseBuilder(context, EditDatabase::class.java, name).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun theQueueKeepsItsOrderAndSurvivesARestart() {
        var db = open()
        var store = RoomEditStore(db)
        val first = store.add(
            EditCommand(
                account = "ali@x", createdMs = 1, kind = CommandKind.CAL_PATCH, itemKey = "cal:work/a.ics", home = Home.CALENDAR, title = "A",
                locator = mapOf("objectHref" to "/remote.php/dav/calendars/ali/work/a.ics"), baseVersion = "\"e1\"", change = mapOf(EditKeys.SUMMARY to "New A")
            )
        )
        val second = store.add(EditCommand(account = "ali@x", createdMs = 2, kind = CommandKind.GANTT_UPDATE, itemKey = "gantt:task:1", home = Home.GANTT, title = "B"))
        store.add(EditCommand(account = "sara@x", createdMs = 3, kind = CommandKind.DECK_UPDATE, itemKey = "deck:card:1", home = Home.BOARD))
        assertTrue(first.id < second.id)
        db.close()

        db = open()
        store = RoomEditStore(db)
        val back = store.commands("ali@x")
        assertEquals(listOf("cal:work/a.ics", "gantt:task:1"), back.map { it.itemKey })
        assertEquals("New A", back[0].change[EditKeys.SUMMARY])
        assertEquals("\"e1\"", back[0].baseVersion)
        assertEquals("/remote.php/dav/calendars/ali/work/a.ics", back[0].locator["objectHref"])
        assertEquals(Home.CALENDAR, back[0].home)
        assertEquals(CommandState.WAITING, back[0].state)
        // a change of the state is kept
        store.update(back[0].copy(state = CommandState.SENDING, attempts = 2, rebase = true))
        db.close()
        db = open()
        val changed = RoomEditStore(db).commands("ali@x")[0]
        assertEquals(CommandState.SENDING, changed.state)
        assertEquals(2, changed.attempts)
        assertTrue(changed.rebase)
        db.close()
    }

    @Test
    fun conflictsAreKeptUntilTheyAreRemovedAndOnlyOfThatAccount() {
        val db = open()
        val store = RoomEditStore(db)
        val command = EditCommand(account = "ali@x", kind = CommandKind.CAL_PATCH, itemKey = "cal:work/a.ics", title = "A", change = mapOf(EditKeys.SUMMARY to "Mine"))
        val conflict = store.addConflict(EditConflict(0, "ali@x", command, ConflictReason.CHANGED_ELSEWHERE, "etag", "\"e2\"", 5))
        store.addConflict(EditConflict(0, "sara@x", command.copy(account = "sara@x"), ConflictReason.GONE, "", "", 6))
        val read = store.conflicts("ali@x").single()
        assertEquals(conflict.id, read.id)
        assertEquals(ConflictReason.CHANGED_ELSEWHERE, read.reason)
        assertEquals("Mine", read.command.change[EditKeys.SUMMARY])
        assertEquals("\"e2\"", read.serverVersion)
        store.removeConflict("ali@x", conflict.id)
        assertTrue(store.conflicts("ali@x").isEmpty())
        assertEquals(1, store.conflicts("sara@x").size)
        db.close()
    }

    @Test
    fun removingAnAccountDeletesItsUnsentChangesAndConflictsOnly() {
        val db = open()
        val store = RoomEditStore(db)
        store.add(EditCommand(account = "ali@x", itemKey = "a"))
        store.add(EditCommand(account = "sara@x", itemKey = "b"))
        store.addConflict(EditConflict(0, "ali@x", EditCommand(account = "ali@x"), ConflictReason.GONE, "", "", 1))
        store.forget("ali@x")
        assertTrue(store.commands("ali@x").isEmpty())
        assertTrue(store.conflicts("ali@x").isEmpty())
        assertEquals(1, store.commands("sara@x").size)
        db.close()
    }
}
