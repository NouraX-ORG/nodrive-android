/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.BackgroundJobManager
import com.owncloud.android.R
import com.owncloud.android.ui.activity.ToolbarActivity
import javax.inject.Inject

/**
 * "Calendar and contacts on this phone" (plan 4.1, 4.7): the steps that switch NoDrive's own calendar and contacts sync on, and the
 * status screen after it. It is also what the phone's account settings open (`syncAdapterSettingsAction`).
 */
class DeviceSyncActivity : ToolbarActivity() {
    @Inject
    lateinit var backgroundJobManager: BackgroundJobManager

    private lateinit var model: DeviceSyncModel

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (allGranted()) model.permissionsGranted() else model.needPermissions(denied = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nodrive_device_sync)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.let { viewThemeUtils.files.themeActionBar(this, it, getString(R.string.nodrive_device_sync_title)) }

        val user: User? = chosenUser()
        if (user == null || user.isAnonymous) {
            finish()
            return
        }
        model = ViewModelProvider(
            this, DeviceSyncModel.Factory(applicationContext, user.accountName, backgroundJobManager, user)
            // keyed by the account: a ViewModel survives recreate(), and a new account must not get the old account's screen
        )[user.accountName, DeviceSyncModel::class.java]

        val actions = DeviceSyncActions(
            continueIntro = { if (allGranted()) model.permissionsGranted() else model.needPermissions(denied = false) },
            allowAccess = { permissionLauncher.launch(PERMISSIONS) },
            openSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            toggle = model::toggle,
            chooseDone = model::chooseDone,
            confirmUntick = model::confirmUntick,
            cancelUntick = model::cancelUntick,
            retryChoices = { model.loadChoices() },
            retryBackup = model::startBackup,
            finishFirstSync = model::finishFirstSync,
            syncNow = model::syncNow,
            chooseAgain = { model.loadChoices(changing = true) },
            askStop = model::askStop,
            confirmStop = model::confirmStop,
            cancelStop = model::cancelStop,
            applyAgain = model::applyAgain,
            keepServer = model::keepServerVersion
        )
        findViewById<ComposeView>(R.id.nodrive_device_sync_compose).setContent {
            MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(this)) {
                DeviceSyncScreen(model, actions)
            }
        }
    }

    /** The account the screen is about: the one the caller named, or the one that is signed in now. */
    private fun chosenUser(): User? {
        val named = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        if (!named.isNullOrEmpty()) userAccountManager.getUser(named).orElse(null)?.let { return it }
        return user.orElse(null)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            // back from the phone's settings with the access allowed
            if (model.state.value.step == Step.PERMISSIONS && allGranted()) model.permissionsGranted()
            if (model.state.value.step == Step.STATUS) model.refreshStatus()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun allGranted(): Boolean = PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        private const val EXTRA_ACCOUNT_NAME = "ACCOUNT_NAME"

        private val PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
        )

        @JvmStatic
        fun intent(context: Context, account: String): Intent =
            Intent(context, DeviceSyncActivity::class.java).putExtra(EXTRA_ACCOUNT_NAME, account)
    }
}
