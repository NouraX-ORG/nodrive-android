/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.work.WorkManager
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.utils.PermissionUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * What the backup writes into Auto upload (the rows and their field values), a row of the user that it must not
 * touch, the stop of running uploads when it is switched off, and the Documents and Downloads switch with All files
 * access. Needs no server: the account points at the phone itself.
 */
class BackupRowsIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = BackupTestSupport()
    private val created = mutableListOf<Uri>()
    private val createdFiles = mutableListOf<File>()
    private val createdFolders = mutableListOf<File>()
    private lateinit var account: String

    private val packageName get() = support.context.packageName

    private fun controller() = BackupControllers.create(support.context, support.provider, ClockImpl(), account)

    @Before
    fun setUp() {
        account = support.addLocalAccount().name
    }

    @After
    fun tearDown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            support.shell("appops set $packageName MANAGE_EXTERNAL_STORAGE default")
        }
        created.forEach { support.deleteMedia(it) }
        createdFiles.forEach { it.delete() }
        createdFolders.forEach { it.delete() }
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

    @Test
    fun documentsAndDownloadsNeedAllFilesAccessAndThenGetTheirOwnRows() {
        assumeTrue("All files access exists from Android 11", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val storage = Environment.getExternalStorageDirectory()
        val download = File(storage, "Download")
        val documents = File(storage, "Documents")
        support.shell("appops set $packageName MANAGE_EXTERNAL_STORAGE deny")
        support.waitFor("All files access is off", WORK_TIMEOUT_MILLIS) { true.takeIf { !PermissionUtil.checkAllFilesAccess() } }
        assertTrue("without access no folder is found", PhoneFolders(support.context).documentFolders().isEmpty())

        support.shell("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        support.waitFor("All files access is on", WORK_TIMEOUT_MILLIS) { true.takeIf { PermissionUtil.checkAllFilesAccess() } }

        // Written only now: without All files access the app cannot write there.
        listOf(download, documents).filterNot { it.exists() }.forEach {
            assertTrue(it.mkdirs())
            createdFolders += it
        }
        val file = File(download, "nodrive-ci-doc-${UUID.randomUUID()}.txt")
        file.writeText("backup test")
        createdFiles += file

        val on = controller().enableDocs(BackupDefaults())
        assertEquals(2, on.rowsToStart.size)
        val root = checkNotNull(support.state(account).read().root)
        val custom = support.rows().filter { it.type == MediaFolderType.CUSTOM && it.account == account }
        assertEquals(
            setOf(download.absolutePath to "$root/Download", documents.absolutePath to "$root/Documents"),
            custom.map { it.localPath to it.remotePath }.toSet()
        )
        assertTrue(custom.all { it.isEnabled && it.isExcludeHidden && it.isWifiOnly && !it.isSubfolderByDate })
        assertTrue(custom.all { it.uploadAction == FileUploadWorker.LOCAL_BEHAVIOUR_FORGET })
        assertTrue(support.state(account).read().docsOn)
        assertFalse("the media switch is separate", support.state(account).read().mediaOn)

        controller().disableDocs()
        assertTrue(support.rows().filter { it.type == MediaFolderType.CUSTOM }.none { it.isEnabled })
        assertTrue("the file is still on the phone", file.exists())
        assertNotNull(support.state(account).read().root)
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
