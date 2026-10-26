/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.net.Uri
import androidx.work.WorkManager
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What the backup writes into Auto upload (the rows and their field values), a row of the user that it must not
 * touch, and the stop of running uploads when it is switched off. Needs no server: the account points at the phone itself.
 */
class BackupRowsIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = BackupTestSupport()
    private val created = mutableListOf<Uri>()
    private lateinit var account: String

    private fun controller() = BackupControllers.create(support.context, support.provider, ClockImpl(), account)

    @Before
    fun setUp() {
        account = support.addLocalAccount().name
    }

    @After
    fun tearDown() {
        created.forEach { support.deleteMedia(it) }
        support.wipeAllAccountsAndRowsCiEmulatorOnly()
        BackupState.forget(support.context, account)
    }

    @Test
    fun theRowsTheSwitchWritesHaveTheAgreedFieldValues() {
        created += support.insertJpeg(support.newJpegName())
        val result = controller().enableMedia()
        assertTrue(result.rowsToStart.isNotEmpty())
        val root = checkNotNull(support.state(account).read().root)

        val row = support.rows().first {
            it.localPath == support.cameraFolder().absolutePath && it.type == MediaFolderType.IMAGE
        }
        assertEquals("$root/DCIM/Camera", row.remotePath)
        assertEquals(account, row.account)
        assertTrue(row.isEnabled)
        assertTrue("existing files are included", row.alsoUploadExistingFiles())
        assertTrue(row.isWifiOnly)
        assertFalse(row.isChargingOnly)
        assertFalse("no date folders", row.isSubfolderByDate)
        assertEquals(FileUploadWorker.LOCAL_BEHAVIOUR_FORGET, row.uploadAction)
        assertEquals(NameCollisionPolicy.ASK_USER.serialize(), row.nameCollisionPolicyInt)
        assertFalse(row.isHidden)
        assertFalse(row.isExcludeHidden)
        assertTrue(row.id in support.state(account).read().owned)
    }

    @Test
    fun aRowTheUserMadeIsNeverRewrittenAndNeverEnabled() {
        created += support.insertJpeg(support.newJpegName())
        val cameraPath = support.cameraFolder().absolutePath
        val own = SyncedFolder(
            cameraPath,
            "/My own place/Camera",
            false,
            true,
            false,
            true,
            account,
            FileUploadWorker.LOCAL_BEHAVIOUR_DELETE,
            NameCollisionPolicy.OVERWRITE.serialize(),
            false,
            SyncedFolder.EMPTY_ENABLED_TIMESTAMP_MS,
            MediaFolderType.IMAGE,
            false,
            SubFolderRule.YEAR_MONTH,
            false,
            SyncedFolder.NOT_SCANNED_YET
        )
        val id = support.provider.storeSyncedFolder(own)
        val before = fingerprint(id)

        val result = controller().enableMedia()
        assertTrue("the folder is reported as left alone", result.leftAlone.contains("DCIM/Camera"))
        assertEquals("the row is byte for byte the same", before, fingerprint(id))
        assertFalse(id in support.state(account).read().owned)

        controller().disableMedia()
        assertEquals("switching off does not touch it either", before, fingerprint(id))
        assertTrue("the photo is still on the phone", support.cameraFolder().listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun switchingOffStopsTheUploadWorkOfTheRowsItOwns() {
        created += support.insertJpeg(support.newJpegName())
        val on = controller().enableMedia(BackupDefaults(wifiOnly = false))
        assertTrue(on.rowsToStart.isNotEmpty())
        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(support.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(support.context)
        )
        on.rowsToStart.forEach { jobs.startAutoUpload(checkNotNull(support.provider.getSyncedFolderByID(it)), true) }

        controller().disableMedia()

        support.waitForAutoUploadToStop(on.rowsToStart, WORK_TIMEOUT_MILLIS)
        on.rowsToStart.forEach { assertFalse("row $it is stopped", checkNotNull(rowOf(it)).isEnabled) }
    }

    private fun rowOf(id: Long) = support.provider.getSyncedFolderByID(id)

    private fun fingerprint(id: Long): String {
        val row = checkNotNull(rowOf(id))
        return listOf(
            row.localPath, row.remotePath, row.isWifiOnly, row.isChargingOnly, row.alsoUploadExistingFiles(),
            row.isSubfolderByDate, row.account, row.uploadAction, row.nameCollisionPolicyInt, row.isEnabled,
            row.enabledTimestampMs, row.type, row.isHidden, row.subfolderRule, row.isExcludeHidden,
            row.lastScanTimestampMs
        ).joinToString("|")
    }

    private companion object {
        const val WORK_TIMEOUT_MILLIS = 60_000L
    }
}
