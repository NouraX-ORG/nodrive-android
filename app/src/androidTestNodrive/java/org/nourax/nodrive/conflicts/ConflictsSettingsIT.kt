/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.net.Uri
import android.os.Environment
import android.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.PreferenceMatchers.withKey
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.R
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.ui.activity.SettingsActivity
import com.owncloud.android.ui.activity.SyncedFoldersActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.backup.BackupControllers
import org.nourax.nodrive.backup.BackupDefaults
import org.nourax.nodrive.backup.BackupState
import org.nourax.nodrive.backup.BackupTestSupport

/** "If a file is already in NoDrive": the Settings row, its dialog and the per-folder Auto upload dialog. No server. */
class ConflictsSettingsIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = ConflictsTestSupport()
    private val backup = support.backup
    private val created = mutableListOf<Uri>()
    private lateinit var account: String
    private var userRowId = -1L

    private val ask = NameCollisionPolicy.ASK_USER.serialize()

    @Before
    fun setUp() {
        account = backup.addLocalAccount().name
        created += backup.insertJpeg(backup.newJpegName())
        // One folder the backup owns, and one Auto upload folder of the user (never touched by the setting).
        BackupControllers.create(backup.context, backup.provider, ClockImpl(), account)
            .enableMedia(BackupDefaults(wifiOnly = true))
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        userRowId = backup.provider.storeSyncedFolder(
            SyncedFolder(
                downloads.absolutePath,
                "/Mine/Downloads",
                true,
                false,
                false,
                false,
                account,
                2,
                ask,
                true,
                System.currentTimeMillis(),
                MediaFolderType.CUSTOM,
                false,
                SubFolderRule.YEAR_MONTH,
                false,
                SyncedFolder.NOT_SCANNED_YET
            )
        )
        assertTrue("the user's row was stored", userRowId > 0)
    }

    @After
    fun tearDown() {
        created.forEach { backup.deleteMedia(it) }
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
        BackupState.forget(backup.context, account)
    }

    private fun ownedRows() = backup.rows().filter { it.id in backup.state(account).read().owned }

    private fun waitForOwnedPolicy(policy: Int) = backup.waitFor("every owned row at policy $policy", TIMEOUT_MILLIS) {
        true.takeIf { ownedRows().isNotEmpty() && ownedRows().all { it.nameCollisionPolicyInt == policy } }
    }

    @Test
    fun theSettingShowsSmartByDefaultAndChangesOnlyTheOwnedFolders() {
        assertTrue("the backup made an owned row", ownedRows().isNotEmpty())
        assertTrue("new owned rows carry the Smart carrier", ownedRows().all { it.nameCollisionPolicyInt == ask })

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                val item = activity.findPreference(ConflictSettingsItem.KEY) as Preference?
                assertNotNull("the row is in the Backup section", item)
                assertEquals("If a file is already in NoDrive", item!!.title.toString())
                assertEquals("Smart (recommended)", item.summary.toString())
            }
            backup.sleepForAnimation()
            support.screenshot("setting")

            onData(withKey(ConflictSettingsItem.KEY)).perform(click())
            backup.waitFor("the choices", TIMEOUT_MILLIS) {
                onView(withText(containsString("Skip it if it's the same size, otherwise keep both"))).check(matches(isDisplayed()))
                true
            }
            listOf("Keep the NoDrive copy", "Keep the phone copy", "Keep both", "Ask me every time").forEach {
                onView(withText(it)).check(matches(isDisplayed()))
            }
            backup.sleepForAnimation()
            support.screenshot("setting-dialog")

            onView(withText("Keep both")).perform(click())
            waitForOwnedPolicy(NameCollisionPolicy.RENAME.serialize())
            assertEquals("the user's row is untouched", ask, backup.rows().first { it.id == userRowId }.nameCollisionPolicyInt)
            backup.waitFor("the summary", TIMEOUT_MILLIS) {
                var summary = ""
                scenario.onActivity { activity ->
                    @Suppress("DEPRECATION")
                    summary = activity.findPreference(ConflictSettingsItem.KEY).summary.toString()
                }
                true.takeIf { summary == "Keep both" }
            }
            assertEquals(ConflictChoice.KEEP_BOTH, support.settings().choice(account))

            onData(withKey(ConflictSettingsItem.KEY)).perform(click())
            backup.waitFor("the choices again", TIMEOUT_MILLIS) {
                onView(withText(containsString("Skip it if it's the same size, otherwise keep both"))).check(matches(isDisplayed()))
                true
            }
            onView(withText(containsString("Smart (recommended)"))).perform(click())
            waitForOwnedPolicy(ask)
            assertEquals(ConflictChoice.SMART, support.settings().choice(account))
            assertEquals("the user's row is untouched", ask, backup.rows().first { it.id == userRowId }.nameCollisionPolicyInt)
        }
    }

    @Test
    fun theFolderDialogExplainsForOwnedFoldersAndKeepsUpstreamsPickerForOthers() {
        val cameraPath = backup.cameraFolder().absolutePath
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        ActivityScenario.launch(SyncedFoldersActivity::class.java).use { scenario ->
            openFolderDialog(scenario, cameraPath, MediaFolderType.IMAGE)
            onView(withId(R.id.setting_instant_name_collision_policy_summary)).check(matches(withText("Smart (recommended)")))
            backup.sleepForAnimation()
            onView(withId(R.id.setting_instant_name_collision_policy_container)).perform(scrollTo(), click())
            backup.waitFor("the explanation", TIMEOUT_MILLIS) {
                onView(withText(containsString("Settings › Backup › If a file is already in NoDrive"))).check(matches(isDisplayed()))
                true
            }
            backup.sleepForAnimation()
            support.screenshot("folder-dialog")
            onView(withText("OK")).perform(click())
            pressBack()

            openFolderDialog(scenario, downloads, MediaFolderType.CUSTOM)
            onView(withId(R.id.setting_instant_name_collision_policy_summary)).check(matches(withText("Ask me every time")))
            onView(withId(R.id.setting_instant_name_collision_policy_container)).perform(scrollTo(), click())
            backup.waitFor("upstream's picker", TIMEOUT_MILLIS) {
                onView(withText("What to do if the file already exists?")).check(matches(isDisplayed()))
                onView(withText("Overwrite remote version")).check(matches(isDisplayed()))
                true
            }
            pressBack()
        }
        assertEquals(ask, backup.rows().first { it.id == userRowId }.nameCollisionPolicyInt)
    }

    private fun openFolderDialog(scenario: ActivityScenario<SyncedFoldersActivity>, path: String, type: MediaFolderType) {
        val section = backup.waitFor("the folder $path in Auto upload", TIMEOUT_MILLIS) {
            var found = -1
            scenario.onActivity { activity -> found = activity.adapter.getSectionByLocalPathAndType(path, type.id) }
            found.takeIf { it >= 0 }
        }
        scenario.onActivity { activity -> activity.onSyncFolderSettingsClick(section, activity.adapter.get(section)) }
        backup.waitFor("the folder dialog", TIMEOUT_MILLIS) {
            onView(withId(R.id.setting_instant_name_collision_policy_container)).check(matches(isDisplayed()))
            true
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}
