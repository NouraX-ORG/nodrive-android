/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.os.Bundle
import android.view.MenuItem
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.owncloud.android.R
import com.owncloud.android.ui.activity.ToolbarActivity
import org.nourax.nodrive.workspace.WorkspaceLinks

/** A short guide to move Google Drive and Google Photos to NoDrive with Google Takeout (design 3.2). */
class MoveFromGoogleActivity : ToolbarActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nodrive_backup_takeout)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.let { viewThemeUtils.files.themeActionBar(this, it, R.string.nodrive_backup_move_google_title) }
        findViewById<MaterialButton>(R.id.nodrive_backup_takeout_open).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryFilled(this)
            // Only the address is passed: no header, no account and no NoDrive credential.
            setOnClickListener { WorkspaceLinks.openInCustomTab(this@MoveFromGoogleActivity, TAKEOUT_URL.toUri()) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val TAKEOUT_URL = "https://takeout.google.com"
    }
}
