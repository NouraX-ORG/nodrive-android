/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.owncloud.android.datamodel.MediaFolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupControllerTest {
    private val camera = "/storage/emulated/0/DCIM/Camera"
    private val movies = "/storage/emulated/0/Movies"
    private val documents = "/storage/emulated/0/Documents"

    private class FakeStore(var rows: List<FolderRow> = emptyList()) : FolderStore {
        val calls = mutableListOf<String>()
        val specs = mutableListOf<FolderSpec>()
        val defaultsSeen = mutableListOf<BackupDefaults>()
        private var nextId = 10L

        override fun rows() = rows

        /** Number of creates that work before the next one throws, like a full database would. */
        var createsUntilFailure = Int.MAX_VALUE

        override fun create(account: String, spec: FolderSpec, defaults: BackupDefaults): Long {
            if (createsUntilFailure-- <= 0) throw IllegalStateException("database is full")
            val id = nextId++
            calls += "create:$id"
            specs += spec
            defaultsSeen += defaults
            rows = rows + FolderRow(id, spec.path, spec.type, account, true)
            return id
        }

        override fun enable(rowId: Long) {
            calls += "enable:$rowId"
            rows = rows.map { if (it.id == rowId) it.copy(enabled = true) else it }
        }

        override fun disable(rowId: Long) {
            calls += "disable:$rowId"
            rows = rows.map { if (it.id == rowId) it.copy(enabled = false) else it }
        }
    }

    private class FakeDevices(var media: List<MediaFolderInfo>, var docs: List<String> = emptyList()) : DeviceFolders {
        override fun mediaFolders() = media

        override fun documentFolders() = docs
    }

    private val data = BackupStateTest.MemoryData()
    private val state = BackupState("a@h", data)
    private val store = FakeStore()
    private val devices = FakeDevices(
        listOf(
            MediaFolderInfo(camera, MediaFolderType.IMAGE),
            MediaFolderInfo(movies, MediaFolderType.VIDEO)
        )
    )
    private val controller = BackupController("a@h", devices, store, state, "Pixel 8")

    @Test
    fun switchingOnCreatesTheRowsRecordsThemAsOwnedAndSavesTheRoot() {
        val result = controller.enableMedia()

        assertEquals(2, store.rows.size)
        assertEquals(setOf(10L, 11L), result.state.owned)
        assertEquals(listOf(10L, 11L), result.rowsToStart)
        assertEquals("/Phone backup/Pixel 8", state.read().root)
        assertTrue(state.read().mediaOn)
        assertEquals(setOf(10L, 11L), state.read().owned)
        assertTrue(store.defaultsSeen.all { it == BackupDefaults(wifiOnly = true, chargingOnly = false) })
        assertEquals(
            setOf(
                BackupLine("DCIM/Camera", "/Phone backup/Pixel 8/DCIM/Camera"),
                BackupLine("Movies", "/Phone backup/Pixel 8/Movies")
            ),
            result.lines.toSet()
        )
    }

    @Test
    fun theWifiDefaultCanBeTurnedOffForATest() {
        controller.enableMedia(BackupDefaults(wifiOnly = false))
        assertTrue(store.defaultsSeen.all { !it.wifiOnly })
    }

    @Test
    fun runningItTwiceDoesNotTouchTheStoreTheSecondTime() {
        controller.enableMedia()
        val callsAfterFirst = store.calls.size
        val second = controller.enableMedia()
        assertEquals(callsAfterFirst, store.calls.size)
        assertTrue(second.rowsToStart.isEmpty())
    }

    @Test
    fun switchingOffStopsOnlyTheOwnedRowsAndKeepsThem() {
        store.rows = listOf(FolderRow(1, "/storage/emulated/0/Pictures", MediaFolderType.IMAGE, "a@h", true))
        controller.enableMedia()
        controller.disableMedia()

        assertEquals(3, store.rows.size)
        assertTrue(store.rows.first { it.id == 1L }.enabled)
        assertTrue(store.rows.filter { it.id != 1L }.none { it.enabled })
        assertTrue(store.calls.none { it.startsWith("delete") })
        assertFalse(state.read().mediaOn)
        assertEquals(setOf(10L, 11L), state.read().owned)
    }

    @Test
    fun switchingOnAgainEnablesTheSameRows() {
        controller.enableMedia()
        controller.disableMedia()
        val again = controller.enableMedia()
        assertEquals(listOf(10L, 11L), again.rowsToStart)
        assertEquals(2, store.rows.size)
        assertTrue(store.rows.all { it.enabled })
    }

    @Test
    fun autoAddCreatesANewFolderOnlyWhileTheSwitchIsOn() {
        assertTrue(controller.autoAdd().rowsToStart.isEmpty())
        controller.enableMedia()
        devices.media = devices.media + MediaFolderInfo("/storage/emulated/0/Pictures/Screenshots", MediaFolderType.IMAGE)

        val added = controller.autoAdd()
        assertEquals(1, added.rowsToStart.size)
        assertEquals(3, store.rows.size)
        assertTrue(controller.autoAdd().rowsToStart.isEmpty())
    }

    @Test
    fun autoAddWhileOffReadsNothingFromThePhone() {
        val reading = object : DeviceFolders {
            var reads = 0

            override fun mediaFolders(): List<MediaFolderInfo> {
                reads++
                return emptyList()
            }

            override fun documentFolders(): List<String> {
                reads++
                return emptyList()
            }
        }
        BackupController("a@h", reading, store, state, "Pixel 8").autoAdd()
        assertEquals(0, reading.reads)
    }

    @Test
    fun documentsHaveTheirOwnSwitch() {
        devices.docs = listOf(documents)
        controller.enableDocs()
        assertEquals(listOf(MediaFolderType.CUSTOM), store.rows.map { it.type })
        assertTrue(state.read().docsOn)
        assertFalse(state.read().mediaOn)
        controller.disableDocs()
        assertTrue(store.rows.none { it.enabled })
    }

    @Test
    fun theFirstRootIsKeptWhenThePhoneIsRenamed() {
        controller.enableMedia()
        val renamed = BackupController("a@h", devices, store, state, "Brand new name")
        renamed.disableMedia()
        renamed.enableMedia()
        assertEquals("/Phone backup/Pixel 8", state.read().root)
    }

    @Test
    fun anotherAccountsRowsAreNeverTouched() {
        store.rows = listOf(FolderRow(1, camera, MediaFolderType.IMAGE, "b@h", true))
        controller.enableMedia()
        controller.disableMedia()
        assertTrue(store.calls.none { it.endsWith(":1") })
        assertTrue(store.rows.first { it.id == 1L }.enabled)
    }

    @Test
    fun aFailurePartWayKeepsTheRowsAlreadyMadeAsOwnedSoSwitchingOffStopsThem() {
        store.createsUntilFailure = 1
        try {
            controller.enableMedia()
            org.junit.Assert.fail("the second create should have failed")
        } catch (_: IllegalStateException) {
            // expected: the store ran out of room
        }
        val saved = state.read()
        assertEquals(setOf(10L), saved.owned)
        assertEquals("/Phone backup/Pixel 8", saved.root)
        assertFalse(saved.mediaOn)

        controller.disableMedia()
        assertTrue(store.rows.none { it.enabled })
        assertTrue(store.calls.contains("disable:10"))
    }

    @Test
    fun aRowOfTheUserIsNeverChangedAndIsReported() {
        store.rows = listOf(FolderRow(1, camera, MediaFolderType.IMAGE, "a@h", false))
        val result = controller.enableMedia()
        assertTrue(store.calls.none { it.endsWith(":1") })
        assertFalse(store.rows.first { it.id == 1L }.enabled)
        assertFalse(1L in state.read().owned)
        assertEquals(listOf("DCIM/Camera"), result.leftAlone)
        controller.disableMedia()
        assertTrue(store.calls.none { it.endsWith(":1") })
    }

    @Test
    fun theTwoRowsOfOneFolderGiveOneResultLine() {
        devices.media = listOf(
            MediaFolderInfo(camera, MediaFolderType.IMAGE),
            MediaFolderInfo(camera, MediaFolderType.VIDEO)
        )
        val result = controller.enableMedia()
        assertEquals(2, result.rowsToStart.size)
        assertEquals(1, result.lines.size)
    }

    @Test
    fun autoAddUsesTheFoldersItWasGivenAndDoesNotScanThePhoneAgain() {
        controller.enableMedia()
        val reading = object : DeviceFolders {
            var reads = 0

            override fun mediaFolders(): List<MediaFolderInfo> {
                reads++
                return emptyList()
            }

            override fun documentFolders(): List<String> = emptyList()
        }
        val scanning = BackupController("a@h", reading, store, state, "Pixel 8")
        val found = listOf(MediaFolderInfo("/storage/emulated/0/Pictures/Screenshots", MediaFolderType.IMAGE))
        val added = scanning.autoAdd(detected = found)
        assertEquals(1, added.rowsToStart.size)
        assertEquals(0, reading.reads)
    }
}
