/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.work.WorkManager
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.datamodel.UploadsStorageManager.UploadStatus
import com.owncloud.android.db.UploadResult
import com.owncloud.android.ui.activity.UploadListActivity
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.backup.BackupControllers
import org.nourax.nodrive.backup.BackupDefaults
import org.nourax.nodrive.backup.BackupState
import org.nourax.nodrive.backup.BackupTestSupport
import java.io.File

/**
 * The real thing against the throwaway Nextcloud of the CI job: the Smart rule in the upload path, the one grouped
 * notification and "Resolve all conflicts…" with each choice. The files on the phone are checked by SHA-256 before and
 * after every case: they must never change. The app password is made for these tests only.
 */
class ConflictsUploadIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = ConflictsTestSupport()
    private val backup = support.backup
    private val created = mutableListOf<Uri>()
    private val phoneHashes = mutableMapOf<File, String>()
    private var account: String? = null
    private var startedRows: List<Long> = emptyList()

    private val acct: String get() = checkNotNull(account)

    @Before
    fun requireServer() {
        assumeTrue("needs the CI test server", support.hasServer)
        account = support.addServerAccount().name
    }

    @After
    fun tearDown() {
        NotificationManagerCompat.from(backup.context).cancelAll()
        created.forEach { backup.deleteMedia(it) }
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
        account?.let { BackupState.forget(backup.context, it) }
    }

    // ---- 1. Smart

    @Test
    fun smartCountsTheSameSizeAsBackedUpAndKeepsBothForAnotherSize() {
        assertEquals("Smart is the default", ConflictChoice.SMART, support.settings().choice(acct))
        val same = phoneFile("nodrive-cf-same.jpg")
        val diff = phoneFile("nodrive-cf-diff.jpg")
        val folder = support.cameraRemoteFolder(acct)
        support.putOnServer("$folder/${same.name}", same)
        support.putOnServer("$folder/${diff.name}", support.otherBytes("cf-diff-old.bin"))
        val sameEtag = support.etagOnServer("$folder/${same.name}")
        val diffEtag = support.etagOnServer("$folder/${diff.name}")
        val oldDiffSize = support.sizeOnServer("$folder/${diff.name}")

        val rows = startBackup()

        backup.waitFor("the same-size file counted as backed up", UPLOAD_TIMEOUT_MILLIS) {
            support.uploadOf("$folder/${same.name}")?.takeIf {
                it.status == UploadStatus.UPLOAD_SUCCEEDED.value && it.lastResult == UploadResult.SKIPPED.value
            }
        }
        val copy = backup.waitFor("the second copy of the other-size file", UPLOAD_TIMEOUT_MILLIS) {
            support.sizeOnServer("$folder/nodrive-cf-diff (2).jpg")
        }
        stopBackup(rows)

        assertEquals("the second copy has the size of the phone file", diff.length(), copy)
        assertEquals("the old copy is unchanged", oldDiffSize, support.sizeOnServer("$folder/${diff.name}"))
        assertEquals(diffEtag, support.etagOnServer("$folder/${diff.name}"))
        assertEquals("the same-size file was not sent again", sameEtag, support.etagOnServer("$folder/${same.name}"))
        assertNull("no copy of the same-size file", support.remoteFile("$folder/nodrive-cf-same (2).jpg"))
        assertEquals("no conflict waits", 0, support.waitingConflicts(acct))
        assertTrue("no conflict notification", support.summaries().isEmpty())
        assertPhoneFilesUnchanged()
    }

    // ---- 2. One grouped notification

    @Test
    fun threeConflictsGiveOneSummaryNotificationAndNoPerFileOne() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = createClashes(folder, "nodrive-cf-note-${backup.look}", count = 3, sameSize = 0)

        val rows = startBackup()
        backup.waitFor("three waiting conflicts", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.waitingConflicts(acct) == clashes.size }
        }
        backup.waitFor("the summary says three", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.summaries().singleOrNull()?.let { support.title(it) } == "3 files are already in NoDrive" }
        }
        val summary = support.summaries().single()
        assertTrue("Resolve all is an action", "Resolve all" in support.actionTitles(summary))
        val perFile = support.activeNotifications().filter {
            it.tag == null && it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ==
                "File upload conflict"
        }
        assertTrue("no per-file notification: ${perFile.map { it.id }}", perFile.isEmpty())
        assertTrue(
            "upstream's app-wide conflict notification (id 112) is replaced by the summary",
            support.activeNotifications().none { it.tag == null && it.id == UPSTREAM_APP_WIDE_CONFLICT_ID }
        )
        support.shadeScreenshot("notification")
        stopBackup(rows)
        assertPhoneFilesUnchanged()
    }

    // ---- 3. Resolve all conflicts…  (one test per choice; the backup stays on, because switching it off would also
    // drop the queued upload rows of its folder, and these rows are what is being resolved)

    @Test
    fun resolveAllKeepBothUploadsACopyNextToEveryOldFile() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-both-${backup.look}", count = 2, sameSize = 0)
        resolveAll("Keep both", first = true)
        backup.waitFor("both copies of every file", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { clashes.all { support.sizeOnServer("$folder/${it.base} (2).jpg") == it.phone.length() } }
        }
        clashes.forEach { assertEquals("old copy stays", OTHER_SIZE, support.sizeOnServer("$folder/${it.base}.jpg")) }
        assertResolved("keep both")
    }

    @Test
    fun resolveAllKeepTheNoDriveCopyChangesNothingOnTheDrive() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-nodrive-${backup.look}", count = 2, sameSize = 0)
        val etags = clashes.associateWith { support.etagOnServer("$folder/${it.base}.jpg") }
        resolveAll("Keep the NoDrive copy")
        backup.waitFor("no waiting conflict", UPLOAD_TIMEOUT_MILLIS) { true.takeIf { support.waitingConflicts(acct) == 0 } }
        clashes.forEach {
            assertEquals("server file unchanged", etags[it], support.etagOnServer("$folder/${it.base}.jpg"))
            assertNull("no extra copy", support.remoteFile("$folder/${it.base} (2).jpg"))
            val row = support.uploadOf("$folder/${it.base}.jpg")
            assertNotNull(row)
            assertEquals(UploadStatus.UPLOAD_SUCCEEDED.value, row!!.status)
            assertEquals(UploadResult.SKIPPED.value, row.lastResult)
        }
        assertResolved("keep the nodrive copy")
    }

    @Test
    fun resolveAllKeepThePhoneCopyReplacesTheDriveFile() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-phone-${backup.look}", count = 2, sameSize = 0)
        resolveAll("Keep the phone copy")
        backup.waitFor("the phone copy on the server", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { clashes.all { support.sizeOnServer("$folder/${it.base}.jpg") == it.phone.length() } }
        }
        clashes.forEach { assertNull("no extra copy", support.remoteFile("$folder/${it.base} (2).jpg")) }
        assertResolved("keep the phone copy")
    }

    @Test
    fun resolveAllSmartSkipsTheSameSizeAndKeepsBothForTheRest() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-smart-${backup.look}", count = 2, sameSize = 1)
        val same = clashes.first { it.sameSize }
        val other = clashes.first { !it.sameSize }
        val sameEtag = support.etagOnServer("$folder/${same.base}.jpg")
        resolveAll("Smart (recommended)")
        backup.waitFor("the second copy of the other-size file", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.sizeOnServer("$folder/${other.base} (2).jpg") == other.phone.length() }
        }
        assertResolved("smart")
        assertEquals("the same-size file was not sent again", sameEtag, support.etagOnServer("$folder/${same.base}.jpg"))
        assertNull("no copy of the same-size file", support.remoteFile("$folder/${same.base} (2).jpg"))
        assertEquals("the old copy of the other file stays", OTHER_SIZE, support.sizeOnServer("$folder/${other.base}.jpg"))
    }

    // The review's blocking finding: an account choice must never replace the policy a Resolve all gave an upload.
    // Upstream's Auto upload takes a waiting conflict up again as soon as its folder's policy is no longer Ask, and
    // would then apply the new setting by itself; so these cases switch Auto upload off for the folder first (a
    // disabled folder is skipped by its job), change the setting, and only then use Resolve all.

    @Test
    fun resolveAllSmartNeverOverwritesWhenTheSettingIsKeepThePhoneCopy() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-b1a-${backup.look}", count = 1, sameSize = 0)
        val etags = clashes.associateWith { support.etagOnServer("$folder/${it.base}.jpg") }
        pauseAutoUpload()
        support.settings().setChoice(acct, ConflictChoice.KEEP_PHONE)
        resolveAll("Smart (recommended)")
        backup.waitFor("the second copy of the file", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { clashes.all { support.sizeOnServer("$folder/${it.base} (2).jpg") == it.phone.length() } }
        }
        assertResolved("smart with the setting on keep the phone copy")
        clashes.forEach {
            assertEquals("the old copy is unchanged", etags[it], support.etagOnServer("$folder/${it.base}.jpg"))
            assertEquals(OTHER_SIZE, support.sizeOnServer("$folder/${it.base}.jpg"))
        }
    }

    @Test
    fun resolveAllKeepThePhoneCopyIsNotIgnoredWhenTheSettingIsKeepTheNoDriveCopy() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-b1b-${backup.look}", count = 1, sameSize = 0)
        pauseAutoUpload()
        support.settings().setChoice(acct, ConflictChoice.KEEP_NODRIVE)
        resolveAll("Keep the phone copy")
        backup.waitFor("the phone copy on the server", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { clashes.all { support.sizeOnServer("$folder/${it.base}.jpg") == it.phone.length() } }
        }
        assertResolved("keep the phone copy with the setting on keep the NoDrive copy")
    }

    // ---- 3b. The review's last should-fix: upstream's Auto upload reads its folder row when a run begins and writes the
    // whole row back when it ends. A choice made in between is undone by that write. These cases reproduce exactly that
    // with upstream's own provider (the stale object is read before the switch and written after it), then start a run.

    @Test
    fun switchingFromKeepThePhoneCopyToSmartDuringARunNeverOverwritesTheDriveFile() {
        val folder = support.cameraRemoteFolder(acct)
        val clash = createClashes(folder, "nodrive-cf-stale-a-${backup.look}", count = 1, sameSize = 0).single()
        val etag = support.etagOnServer("$folder/${clash.base}.jpg")
        val rows = switchWithAStaleWrite(ConflictChoice.KEEP_PHONE)

        startRun(rows)
        backup.waitFor("the second copy of the other-size file", UPLOAD_TIMEOUT_MILLIS) {
            support.sizeOnServer("$folder/${clash.base} (2).jpg")
        }
        backup.waitForAutoUploadToStop(rows, UPLOAD_TIMEOUT_MILLIS)

        assertEquals("the Drive file was not replaced", etag, support.etagOnServer("$folder/${clash.base}.jpg"))
        assertEquals(OTHER_SIZE, support.sizeOnServer("$folder/${clash.base}.jpg"))
        assertOwnedRowsHold(ConflictChoice.SMART)
        assertEquals(0, support.waitingConflicts(acct))
        assertPhoneFilesUnchanged()
    }

    @Test
    fun switchingFromKeepBothToSmartDuringARunStillCountsTheSameSizeAsBackedUp() {
        val folder = support.cameraRemoteFolder(acct)
        val clash = createClashes(folder, "nodrive-cf-stale-b-${backup.look}", count = 1, sameSize = 1).single()
        val etag = support.etagOnServer("$folder/${clash.base}.jpg")
        val rows = switchWithAStaleWrite(ConflictChoice.KEEP_BOTH)

        startRun(rows)
        backup.waitFor("the same-size file counted as backed up", UPLOAD_TIMEOUT_MILLIS) {
            support.uploadOf("$folder/${clash.base}.jpg")?.takeIf {
                it.status == UploadStatus.UPLOAD_SUCCEEDED.value && it.lastResult == UploadResult.SKIPPED.value
            }
        }
        backup.waitForAutoUploadToStop(rows, UPLOAD_TIMEOUT_MILLIS)

        assertNull("no duplicate of the same-size file", support.remoteFile("$folder/${clash.base} (2).jpg"))
        assertEquals(etag, support.etagOnServer("$folder/${clash.base}.jpg"))
        assertOwnedRowsHold(ConflictChoice.SMART)
        assertPhoneFilesUnchanged()
    }

    // ---- 4. The notification action and the empty case

    @Test
    fun theNotificationActionOpensTheDialogForItsAccountAndResolves() {
        support.settings().setChoice(acct, ConflictChoice.ASK)
        val folder = support.cameraRemoteFolder(acct)
        val clashes = waitForConflicts(folder, "nodrive-cf-action-${backup.look}", count = 2, sameSize = 0)
        ActivityScenario.launch<ResolveAllActivity>(ResolveAll.intent(backup.context, acct)).use {
            chooseAndApply("Keep both", first = false)
        }
        backup.waitFor("both copies of every file", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { clashes.all { support.sizeOnServer("$folder/${it.base} (2).jpg") == it.phone.length() } }
        }
        assertResolved("the notification action")
    }

    @Test
    fun resolveAllForAnAccountWithoutConflictsSaysSoAndChangesNothing() {
        ActivityScenario.launch<ResolveAllActivity>(ResolveAll.intent(backup.context, "nobody@example.invalid")).use {
            backup.waitFor("the message", UPLOAD_TIMEOUT_MILLIS) {
                onView(withText(NO_CONFLICTS)).check(matches(isDisplayed()))
                true
            }
            onView(withText("OK")).perform(click())
        }
        assertEquals(0, support.waitingConflicts(acct))
    }

    // ---- helpers

    private class Clash(val base: String, val phone: File, val sameSize: Boolean)

    /** Files on the phone, and a file of the same name on the Drive: of the same bytes ([sameSize] of them) or of 37 bytes. */
    private fun createClashes(folder: String, prefix: String, count: Int, sameSize: Int): List<Clash> =
        (1..count).map { index ->
            val base = "$prefix-$index"
            val phone = phoneFile("$base.jpg")
            val same = index <= sameSize
            support.putOnServer("$folder/$base.jpg", if (same) phone else support.otherBytes("$base.bin"))
            Clash(base, phone, same)
        }

    /**
     * Makes the clashes, runs the backup and waits until every one waits as a conflict (the choice is Ask). The backup
     * stays on afterwards.
     */
    private fun waitForConflicts(folder: String, prefix: String, count: Int, sameSize: Int): List<Clash> {
        val clashes = createClashes(folder, prefix, count, sameSize)
        val rows = startBackup()
        startedRows = rows
        backup.waitFor("$count waiting conflicts", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.waitingConflicts(acct) == count }
        }
        backup.waitForAutoUploadToStop(rows, UPLOAD_TIMEOUT_MILLIS)
        return clashes
    }

    /** Auto upload of the folders of this test stops taking rows up (its job ends at once for a disabled folder). */
    private fun pauseAutoUpload() {
        startedRows.forEach { check(backup.provider.updateSyncedFolderEnabled(it, false) > 0) { "row $it not paused" } }
        backup.waitForAutoUploadToStop(startedRows, UPLOAD_TIMEOUT_MILLIS)
    }

    private fun assertResolved(what: String) {
        backup.waitFor("no waiting conflict after $what", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.waitingConflicts(acct) == 0 }
        }
        backup.waitFor("the summary notification is gone after $what", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { support.summaries().isEmpty() }
        }
        assertPhoneFilesUnchanged()
    }

    /**
     * The Uploads screen, the overflow menu, "Resolve all conflicts…", one choice, Apply. If the dialog says there is
     * nothing to resolve while conflicts wait (a background run of Auto upload takes the rows up again for a moment),
     * it is closed and the whole way is taken again, up to [RESOLVE_ATTEMPTS] times.
     */
    private fun resolveAll(choiceText: String, first: Boolean = false) {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            val nothing = ActivityScenario.launch(UploadListActivity::class.java).use {
                backup.sleepForAnimation()
                openActionBarOverflowOrOptionsMenu(backup.context)
                backup.waitFor("the menu item", UPLOAD_TIMEOUT_MILLIS) {
                    onView(withText("Resolve all conflicts…")).check(matches(isDisplayed()))
                    true
                }
                if (first) support.screenshot("uploads-menu")
                onView(withText("Resolve all conflicts…")).perform(click())
                val message = backup.waitFor("the dialog or the no-conflicts message", UPLOAD_TIMEOUT_MILLIS) {
                    when {
                        isShown(NO_CONFLICTS) -> NO_CONFLICTS
                        isShown("Apply") -> "choices"
                        else -> null
                    }
                }
                if (message == NO_CONFLICTS) {
                    onView(withText("OK")).perform(click())
                    true
                } else {
                    chooseAndApply(choiceText, first)
                    false
                }
            }
            if (!nothing) return
            val current = UserAccountManagerImpl.fromContext(backup.context).user.accountName
            android.util.Log.w(
                "ConflictsUploadIT",
                "attempt ${attempt + 1}: nothing to resolve; test count ${support.waitingConflicts(acct)}, " +
                    "current account is the test account: ${current == acct}"
            )
            backup.sleepForAnimation()
        }
        fail(
            "Resolve all said \"nothing to resolve\" $RESOLVE_ATTEMPTS times; waiting conflicts of the test account: " +
                "${support.waitingConflicts(acct)}"
        )
    }

    private fun isShown(text: String): Boolean = try {
        onView(withText(text)).check(matches(isDisplayed()))
        true
    } catch (e: NoMatchingViewException) {
        false
    } catch (e: AssertionError) {
        false
    }

    /** The Resolve all dialog is on the screen: checks its choices, picks one and taps Apply. */
    private fun chooseAndApply(choiceText: String, first: Boolean) {
        backup.waitFor("the dialog", UPLOAD_TIMEOUT_MILLIS) {
            onView(withText(containsString("Skip it if it's the same size, otherwise keep both"))).check(matches(isDisplayed()))
            true
        }
        listOf("Keep the NoDrive copy", "Keep the phone copy", "Keep both").forEach {
            onView(withText(it)).check(matches(isDisplayed()))
        }
        val label = if (choiceText.startsWith("Smart")) containsString("Smart (recommended)") else containsString(choiceText)
        onView(withText(label)).perform(click())
        backup.sleepForAnimation()
        if (first) support.screenshot("resolve-all")
        onView(withText("Apply")).perform(click())
    }

    /**
     * Makes the backup's rows without running them, gives them the choice [from], lets an "Auto upload run that began
     * then" keep its copy of the row, switches to Smart, and lets that run end: it writes its old row back, exactly as
     * upstream does. Checks that the row really is stale before the test goes on.
     */
    private fun switchWithAStaleWrite(from: ConflictChoice): List<Long> {
        val rows = BackupControllers.create(backup.context, backup.provider, ClockImpl(), acct)
            .enableMedia(BackupDefaults(wifiOnly = false)).rowsToStart
        assertTrue("a row for the camera folder", rows.isNotEmpty())
        startedRows = rows
        WorkManager.getInstance(backup.context).cancelAllWork().result.get()
        backup.waitForAutoUploadToStop(rows, UPLOAD_TIMEOUT_MILLIS)
        support.settings().setChoice(acct, from)
        val stale = rows.map { checkNotNull(backup.provider.getSyncedFolderByID(it)) }
        support.settings().setChoice(acct, ConflictChoice.SMART)
        stale.forEach { backup.provider.updateSyncFolder(it) }
        val staleValue = from.upstreamPolicy().serialize()
        assertTrue(
            "the run's write-back made the rows stale",
            backup.rows().filter { it.id in rows }.all { it.nameCollisionPolicyInt == staleValue }
        )
        return rows
    }

    private fun startRun(rows: List<Long>) {
        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(backup.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(backup.context)
        )
        rows.forEach { id ->
            jobs.startAutoUpload(checkNotNull(backup.provider.getSyncedFolderByID(id)), overridePowerSaving = true)
        }
    }

    private fun assertOwnedRowsHold(choice: ConflictChoice) {
        val wanted = choice.upstreamPolicy().serialize()
        val owned = backup.state(acct).read().owned
        val held = backup.rows().filter { it.id in owned }.map { it.nameCollisionPolicyInt }
        assertTrue("owned rows hold $wanted, they hold $held", held.isNotEmpty() && held.all { it == wanted })
    }

    private fun phoneFile(name: String): File {
        created += backup.insertJpeg(name)
        val file = backup.cameraFolder().resolve(name)
        assertTrue("the phone file exists", file.exists())
        phoneHashes[file] = support.sha256(file)
        return file
    }

    private fun assertPhoneFilesUnchanged() {
        phoneHashes.forEach { (file, hash) ->
            assertTrue("${file.name} is still on the phone", file.exists())
            assertEquals("${file.name} is unchanged", hash, support.sha256(file))
        }
    }

    private fun startBackup(): List<Long> {
        val controller = BackupControllers.create(backup.context, backup.provider, ClockImpl(), acct)
        val rows = controller.enableMedia(BackupDefaults(wifiOnly = false)).rowsToStart
        assertTrue("a row for the camera folder", rows.isNotEmpty())
        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(backup.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(backup.context)
        )
        rows.forEach { id ->
            jobs.startAutoUpload(checkNotNull(backup.provider.getSyncedFolderByID(id)), overridePowerSaving = true)
        }
        return rows
    }

    private fun stopBackup(rows: List<Long>) {
        BackupControllers.create(backup.context, backup.provider, ClockImpl(), acct).disableMedia()
        backup.waitForAutoUploadToStop(rows, UPLOAD_TIMEOUT_MILLIS)
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MILLIS = 300_000L
        const val OTHER_SIZE = 37L
        const val UPSTREAM_APP_WIDE_CONFLICT_ID = 112
        const val RESOLVE_ATTEMPTS = 3
        const val NO_CONFLICTS = "There are no conflicts to resolve."
    }
}
