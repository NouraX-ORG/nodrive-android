/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.launchActivity
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.test.TestActivity
import com.owncloud.android.AbstractIT
import com.owncloud.android.R
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.resources.status.OCCapability
import com.owncloud.android.ui.adapter.OCFileListAdapter
import com.owncloud.android.ui.fragment.OCFileListFragment
import com.owncloud.android.utils.MimeType
import com.owncloud.android.utils.theme.CapabilityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The file list's "Open in Notes" card opens the upstream Notes app, or its store page when that app
 * is missing. NoDrive must never show it, also when the server reports a Notes folder (sweep round 2, U1).
 */
class NoDriveOpenInNotesIT : AbstractIT() {
    private var savedNotesFolderPath: String? = null

    @Before
    fun serverHasNotesFolder() {
        val capability = fileDataStorageManager.getCapability(user.accountName)
        savedNotesFolderPath = capability.notesFolderPath
        saveNotesFolderPath(capability, NOTES_FOLDER)
        AppPreferencesImpl.fromContext(targetContext).setShowEcosystemApps(true)

        val root = storageManager.getFileByEncryptedRemotePath(OCFile.ROOT_PATH)
        val notes = OCFile("$NOTES_FOLDER/").apply {
            remoteId = "00000101"
            mimeType = MimeType.DIRECTORY
            parentId = root.fileId
        }
        assertTrue(storageManager.saveFile(notes))
        val note = OCFile("$NOTES_FOLDER/$NOTE").apply {
            remoteId = "00000102"
            mimeType = "text/markdown"
            parentId = notes.fileId
        }
        assertTrue(storageManager.saveFile(note))
    }

    @After
    fun restoreNotesFolder() {
        saveNotesFolderPath(fileDataStorageManager.getCapability(user.accountName), savedNotesFolderPath)
    }

    @Test
    fun notesFolderShowsNoOpenInNotesCard() {
        // Everything upstream needs to show the card holds; only the NoDrive hook can hide it.
        assertEquals(NOTES_FOLDER, CapabilityUtils.getCapability(user, targetContext).notesFolderPath)
        assertTrue(AppPreferencesImpl.fromContext(targetContext).isShowEcosystemApps)
        assertTrue(targetContext.resources.getBoolean(R.bool.is_branded_client))

        launchActivity<TestActivity>().use { scenario ->
            var adapter: OCFileListAdapter? = null
            scenario.onActivity { activity ->
                val fragment = OCFileListFragment()
                activity.addFragment(fragment)
                activity.supportFragmentManager.executePendingTransactions()
                val folder = activity.storageManager.getFileByDecryptedRemotePath("$NOTES_FOLDER/")
                assertTrue("$NOTES_FOLDER/ missing", folder != null && folder.isFolder)
                fragment.listDirectory(folder, false)
                adapter = fragment.adapter
            }
            waitUntilListed(scenario, { adapter }, NOTE)

            scenario.onActivity {
                assertFalse("the file list shows the Open in Notes card", adapter!!.shouldShowHeader())
            }
            onView(withId(R.id.open_in)).check(doesNotExist())
        }
    }

    private fun saveNotesFolderPath(capability: OCCapability, path: String?) {
        capability.accountName = user.accountName
        capability.notesFolderPath = path
        fileDataStorageManager.saveCapabilities(capability)
        CapabilityUtils.updateCapability(capability)
    }

    private fun waitUntilListed(
        scenario: ActivityScenario<TestActivity>,
        adapter: () -> OCFileListAdapter?,
        name: String
    ) {
        val deadline = SystemClock.uptimeMillis() + LIST_TIMEOUT_MS
        var listed = false
        while (!listed && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { listed = adapter()?.files?.any { it.fileName == name } == true }
            if (!listed) SystemClock.sleep(POLL_MS)
        }
        assertTrue("$name is not listed in $NOTES_FOLDER", listed)
    }

    companion object {
        private const val NOTES_FOLDER = "/Notes"
        private const val NOTE = "shopping.md"
        private const val LIST_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 200L
    }
}
