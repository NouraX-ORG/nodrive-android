/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.owncloud.android.R
import com.owncloud.android.ui.activity.ToolbarActivity
import com.owncloud.android.utils.DisplayUtils

/** Lists where Google backs up the phone's media, each with its steps and an Open button (design 3.1). */
class StopGoogleBackupActivity : ToolbarActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nodrive_backup_places)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.let { viewThemeUtils.files.themeActionBar(this, it, R.string.nodrive_backup_stop_google_title) }

        val launcher = PlaceLauncher(starterFactory(this))
        val container = findViewById<LinearLayout>(R.id.nodrive_backup_places)
        GoogleBackupPlaces.visible { isInstalled(it) }.forEach { container.addView(card(container, it, launcher)) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun isInstalled(packageName: String) = packageManager.getLaunchIntentForPackage(packageName) != null

    private fun card(parent: LinearLayout, place: Place, launcher: PlaceLauncher): MaterialCardView {
        val card = LayoutInflater.from(this).inflate(R.layout.nodrive_backup_place_item, parent, false) as MaterialCardView
        viewThemeUtils.material.themeCardView(card)
        card.findViewById<TextView>(R.id.nodrive_backup_place_title).setText(place.titleRes)
        card.findViewById<TextView>(R.id.nodrive_backup_place_steps).setText(place.stepsRes)
        card.findViewById<MaterialButton>(R.id.nodrive_backup_place_open).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryFilled(this)
            setOnClickListener {
                if (launcher.open(place) == LaunchResult.FAILED) {
                    DisplayUtils.showSnackMessage(this@StopGoogleBackupActivity, R.string.nodrive_backup_place_failed)
                }
            }
        }
        return card
    }

    companion object {
        /** Tests replace this to make every intent fail. */
        @VisibleForTesting
        var starterFactory: (Activity) -> IntentStarter = { AndroidIntentStarter(it) }
    }
}
