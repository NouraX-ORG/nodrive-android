/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.ContentResolver
import android.os.Bundle
import android.preference.Preference
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.nextcloud.test.GrantTestPermissionRule
import com.owncloud.android.R
import com.owncloud.android.ui.activity.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.devicesync.ui.DeviceSyncActivity
import org.nourax.nodrive.devicesync.ui.DeviceSyncModel
import org.nourax.nodrive.devicesync.ui.DeviceSyncSettingsSection

/**
 * Off until the person switches it on: the Settings row says Off, the phone's sync system knows the two adapters but the account is not
 * syncable, a sync before the backup does nothing, and the first two screens look right. No server is needed.
 */
class DeviceSyncSettingsIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val support = DeviceSyncTestSupport()

    @Before
    fun setUp() {
        support.grantPermissions()
        support.account = support.agenda.addLocalAccount()
        DeviceSyncAccounts.forget(support.context, support.account.name)
    }

    @After
    fun tearDown() {
        support.cleanUp()
    }

    @Test
    fun theSettingsRowSaysOffByDefault() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                val row = activity.findPreference(DeviceSyncSettingsSection.KEY_ROW) as Preference
                assertEquals(activity.getString(R.string.nodrive_device_sync_title), row.title.toString())
                assertEquals(activity.getString(R.string.nodrive_device_sync_off), row.summary.toString())
            }
            support.agenda.backup.sleepForAnimation()
            support.screenshot("settings")
        }
    }

    @Test
    fun theAdaptersAreBoundButTheAccountIsNotSyncableWhileOff() {
        val account = support.account
        // The phone's first request for a new adapter: ours answers "not syncable" while sync is off.
        for (authority in listOf(DeviceSyncConstants.AUTHORITY_CALENDAR, DeviceSyncConstants.AUTHORITY_CONTACTS)) {
            ContentResolver.requestSync(account, authority, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })
        }
        for (authority in listOf(DeviceSyncConstants.AUTHORITY_CALENDAR, DeviceSyncConstants.AUTHORITY_CONTACTS)) {
            support.agenda.backup.waitFor("$authority initialised by our adapter", WAIT_MILLIS) {
                DeviceSyncControl.isSyncable(support.context, account.name, authority).takeIf { it == 0 }
            }
            assertFalse(DeviceSyncControl.isSyncable(support.context, account.name, authority) == 1)
        }
        // The phone's account settings open the screen of the app: Android takes the settings activity as a class name.
        val ours = ContentResolver.getSyncAdapterTypes().filter {
            it.accountType == support.accountType &&
                it.authority in listOf(DeviceSyncConstants.AUTHORITY_CALENDAR, DeviceSyncConstants.AUTHORITY_CONTACTS)
        }
        assertEquals("both adapters are known to the phone", 2, ours.size)
        for (type in ours) assertEquals(type.authority, "org.nourax.nodrive.devicesync.ui.DeviceSyncActivity", type.settingsActivity)
    }

    @Test
    fun aSyncBeforeTheBackupDoesNothingAndAnOffAccountIsLeftAlone() {
        val name = support.account.name
        assertTrue("off: nothing runs", DeviceSyncRunner.run(support.context, name, setOf("calendar", "contacts")).ok)
        // on, but the backup is not done: the first sync does not start
        DeviceSyncSettings.update(support.context, name, enabled = true)
        val outcome = DeviceSyncRunner.run(support.context, name, setOf("calendar", "contacts"))
        assertFalse(outcome.ok)
        assertEquals("backup_pending", outcome.message)
        assertEquals(0, outcome.added)
    }

    @Test
    fun theIntroAndThePermissionScreens() {
        ActivityScenario.launch(DeviceSyncActivity::class.java).use { scenario ->
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("ds_intro_text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ds_intro_text").assertExists()
            compose.onNodeWithTag("ds_intro_continue").assertExists()
            support.agenda.assertNoServerSoftwareName(compose)
            support.agenda.backup.sleepForAnimation()
            support.screenshot("intro")
            // the permission screen with the phone's answer "no"
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[support.account.name, DeviceSyncModel::class.java]
                model.needPermissions(denied = true)
            }
            compose.waitUntil(LOAD_MILLIS) { compose.onAllNodesWithTag("ds_permissions_denied").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ds_open_settings").assertExists()
            assertNotNull(compose.onAllNodes(hasText("Without access", substring = true)).fetchSemanticsNodes().firstOrNull())
            support.agenda.assertNoServerSoftwareName(compose)
            support.agenda.backup.sleepForAnimation()
            support.screenshot("permissions")
            assertEquals(0, compose.onAllNodesWithTag("ds_intro_continue").fetchSemanticsNodes().size)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 90_000L
        const val LOAD_MILLIS = 30_000L
    }
}
