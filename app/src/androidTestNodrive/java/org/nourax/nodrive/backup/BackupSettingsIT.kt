/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.preference.Preference
import android.provider.Browser
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.espresso.matcher.PreferenceMatchers.withKey
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.lifecycle.Lifecycle
import com.owncloud.android.R
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.ui.activity.FileDisplayActivity
import com.owncloud.android.ui.activity.SettingsActivity
import com.owncloud.android.ui.activity.SyncedFoldersActivity
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Backup section of Settings, its screens and the first-sign-in offer. No server is contacted. */
class BackupSettingsIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = BackupTestSupport()
    private val created = mutableListOf<Uri>()
    private lateinit var account: String

    @Before
    fun setUp() {
        account = support.addLocalAccount().name
    }

    @After
    fun tearDown() {
        runCatching { Intents.release() }
        StopGoogleBackupActivity.starterFactory = { AndroidIntentStarter(it) }
        created.forEach { support.deleteMedia(it) }
        support.deleteRowsOfTestAccounts()
        BackupState.forget(support.context, account)
    }

    @Test
    fun settingsShowBackupFirstAndTheSwitchesDoTheirWork() {
        val name = support.newJpegName()
        created += support.insertJpeg(name)
        val cameraPath = support.cameraFolder().absolutePath

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                val first = activity.listView.adapter.getItem(0) as Preference
                assertEquals(activity.getString(R.string.nodrive_backup_category), first.title.toString())
                listOf(
                    BackupSettingsSection.KEY_MEDIA,
                    BackupSettingsSection.KEY_DOCS,
                    BackupSettingsSection.KEY_FOLDERS,
                    BackupSettingsSection.KEY_STOP_GOOGLE,
                    BackupSettingsSection.KEY_MOVE_GOOGLE
                ).forEach { key -> assertNotNull("preference $key", activity.findPreference(key)) }
            }
            support.sleepForAnimation()
            support.screenshot("settings")

            onData(withKey(BackupSettingsSection.KEY_MEDIA)).perform(click())
            val setup = support.waitFor("the setup screen", TIMEOUT_MILLIS) { support.resumed<BackupSetupActivity>() }
            waitForDone(setup)
            support.sleepForAnimation()
            support.screenshot("setup-result")
            support.onMain { setup.finish() }
        }

        val row = support.waitFor("an enabled image row for $cameraPath", TIMEOUT_MILLIS) {
            support.rows().firstOrNull { it.localPath == cameraPath && it.type == MediaFolderType.IMAGE && it.isEnabled }
        }
        val root = checkNotNull(support.state(account).read().root)
        assertTrue(root.startsWith("/Phone backup/"))
        assertEquals("$root/DCIM/Camera", row.remotePath)
        assertTrue(row.alsoUploadExistingFiles())
        assertTrue(row.isWifiOnly)
        assertFalse(row.isChargingOnly)
        assertFalse(row.isSubfolderByDate)
        assertEquals(2, row.uploadAction)
        assertEquals(NameCollisionPolicy.ASK_USER.serialize(), row.nameCollisionPolicyInt)
        assertTrue(row.id in support.state(account).read().owned)

        assertAutoUploadShowsTheFolder(cameraPath)

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                val summary = activity.findPreference(BackupSettingsSection.KEY_MEDIA).summary.toString()
                assertTrue(summary, summary.startsWith("On:"))
            }
            onData(withKey(BackupSettingsSection.KEY_MEDIA)).perform(click())
            val stop = support.waitFor("the stop screen", TIMEOUT_MILLIS) { support.resumed<BackupSetupActivity>() }
            waitForDone(stop)
            support.onMain { stop.finish() }
        }
        val after = support.rows().first { it.id == row.id }
        assertFalse("the row is kept but stopped", after.isEnabled)
        assertTrue("the photo is still on the phone", File(cameraPath, name).exists())
        assertFalse(support.state(account).read().mediaOn)
    }

    private fun assertAutoUploadShowsTheFolder(cameraPath: String) {
        ActivityScenario.launch(SyncedFoldersActivity::class.java).use { scenario ->
            support.waitFor("the folder enabled in Auto upload", TIMEOUT_MILLIS) {
                var enabled = false
                scenario.onActivity { activity ->
                    val section = activity.adapter.getSectionByLocalPathAndType(cameraPath, MediaFolderType.IMAGE.id)
                    enabled = section >= 0 && activity.adapter.get(section)?.isEnabled == true
                }
                true.takeIf { enabled }
            }
            support.sleepForAnimation()
            support.screenshot("auto-upload")
        }
    }

    @Test
    fun stopGoogleBackupOpensThePlaceAndSurvivesAMissingTarget() {
        Intents.init()
        intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        ActivityScenario.launch(StopGoogleBackupActivity::class.java).use { scenario ->
            support.sleepForAnimation()
            support.screenshot("stop-google")
            tapOpenOfCard(scenario, R.string.nodrive_backup_place_device_title)
            support.waitFor("an intent to the backup settings", TIMEOUT_MILLIS) {
                Intents.getIntents().firstOrNull {
                    it.action == GoogleBackupPlaces.BACKUP_SETTINGS_ACTION || it.action == Settings.ACTION_SETTINGS
                }
            }
        }
        Intents.release()

        StopGoogleBackupActivity.starterFactory = { IntentStarter { throw ActivityNotFoundException("nothing here") } }
        ActivityScenario.launch(StopGoogleBackupActivity::class.java).use { scenario ->
            tapOpenOfCard(scenario, R.string.nodrive_backup_place_device_title)
            support.waitFor("the could-not-open message", TIMEOUT_MILLIS) {
                onView(withText(R.string.nodrive_backup_place_failed)).check(matches(isDisplayed()))
                true
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun tapOpenOfCard(scenario: ActivityScenario<StopGoogleBackupActivity>, titleRes: Int) {
        scenario.onActivity { activity ->
            val title = activity.getString(titleRes)
            val cards = activity.findViewById<android.view.ViewGroup>(R.id.nodrive_backup_places)
            val card = (0 until cards.childCount).map(cards::getChildAt).first {
                it.findViewById<TextView>(R.id.nodrive_backup_place_title).text.toString() == title
            }
            card.findViewById<View>(R.id.nodrive_backup_place_open).performClick()
        }
    }

    @Test
    fun moveFromGoogleOpensTakeoutWithOnlyTheAddress() {
        Intents.init()
        intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        ActivityScenario.launch(MoveFromGoogleActivity::class.java).use {
            support.sleepForAnimation()
            support.screenshot("move-google")
            onView(withText(R.string.nodrive_backup_takeout_open)).perform(click())
            val sent = support.waitFor("the Takeout intent", TIMEOUT_MILLIS) {
                Intents.getIntents().firstOrNull { it.data == Uri.parse("https://takeout.google.com") }
            }
            assertEquals(Intent.ACTION_VIEW, sent.action)
            // The Custom Tab library adds the phone's language as Accept-Language; nothing else may travel with it.
            val headers = sent.getBundleExtra(Browser.EXTRA_HEADERS)
            assertTrue(
                "extra headers: ${headers?.keySet()}",
                headers == null || headers.keySet().all { it.equals("Accept-Language", ignoreCase = true) }
            )
            assertTrue(sent.extras?.keySet().orEmpty().none { it.contains("auth", ignoreCase = true) })
            assertTrue(Intents.getIntents().none { it.data?.host?.contains("nourax") == true })
        }
    }

    @Test
    fun theOfferAppearsOnceAfterTheFirstSignIn() {
        BackupOffer.markPending(support.context, account)
        ActivityScenario.launch(FileDisplayActivity::class.java).use {
            support.waitFor("the offer", TIMEOUT_MILLIS) {
                onView(withText(R.string.nodrive_backup_offer_title)).check(matches(isDisplayed()))
                true
            }
            support.sleepForAnimation()
            support.screenshot("offer")
            onView(withText(R.string.nodrive_backup_offer_not_now)).perform(click())
            assertEquals(OfferState.DONE, support.state(account).offer())
        }
        ActivityScenario.launch(FileDisplayActivity::class.java).use {
            support.sleepForAnimation()
            onView(withText(R.string.nodrive_backup_offer_title)).check(doesNotExist())
        }
    }

    private fun waitForDone(setup: BackupSetupActivity) {
        support.waitFor("the setup result", TIMEOUT_MILLIS) {
            true.takeIf {
                support.onMainResult { setup.findViewById<View>(R.id.nodrive_backup_done).visibility == View.VISIBLE }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
    }
}
