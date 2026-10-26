/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The real [IntentStarter]: a missing target throws ActivityNotFoundException, which [PlaceLauncher] catches. */
class AndroidIntentStarter(private val context: Context) : IntentStarter {
    override fun start(spec: IntentSpec): Boolean {
        val intent = when (spec) {
            is IntentSpec.LaunchPackage -> context.packageManager.getLaunchIntentForPackage(spec.packageName)
            is IntentSpec.ViewAction -> Intent(spec.action).apply { spec.data?.let { data = Uri.parse(it) } }
        } ?: return false
        context.startActivity(intent)
        return true
    }
}
