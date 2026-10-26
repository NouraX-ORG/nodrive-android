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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real thing against the throwaway Nextcloud of the CI job: a photo that is already on the phone when the switch
 * goes on must appear on the server below the backup root. The app password is made for this test only.
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
        support.deleteRowsOfTestAccounts()
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

        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(support.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(support.context)
        )
        result.rowsToStart.forEach { id ->
            jobs.startAutoUpload(checkNotNull(support.provider.getSyncedFolderByID(id)), overridePowerSaving = true)
        }

        val user = UserAccountManagerImpl.fromContext(support.context).user
        support.waitFor("$remote on the server", UPLOAD_TIMEOUT_MILLIS) {
            val client = OwnCloudClientManagerFactory.getDefaultSingleton()
                .getClientFor(user.toOwnCloudAccount(), support.context)
            true.takeIf { ReadFileRemoteOperation(remote).execute(client).isSuccess }
        }
        assertTrue("the photo is still on the phone", localFile.exists())
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MILLIS = 300_000L
    }
}
