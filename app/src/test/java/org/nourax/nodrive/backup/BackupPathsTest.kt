/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPathsTest {
    private val root = "/Phone backup/Pixel 8"

    @Test
    fun cameraFolderIsMirroredBelowTheRoot() {
        assertEquals("$root/DCIM/Camera", BackupPaths.remotePath(root, "/storage/emulated/0/DCIM/Camera"))
        assertEquals(
            "$root/Pictures/Screenshots",
            BackupPaths.remotePath(root, "/storage/emulated/0/Pictures/Screenshots")
        )
    }

    @Test
    fun appMediaFolderKeepsItsWholePath() {
        val local = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"
        assertEquals("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", BackupPaths.remotePath(root, local))
    }

    @Test
    fun otherUserProfileOfTheSameVolumeIsMirrored() {
        assertEquals("$root/DCIM", BackupPaths.remotePath(root, "/storage/emulated/10/DCIM/"))
    }

    @Test
    fun sdCardGetsItsOwnFolder() {
        assertEquals("$root/SD card 1234-ABCD/DCIM", BackupPaths.remotePath(root, "/storage/1234-ABCD/DCIM"))
    }

    @Test
    fun unknownVolumeGoesUnderOther() {
        assertEquals("$root/Other/mnt/media/Pics", BackupPaths.remotePath(root, "/mnt/media/Pics"))
    }

    @Test
    fun forbiddenCharactersInAFolderNameBecomeUnderscores() {
        assertEquals("$root/Pictures/a_b_c", BackupPaths.remotePath(root, "/storage/emulated/0/Pictures/a:b*c"))
    }

    @Test
    fun deviceNameIsSanitisedAndNeverEmpty() {
        assertEquals("A_B_C", BackupPaths.deviceFolderName("A/B:C"))
        assertEquals("Pixel 8 (work)", BackupPaths.deviceFolderName("  Pixel 8 (work) "))
        assertEquals("Android phone", BackupPaths.deviceFolderName(""))
        assertEquals("Android phone", BackupPaths.deviceFolderName(null))
        assertEquals("Android phone", BackupPaths.deviceFolderName("   "))
        assertEquals("Android phone", BackupPaths.deviceFolderName("///"))
    }

    @Test
    fun anArabicPhoneNameStaysReadable() {
        assertEquals("هاتف محمد", BackupPaths.deviceFolderName("هاتف محمد"))
        assertEquals("/Phone backup/هاتف محمد", BackupPaths.root(null, "هاتف محمد"))
    }

    @Test
    fun anUrduPhoneNameStaysReadable() {
        assertEquals("میرا فون ۲", BackupPaths.deviceFolderName("میرا فون ۲"))
    }

    @Test
    fun anEmojiPhoneNameStaysReadableAndOnlyInvalidCharactersAreReplaced() {
        assertEquals("Ali's 📱", BackupPaths.deviceFolderName("Ali's 📱"))
        assertEquals("📱", BackupPaths.deviceFolderName("📱"))
        assertEquals("Muhammad's S23", BackupPaths.deviceFolderName("Muhammad's S23"))
        assertEquals("هاتف_محمد", BackupPaths.deviceFolderName("هاتف/محمد"))
    }

    @Test
    fun aNameWithNothingReadableFallsBackToTheModelThenToTheDefault() {
        assertEquals("SM-S911B", BackupPaths.deviceFolderName("///", "SM-S911B"))
        assertEquals("SM-S911B", BackupPaths.deviceFolderName(null, "SM-S911B"))
        assertEquals("Android phone", BackupPaths.deviceFolderName("...", "///"))
        assertEquals("Android phone", BackupPaths.deviceFolderName("_ _", null))
    }

    @Test
    fun aStorageVolumeIsRecognisedAsAVolumeRoot() {
        assertTrue(BackupPaths.isVolumeRoot("/storage/emulated/0"))
        assertTrue(BackupPaths.isVolumeRoot("/storage/emulated/0/"))
        assertTrue(BackupPaths.isVolumeRoot("/storage/1234-ABCD"))
        assertFalse(BackupPaths.isVolumeRoot("/storage/emulated/0/DCIM"))
    }

    @Test
    fun rootIsBuiltFromTheDeviceName() {
        assertEquals("/Phone backup/Pixel 8", BackupPaths.root(null, "Pixel 8"))
        assertEquals("/Phone backup/Android phone", BackupPaths.root("", null))
    }

    @Test
    fun theStoredRootWinsOverARenamedPhone() {
        assertEquals("/Phone backup/Old name", BackupPaths.root("/Phone backup/Old name", "New name"))
    }
}
