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
import android.os.Build
import android.provider.Settings
import com.owncloud.android.utils.PermissionUtil

/** What the phone has to allow before the backup may be switched on (design 2.5 and 2.6). */
object BackupAccess {
    fun mediaGranted(context: Context): Boolean =
        PermissionUtil.checkAllFilesAccess() || PermissionUtil.checkMediaAccess(context)

    /** Android 14 "selected photos only": NoDrive would see just the picked items, so this is not enough. */
    fun mediaPartialOnly(context: Context): Boolean =
        !mediaGranted(context) && PermissionUtil.checkPartialMediaAccess(context)

    fun docsGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        PermissionUtil.checkAllFilesAccess()
    } else {
        PermissionUtil.checkMediaAccess(context)
    }

    val docsNeedAllFilesScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Upstream's own intent first, then the general All files access list, then this app's details page. */
    fun allFilesAccessIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            PermissionUtil.getManageAllFilesIntent(context),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
