/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.client.core.Clock
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.utils.BatteryOptimizationHelper
import com.owncloud.android.R
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.SyncedFoldersActivity
import com.owncloud.android.ui.activity.ToolbarActivity
import com.owncloud.android.utils.PermissionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Switches the backup on or off for photos and videos, or for Documents and Downloads. For "on" it first asks the
 * phone for the access it needs; "off" only stops the folders the backup turned on, nothing is deleted.
 */
class BackupSetupActivity : ToolbarActivity() {
    @Inject
    lateinit var syncedFolderProvider: SyncedFolderProvider

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var backgroundJobManager: BackgroundJobManager

    private lateinit var action: BackupAction

    /** What the screen shows once the change is done, kept over a rotation so nothing is asked or applied twice. */
    private var finishedMessage = 0
    private var finishedLines = ""
    private var finishedWithNote = false

    private val allFilesAccessScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (BackupAccess.docsGranted(this)) applyChange() else finishDenied(R.string.nodrive_backup_docs_access_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = BackupAction.from(intent)
        if (requested == null) {
            finish()
            return
        }
        action = requested
        setContentView(R.layout.nodrive_backup_setup)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.let { viewThemeUtils.files.themeActionBar(this, it, titleOf(action)) }
        findViewById<MaterialButton>(R.id.nodrive_backup_done).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryFilled(this)
            setOnClickListener { finish() }
        }
        findViewById<MaterialButton>(R.id.nodrive_backup_open_auto_upload).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryTonal(this)
            setOnClickListener { startActivity(Intent(this@BackupSetupActivity, SyncedFoldersActivity::class.java)) }
        }
        findViewById<MaterialButton>(R.id.nodrive_backup_battery_button).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryTonal(this)
            setOnClickListener { BatteryOptimizationHelper.openBatteryOptimizationSettings(this@BackupSetupActivity) }
        }
        finishedMessage = savedInstanceState?.getInt(STATE_MESSAGE) ?: 0
        if (finishedMessage != 0) {
            finishedLines = savedInstanceState?.getString(STATE_LINES).orEmpty()
            finishedWithNote = savedInstanceState?.getBoolean(STATE_NOTE) == true
            showFinished()
        } else {
            proceed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MESSAGE, finishedMessage)
        outState.putString(STATE_LINES, finishedLines)
        outState.putBoolean(STATE_NOTE, finishedWithNote)
    }

    override fun onResume() {
        super.onResume()
        if (finishedMessage != 0) showBatteryRow()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != PermissionUtil.PERMISSIONS_EXTERNAL_STORAGE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        when {
            accessGranted() -> applyChange()
            action.isMedia && BackupAccess.mediaPartialOnly(this) ->
                finishDenied(R.string.nodrive_backup_permission_denied)
            else -> showSettingsDialog()
        }
    }

    private fun proceed() {
        when {
            !action.turnsOn -> applyChange()
            accessGranted() -> applyChange()
            action.isMedia && BackupAccess.mediaPartialOnly(this) -> showPartialDialog()
            action.isMedia || !BackupAccess.docsNeedAllFilesScreen ->
                PermissionUtil.requestRequiredStoragePermissions(this)
            else -> showAllFilesDialog()
        }
    }

    private fun accessGranted() = if (action.isMedia) BackupAccess.mediaGranted(this) else BackupAccess.docsGranted(this)

    private fun showPartialDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nodrive_backup_permission_partial_title)
            .setMessage(R.string.nodrive_backup_permission_partial_message)
            .setPositiveButton(R.string.nodrive_backup_permission_allow) { _, _ ->
                PermissionUtil.requestRequiredStoragePermissions(this)
            }
            .setNegativeButton(R.string.nodrive_backup_permission_cancel) { _, _ ->
                finishDenied(R.string.nodrive_backup_permission_denied)
            }
            .setOnCancelListener { finishDenied(R.string.nodrive_backup_permission_denied) }
            .show()
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nodrive_backup_permission_settings_title)
            .setMessage(R.string.nodrive_backup_permission_settings_message)
            .setPositiveButton(R.string.nodrive_backup_open_settings) { _, _ ->
                startActivity(BackupAccess.appDetailsIntent(this))
                finishDenied(R.string.nodrive_backup_permission_denied)
            }
            .setNegativeButton(R.string.nodrive_backup_permission_cancel) { _, _ ->
                finishDenied(R.string.nodrive_backup_permission_denied)
            }
            .setOnCancelListener { finishDenied(R.string.nodrive_backup_permission_denied) }
            .show()
    }

    private fun showAllFilesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nodrive_backup_docs_access_title)
            .setMessage(R.string.nodrive_backup_docs_access_message)
            .setPositiveButton(R.string.nodrive_backup_docs_access_continue) { _, _ -> openAllFilesAccessScreen() }
            .setNegativeButton(R.string.nodrive_backup_permission_cancel) { _, _ ->
                finishDenied(R.string.nodrive_backup_docs_access_denied)
            }
            .setOnCancelListener { finishDenied(R.string.nodrive_backup_docs_access_denied) }
            .show()
    }

    /** Tries each intent until one opens; if none does, the switch stays off. */
    private fun openAllFilesAccessScreen() {
        for (candidate in BackupAccess.allFilesAccessIntents(this)) {
            try {
                allFilesAccessScreen.launch(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                Log_OC.w(TAG, "no screen for ${candidate.action}")
            }
        }
        finishDenied(R.string.nodrive_backup_docs_access_denied)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun applyChange() {
        val user = userAccountManager.user
        if (user.isAnonymous) {
            finishDenied(R.string.nodrive_backup_failed)
            return
        }
        showWorking()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runChange(user.accountName)
                } catch (e: RuntimeException) {
                    Log_OC.e(TAG, "backup change failed: ${e.javaClass.simpleName}")
                    null
                }
            }
            if (result == null) finishDenied(R.string.nodrive_backup_failed) else showResult(result)
        }
    }

    private fun runChange(account: String): BackupResult {
        val controller = BackupControllers.create(applicationContext, syncedFolderProvider, clock, account)
        val result = when (action) {
            BackupAction.MEDIA_ON -> controller.enableMedia()
            BackupAction.MEDIA_OFF -> controller.disableMedia()
            BackupAction.DOCS_ON -> controller.enableDocs()
            BackupAction.DOCS_OFF -> controller.disableDocs()
        }
        result.rowsToStart.forEach { id ->
            syncedFolderProvider.getSyncedFolderByID(id)?.let { backgroundJobManager.startAutoUpload(it, false) }
        }
        return result
    }

    private fun showWorking() {
        findViewById<TextView>(R.id.nodrive_backup_message).setText(R.string.nodrive_backup_working)
        findViewById<ProgressBar>(R.id.nodrive_backup_progress).visibility = View.VISIBLE
    }

    private fun showResult(result: BackupResult) {
        val message = when {
            !action.turnsOn -> R.string.nodrive_backup_result_stopped
            result.lines.isNotEmpty() -> R.string.nodrive_backup_result_folders
            result.state.owned.isEmpty() && result.leftAlone.isEmpty() -> R.string.nodrive_backup_result_none_found
            else -> R.string.nodrive_backup_result_nothing_new
        }
        val covered = result.lines.joinToString("\n") { "${it.relativePath}  →  ${it.remotePath}" }
        val left = if (result.leftAlone.isEmpty()) {
            ""
        } else {
            getString(R.string.nodrive_backup_result_left_alone) + "\n" + result.leftAlone.joinToString("\n")
        }
        finishedMessage = message
        finishedLines = listOf(covered, left).filter { it.isNotEmpty() }.joinToString("\n\n")
        finishedWithNote = action.turnsOn
        showFinished()
    }

    private fun finishDenied(message: Int) {
        finishedMessage = message
        finishedLines = ""
        finishedWithNote = false
        showFinished()
    }

    private fun showFinished() {
        findViewById<TextView>(R.id.nodrive_backup_message).setText(finishedMessage)
        findViewById<ProgressBar>(R.id.nodrive_backup_progress).visibility = View.GONE
        findViewById<TextView>(R.id.nodrive_backup_lines).apply {
            text = finishedLines
            visibility = if (finishedLines.isEmpty()) View.GONE else View.VISIBLE
        }
        showButtons(withNote = finishedWithNote)
    }

    /** The same offer as upstream's Auto upload switch: the first big backup can stall on a battery-limited phone. */
    private fun showBatteryRow() {
        val show = finishedWithNote && BatteryOptimizationHelper.isBatteryOptimizationEnabled(this)
        val visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nodrive_backup_battery_note).visibility = visibility
        findViewById<View>(R.id.nodrive_backup_battery_button).visibility = visibility
    }

    private fun showButtons(withNote: Boolean) {
        findViewById<View>(R.id.nodrive_backup_note).visibility = if (withNote) View.VISIBLE else View.GONE
        showBatteryRow()
        findViewById<View>(R.id.nodrive_backup_open_auto_upload).visibility = View.VISIBLE
        findViewById<View>(R.id.nodrive_backup_done).visibility = View.VISIBLE
    }

    private fun titleOf(action: BackupAction) = when (action) {
        BackupAction.MEDIA_ON -> R.string.nodrive_backup_setup_media_on
        BackupAction.MEDIA_OFF -> R.string.nodrive_backup_setup_media_off
        BackupAction.DOCS_ON -> R.string.nodrive_backup_setup_docs_on
        BackupAction.DOCS_OFF -> R.string.nodrive_backup_setup_docs_off
    }

    companion object {
        private const val TAG = "BackupSetupActivity"
        private const val STATE_MESSAGE = "nodrive_backup_state_message"
        private const val STATE_LINES = "nodrive_backup_state_lines"
        private const val STATE_NOTE = "nodrive_backup_state_note"

        fun intent(context: Context, action: BackupAction): Intent =
            Intent(context, BackupSetupActivity::class.java).putExtra(BackupAction.EXTRA_ACTION, action.name)
    }
}
