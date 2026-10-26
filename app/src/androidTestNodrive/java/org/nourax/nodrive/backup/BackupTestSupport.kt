/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.MainApp
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.lib.common.accounts.AccountUtils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Accounts, media files, polling and screenshots for the backup tests (no server needed unless asked for). */
class BackupTestSupport {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    val context: Context = instrumentation.targetContext

    val look: String get() = arguments.getString(ARG_LOOK) ?: "light"
    val serverUrl: String? get() = arguments.getString(ARG_URL)
    val userId: String? get() = arguments.getString(ARG_USER)
    val serverToken: String? get() = arguments.getString(ARG_TOKEN)

    val provider: SyncedFolderProvider
        get() = SyncedFolderProvider(context.contentResolver, AppPreferencesImpl.fromContext(context), ClockImpl())

    /** A stand-in account whose server address is on the phone itself, so nothing leaves the emulator. */
    fun addLocalAccount(): Account = addAccount("demo", LOCAL_URL, "offline-backup-test")

    fun addServerAccount(): Account =
        addAccount(checkNotNull(userId), checkNotNull(serverUrl), checkNotNull(serverToken))

    private fun addAccount(user: String, url: String, password: String): Account {
        val manager = AccountManager.get(context)
        val type = MainApp.getAccountType(context)
        manager.getAccountsByType(type).forEach {
            BackupState.forget(context, it.name)
            manager.removeAccountExplicitly(it)
        }
        wipeAllAccountsAndRowsCiEmulatorOnly()
        val account = Account("$user@$url", type)
        check(manager.addAccountExplicitly(account, password, null)) { "account not added" }
        manager.setUserData(
            account,
            AccountUtils.Constants.KEY_OC_ACCOUNT_VERSION,
            UserAccountManager.ACCOUNT_VERSION.toString()
        )
        manager.setUserData(account, AccountUtils.Constants.KEY_OC_VERSION, SERVER_VERSION)
        manager.setUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL, url)
        manager.setUserData(account, AccountUtils.Constants.KEY_USER_ID, user)
        check(UserAccountManagerImpl.fromContext(context).setCurrentOwnCloudAccount(account.name))
        BackupState.forget(context, account.name)
        return account
    }

    /**
     * CI emulator only: removes every NoDrive account's Auto upload row, not just the ones a test made. Never call this
     * on a phone that has real data.
     */
    fun wipeAllAccountsAndRowsCiEmulatorOnly() {
        val rows = provider
        rows.syncedFolders.forEach { rows.deleteSyncedFolder(it.id) }
    }

    fun rows(): List<SyncedFolder> = provider.syncedFolders

    /** Runs a shell command as the shell user (for appops) and waits for it to end. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /** The unique work name of upstream's Auto upload for a row (BackgroundJobManagerImpl.startAutoUpload). */
    fun autoUploadWorkStates(rowId: Long): List<WorkInfo.State> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork("${BackgroundJobManagerImpl.JOB_IMMEDIATE_FILES_SYNC}_$rowId").get().map { it.state }

    fun waitForAutoUploadToStop(rowIds: List<Long>, timeoutMillis: Long) =
        waitFor("no Auto upload work running or queued for $rowIds", timeoutMillis) {
            true.takeIf { rowIds.all { id -> autoUploadWorkStates(id).all { it.isFinished } } }
        }

    fun state(account: String) = BackupState(account, ArbitraryDataProviderImpl(context))

    fun cameraFolder(): File = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")

    fun insertJpeg(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or OPAQUE)
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return uri
    }

    fun deleteMedia(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    fun newJpegName() = "nodrive-ci-${UUID.randomUUID()}.jpg"

    fun <T : Any> waitFor(what: String, timeoutMillis: Long, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                probe()?.let { return it }
            } catch (e: AssertionError) {
                last = e
            } catch (e: RuntimeException) {
                last = e
            }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out after ${timeoutMillis / MILLIS_PER_SECOND} s: $what (last: $last)")
    }

    fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    inline fun <reified A : Activity> resumed(): A? = onMainResult {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is A } as A?
    }

    fun sleepForAnimation() = SystemClock.sleep(ANIMATION_MILLIS)

    fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        FileOutputStream(File(folder, "backup-$name-$look.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    companion object {
        const val ARG_URL = "NODRIVE_WS_URL"
        const val ARG_USER = "NODRIVE_WS_USER"
        const val ARG_TOKEN = "NODRIVE_BK_TOKEN"
        const val ARG_LOOK = "NODRIVE_BK_LOOK"
        const val LOCAL_URL = "http://127.0.0.1:9"
        private const val SERVER_VERSION = "35.0.2.1"
        private const val BITMAP_SIZE = 16
        private const val JPEG_QUALITY = 90
        private const val PNG_QUALITY = 100
        private const val OPAQUE = 0xFF000000.toInt()
        private const val POLL_MILLIS = 500L
        private const val ANIMATION_MILLIS = 1500L
        private const val MILLIS_PER_SECOND = 1000L

        /** What the backup needs from the phone, matching what the manifest declares. */
        fun grantRule(): GrantPermissionRule {
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
            }
            @Suppress("SpreadOperator")
            return GrantPermissionRule.grant(*permissions.toTypedArray())
        }
    }
}
