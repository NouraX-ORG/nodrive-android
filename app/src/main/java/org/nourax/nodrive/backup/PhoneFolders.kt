/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.owncloud.android.datamodel.MediaFolder
import com.owncloud.android.datamodel.MediaProvider
import com.owncloud.android.utils.PermissionUtil
import com.owncloud.android.utils.SyncedFolderUtils
import java.io.File

/** [DeviceFolders] from the phone: upstream's media folder detection, plus Documents and Download. */
class PhoneFolders(private val context: Context) : DeviceFolders {
    override fun mediaFolders(): List<MediaFolderInfo> {
        val resolver = context.contentResolver
        val all = MediaProvider.getImageFolders(resolver, ITEM_LIMIT, null, true) +
            MediaProvider.getVideoFolders(resolver, ITEM_LIMIT, null, true)
        return all.mapNotNull { toInfo(it) }
    }

    @Suppress("DEPRECATION")
    override fun documentFolders(): List<String> {
        if (!hasFileAccess()) return emptyList()
        val storage = Environment.getExternalStorageDirectory()
        return listOf(Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS)
            .map { File(storage, it) }
            .filter { it.isDirectory }
            .map { it.absolutePath }
    }

    private fun toInfo(folder: MediaFolder): MediaFolderInfo? {
        val path = folder.absolutePath ?: return null
        val type = folder.type ?: return null
        return MediaFolderInfo(path, type, folder.folderName, SyncedFolderUtils.isQualifyingMediaFolder(folder))
    }

    private fun hasFileAccess() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        PermissionUtil.checkAllFilesAccess()
    } else {
        PermissionUtil.checkMediaAccess(context)
    }

    companion object {
        private const val ITEM_LIMIT = 1

        fun deviceName(context: Context): String? = Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVICE_NAME
        ) ?: Build.MODEL
    }
}
