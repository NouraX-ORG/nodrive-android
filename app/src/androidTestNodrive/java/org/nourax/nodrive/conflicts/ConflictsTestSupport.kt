/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.accounts.Account
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.database.NextcloudDatabase
import com.nextcloud.client.database.entity.UploadEntity
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.resources.files.CreateFolderRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import org.junit.Assert.assertTrue
import org.nourax.nodrive.backup.BackupPaths
import org.nourax.nodrive.backup.BackupTestSupport
import org.nourax.nodrive.backup.PhoneFolders
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Accounts, server files, notifications and screenshots for the conflict tests. The backup support does the rest. */
class ConflictsTestSupport {
    val backup = BackupTestSupport()
    val context: Context get() = backup.context
    private val arguments = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    val hasServer: Boolean
        get() = backup.serverUrl != null && backup.userId != null && serverToken != null
    private val serverToken: String? get() = arguments.getString(ARG_TOKEN)

    fun addServerAccount(): Account =
        backup.addAccount(checkNotNull(backup.userId), checkNotNull(backup.serverUrl), checkNotNull(serverToken))

    fun settings(): ConflictSettings = ConflictSettings.create(context)

    /**
     * Saves the account's choice and nothing else: the Auto upload rows keep their value. (Upstream's Auto upload job
     * takes up a waiting conflict again as soon as its folder's policy is no longer Ask, so changing the rows would
     * resolve the conflicts by itself, before the case under test.)
     */
    fun saveChoiceOnly(account: String, choice: ConflictChoice) {
        ArbitraryDataProviderImpl(context).storeOrUpdateKeyValue(account, ConflictSettings.KEY_CHOICE, choice.key)
    }

    // ---- the throwaway server

    private fun client(): OwnCloudClient = OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(
        UserAccountManagerImpl.fromContext(context).user.toOwnCloudAccount(),
        context
    )

    /** Where the backup puts the files of the camera folder, whatever the saved root is (also before the switch is on). */
    fun cameraRemoteFolder(account: String): String {
        val root = BackupPaths.root(backup.state(account).read().root, PhoneFolders.deviceName(context))
        return BackupPaths.remotePath(root, backup.cameraFolder().absolutePath)
    }

    /** Puts a file on the server with the bytes of [local]. The folder is made first when it is missing. */
    fun putOnServer(remote: String, local: File) {
        val folder = remote.substringBeforeLast('/')
        CreateFolderRemoteOperation(folder, true).execute(client())
        val result = UploadFileRemoteOperation(
            local.absolutePath,
            remote,
            "image/jpeg",
            System.currentTimeMillis() / MILLIS_PER_SECOND
        ).execute(client())
        assertTrue("$remote put on the server: ${result.logMessage}", result.isSuccess)
    }

    /** A small file whose size is not the size of any JPEG the tests make (so it is a different file). */
    fun otherBytes(name: String, size: Int = OTHER_SIZE): File {
        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { out -> out.write(ByteArray(size) { (it % BYTE_RANGE).toByte() }) }
        return file
    }

    fun remoteFile(remote: String): RemoteFile? {
        val result = ReadFileRemoteOperation(remote).execute(client())
        return if (result.isSuccess) result.data[0] as? RemoteFile else null
    }

    fun sizeOnServer(remote: String): Long? = remoteFile(remote)?.length

    fun etagOnServer(remote: String): String? = remoteFile(remote)?.etag

    fun namesOnServer(folder: String): List<String> {
        val result = ReadFolderRemoteOperation(folder).execute(client())
        if (!result.isSuccess) return emptyList()
        return result.data.filterIsInstance<RemoteFile>().map { it.remotePath?.trimEnd('/')?.substringAfterLast('/').orEmpty() }
    }

    // ---- the upload rows

    fun uploadOf(remote: String): UploadEntity? = NextcloudDatabase.instance().uploadDao().getByRemotePath(remote)

    fun waitingConflicts(account: String): Int = ConflictNotifications.countWaiting(account)

    // ---- notifications

    /** Every notification of the app that is on the screen right now. */
    fun activeNotifications(): List<StatusBarNotification> =
        context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    fun summaries(): List<StatusBarNotification> = activeNotifications().filter { it.tag == ConflictNotifications.TAG }

    fun title(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun actionTitles(n: StatusBarNotification): List<String> =
        n.notification.actions.orEmpty().map { it.title.toString() }

    // ---- files and screenshots

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        FileOutputStream(File(folder, "conflicts-$name-${backup.look}.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    /** Opens the notification shade, takes the screenshot and closes it again. */
    fun shadeScreenshot(name: String) {
        backup.shell("cmd statusbar expand-notifications")
        backup.sleepForAnimation()
        backup.sleepForAnimation()
        screenshot(name)
        backup.shell("cmd statusbar collapse")
        backup.sleepForAnimation()
    }

    companion object {
        const val ARG_TOKEN = "NODRIVE_CF_TOKEN"
        private const val OTHER_SIZE = 37
        private const val BYTE_RANGE = 120
        private const val MILLIS_PER_SECOND = 1000L
        private const val PNG_QUALITY = 100
    }
}
