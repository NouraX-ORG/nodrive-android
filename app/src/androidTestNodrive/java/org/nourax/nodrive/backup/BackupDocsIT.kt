/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.os.Environment
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.utils.PermissionUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The Documents and Downloads switch with and without All files access. Changing that access (appops) restarts the
 * app's process, so tools/ci/backup-device.sh sets it from outside before each of the two tests; a test fails when
 * the access is not what it expects, so a skipped setup is never mistaken for a pass.
 */
class BackupDocsIT {
    @get:Rule
    val permissions = BackupTestSupport.grantRule()

    private val support = BackupTestSupport()
    private val createdFiles = mutableListOf<File>()
    private val createdFolders = mutableListOf<File>()
    private lateinit var account: String

    private fun controller() = BackupControllers.create(support.context, support.provider, ClockImpl(), account)

    @Before
    fun setUp() {
        account = support.addLocalAccount().name
    }

    @After
    fun tearDown() {
        createdFiles.forEach { it.delete() }
        createdFolders.forEach { it.delete() }
        support.wipeAllAccountsAndRowsCiEmulatorOnly()
        BackupState.forget(support.context, account)
    }

    @Test
    fun withoutAllFilesAccessNoDocumentsFolderIsFoundAndNoRowIsMade() {
        assertFalse("the script turned All files access off", PermissionUtil.checkAllFilesAccess())
        assertTrue(PhoneFolders(support.context).documentFolders().isEmpty())
        val result = controller().enableDocs()
        assertTrue(result.rowsToStart.isEmpty())
        assertTrue(support.rows().none { it.type == MediaFolderType.CUSTOM })
    }

    @Test
    fun withAllFilesAccessDocumentsAndDownloadsGetTheirOwnRows() {
        assertTrue("the script turned All files access on", PermissionUtil.checkAllFilesAccess())
        val storage = Environment.getExternalStorageDirectory()
        val download = File(storage, "Download")
        val documents = File(storage, "Documents")
        listOf(download, documents).filterNot { it.exists() }.forEach {
            assertTrue(it.mkdirs())
            createdFolders += it
        }
        val file = File(download, "nodrive-ci-doc-${UUID.randomUUID()}.txt")
        file.writeText("backup test")
        createdFiles += file

        val on = controller().enableDocs()
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
    }
}
