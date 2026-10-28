/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedRowsTest {
    private val camera = PolicyRow(1, "a@h", "/storage/emulated/0/DCIM/Camera", "/Phone backup/Pixel/DCIM/Camera", 3)
    private val camera2 = PolicyRow(2, "a@h", "/storage/emulated/0/DCIM/Camera2", "/Phone backup/Pixel/DCIM/Camera2", 3)
    private val other = PolicyRow(3, "b@h", "/storage/emulated/0/DCIM/Camera", "/Phone backup/Pixel/DCIM/Camera", 3)
    private val rows = listOf(camera, camera2, other)
    private val local = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg"
    private val remote = "/Phone backup/Pixel/DCIM/Camera/2026/10/IMG_1.jpg"

    @Test
    fun anOwnedRowOfTheAccountMatches() {
        assertEquals(camera, OwnedRows.find(rows, setOf(1, 2), "a@h", local, remote))
    }

    @Test
    fun aRowTheBackupDoesNotOwnNeverMatches() {
        assertNull(OwnedRows.find(rows, setOf(2), "a@h", local, remote))
        assertNull(OwnedRows.find(rows, emptySet(), "a@h", local, remote))
    }

    @Test
    fun anotherAccountsRowNeverMatches() {
        assertNull(OwnedRows.find(rows, setOf(3), "a@h", local, remote))
        assertEquals(other, OwnedRows.find(rows, setOf(3), "b@h", local, remote))
    }

    @Test
    fun cameraDoesNotMatchCamera2() {
        val local2 = "/storage/emulated/0/DCIM/Camera2/IMG_1.jpg"
        val remote2 = "/Phone backup/Pixel/DCIM/Camera2/IMG_1.jpg"
        assertEquals(camera2, OwnedRows.find(rows, setOf(1, 2), "a@h", local2, remote2))
        assertNull(OwnedRows.find(listOf(camera), setOf(1), "a@h", local2, remote2))
    }

    @Test
    fun bothThePhoneFolderAndTheDriveFolderMustMatch() {
        assertNull(OwnedRows.find(rows, setOf(1), "a@h", local, "/Somewhere else/IMG_1.jpg"))
        assertNull(OwnedRows.find(rows, setOf(1), "a@h", "/storage/emulated/0/Download/IMG_1.jpg", remote))
    }

    @Test
    fun trailingSlashesAndNullsAreHandled() {
        val withSlash = camera.copy(localPath = camera.localPath + "/", remotePath = camera.remotePath + "/")
        assertEquals(withSlash, OwnedRows.find(listOf(withSlash), setOf(1), "a@h", local, remote))
        assertNull(OwnedRows.find(rows, setOf(1), "a@h", null, remote))
        assertNull(OwnedRows.find(rows, setOf(1), "a@h", local, null))
        assertFalse(OwnedRows.isInside("/a/b", ""))
        assertTrue(OwnedRows.isInside("/a/b/c.jpg", "/a/b"))
        assertFalse(OwnedRows.isInside("/a/b", "/a/b"))
    }

    @Test
    fun aPhotoAndAVideoRowOnOnePathGiveTheOwnedOne() {
        val video = camera.copy(id = 9)
        assertEquals(video, OwnedRows.find(listOf(video, camera), setOf(9), "a@h", local, remote))
        assertEquals(camera, OwnedRows.find(listOf(video, camera), setOf(1), "a@h", local, remote))
    }
}
