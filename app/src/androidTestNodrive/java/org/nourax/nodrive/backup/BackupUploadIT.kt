/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import androidx.work.WorkManager
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real thing against the throwaway Nextcloud of the CI job: a photo that is already on the phone when the switch
 * goes on must appear on the server below the backup root, and an on, off, on run sends every file exactly once. The app password is made for this test only.
 */
class BackupUploadIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = BackupTestSupport()
    private val created = mutableListOf<android.net.Uri>()
    private var account: String? = null

    @Before
    fun requireServer() {
        assumeTrue(
            "needs the CI test server",
            support.serverUrl != null && support.userId != null && support.serverToken != null
        )
        account = support.addServerAccount().name
    }

    @After
    fun tearDown() {
        created.forEach { support.deleteMedia(it) }
        support.wipeAllAccountsAndRowsCiEmulatorOnly()
        account?.let { BackupState.forget(support.context, it) }
    }

    @Test
    fun aPhotoThatWasAlreadyOnThePhoneReachesTheServerUnderThePhoneBackupRoot() {
        val name = support.newJpegName()
        created += support.insertJpeg(name)
        val localFile = support.cameraFolder().resolve(name)
        assertTrue("the photo exists before the switch goes on", localFile.exists())

        val controller = BackupControllers.create(
            support.context,
            support.provider,
            ClockImpl(),
            checkNotNull(account)
        )
        val result = controller.enableMedia(BackupDefaults(wifiOnly = false))
        assertTrue("a row was made for the camera folder", result.rowsToStart.isNotEmpty())
        val root = checkNotNull(controller.snapshot().root)
        val remote = "$root/DCIM/Camera/$name"

        startUploads(result.rowsToStart)
        waitForServerFile(remote)
        assertTrue("the photo is still on the phone", localFile.exists())
    }

    @Test
    fun onOffOnUploadsEveryFileExactlyOnce() {
        val first = support.newJpegName()
        created += support.insertJpeg(first)
        val controller = BackupControllers.create(support.context, support.provider, ClockImpl(), checkNotNull(account))

        val firstOn = controller.enableMedia(BackupDefaults(wifiOnly = false))
        val folder = "${checkNotNull(controller.snapshot().root)}/DCIM/Camera"
        startUploads(firstOn.rowsToStart)
        waitForServerFile("$folder/$first")
        val etagBefore = etagOf("$folder/$first")

        val off = controller.disableMedia()
        assertTrue("switching off made no new row", off.rowsToStart.isEmpty())
        support.waitForAutoUploadToStop(firstOn.rowsToStart, UPLOAD_TIMEOUT_MILLIS)
        val second = support.newJpegName()
        created += support.insertJpeg(second)

        val secondOn = controller.enableMedia(BackupDefaults(wifiOnly = false))
        assertEquals("the same rows come back", firstOn.rowsToStart.toSet(), secondOn.rowsToStart.toSet())
        startUploads(secondOn.rowsToStart)
        waitForServerFile("$folder/$second")
        support.waitForAutoUploadToStop(secondOn.rowsToStart, UPLOAD_TIMEOUT_MILLIS)

        assertEquals("the first photo is on the server once", 1, countOnServer(folder, first))
        assertEquals("the second photo is on the server once", 1, countOnServer(folder, second))
        assertEquals("the first photo was not sent again", etagBefore, etagOf("$folder/$first"))
    }

    private fun startUploads(rowIds: List<Long>) {
        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(support.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(support.context)
        )
        rowIds.forEach { id ->
            jobs.startAutoUpload(checkNotNull(support.provider.getSyncedFolderByID(id)), overridePowerSaving = true)
        }
    }

    private fun serverClient() = OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(
        UserAccountManagerImpl.fromContext(support.context).user.toOwnCloudAccount(),
        support.context
    )

    private fun waitForServerFile(remote: String) =
        support.waitFor("$remote on the server", UPLOAD_TIMEOUT_MILLIS) {
            true.takeIf { ReadFileRemoteOperation(remote).execute(serverClient()).isSuccess }
        }

    private fun etagOf(remote: String): String {
        val result = ReadFileRemoteOperation(remote).execute(serverClient())
        assertTrue("$remote is readable", result.isSuccess)
        return checkNotNull((result.data[0] as RemoteFile).etag)
    }

    /** Counts the entries of the folder that start with the file's name without the extension, so "name (2).jpg" counts. */
    private fun countOnServer(folder: String, fileName: String): Int {
        val result = ReadFolderRemoteOperation(folder).execute(serverClient())
        assertTrue("$folder is readable", result.isSuccess)
        val stem = fileName.substringBeforeLast('.')
        return result.data.filterIsInstance<RemoteFile>().count { it.remotePath?.substringAfterLast('/')?.startsWith(stem) == true }
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MILLIS = 300_000L
    }
}
