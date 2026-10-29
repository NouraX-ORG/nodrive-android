/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.store.AgendaDatabase
import org.nourax.nodrive.agenda.store.RoomAgendaStore
import org.nourax.nodrive.agenda.store.StoredSource

/** The offline cache in a real Room database: a round trip, one transaction per source, and removing only one account's rows. */
class AgendaDatabaseIT {
    private lateinit var database: AgendaDatabase
    private lateinit var store: RoomAgendaStore

    @Before
    fun setUp() {
        database = AgendaDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        store = RoomAgendaStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aSourceRoundTripsAndIsReplacedAsAWhole() {
        store.write("ali@x", "gantt", StoredSource("{\"a\":1}", "etag-1", 1000, "ok"))
        val first = store.read("ali@x", "gantt")!!
        assertEquals("{\"a\":1}", first.json)
        assertEquals("etag-1", first.etag)
        assertEquals(1000L, first.fetchedMs)
        store.write("ali@x", "gantt", StoredSource("{\"a\":2}", "etag-2", 2000, "ok"))
        assertEquals("{\"a\":2}", store.read("ali@x", "gantt")!!.json)
        assertNull(store.read("ali@x", "deck"))
    }

    @Test
    fun theSettingsRoundTrip() {
        store.saveSettings("ali@x", "{\"view\":\"week\"}")
        assertEquals("{\"view\":\"week\"}", store.loadSettings("ali@x"))
        store.saveSettings("ali@x", "{\"view\":\"month\"}")
        assertEquals("{\"view\":\"month\"}", store.loadSettings("ali@x"))
        assertNull(store.loadSettings("sara@x"))
    }

    @Test
    fun removingAnAccountDeletesOnlyItsRows() {
        store.write("ali@x", "gantt", StoredSource("{}", "", 1, "ok"))
        store.write("ali@x", "calendar", StoredSource("{}", "", 1, "ok"))
        store.saveSettings("ali@x", "{}")
        store.write("sara@x", "gantt", StoredSource("{}", "", 1, "ok"))
        store.saveSettings("sara@x", "{}")
        store.forget("ali@x")
        assertNull(store.read("ali@x", "gantt"))
        assertNull(store.read("ali@x", "calendar"))
        assertNull(store.loadSettings("ali@x"))
        assertNotNull(store.read("sara@x", "gantt"))
        assertNotNull(store.loadSettings("sara@x"))
    }
}
