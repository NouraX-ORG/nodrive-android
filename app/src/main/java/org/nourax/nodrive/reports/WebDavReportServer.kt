/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.resources.files.CreateFolderRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.lib.resources.shares.CreateShareRemoteOperation
import com.owncloud.android.lib.resources.shares.GetSharesForFileRemoteOperation
import com.owncloud.android.lib.resources.shares.OCShare
import com.owncloud.android.lib.resources.shares.ShareType
import com.owncloud.android.utils.MimeType
import java.io.File

/**
 * The two times of an upload (X-OC-MTime and X-OC-CTime) are Unix SECONDS, like the ones upstream sends. Both are the moment
 * the report is sent.
 */
data class UploadStamps(val modifiedSeconds: Long, val createdSeconds: Long) {
    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        fun at(nowMillis: Long): UploadStamps = (nowMillis / MILLIS_PER_SECOND).let { UploadStamps(it, it) }
    }
}

/**
 * [ReportServer] on the client of the signed-in account: its own server and its own session, nothing else. No host name
 * appears here; the client already knows the account's server.
 */
class WebDavReportServer(private val client: OwnCloudClient) : ReportServer {
    override fun kindOf(path: String): RemoteKind {
        val result = ReadFileRemoteOperation(path).execute(client)
        if (result.isSuccess) {
            val file = result.data?.firstOrNull() as? RemoteFile
            return if (file != null && MimeType.DIRECTORY == file.mimeType) RemoteKind.FOLDER else RemoteKind.FILE
        }
        if (result.httpCode == HTTP_NOT_FOUND || result.code == RemoteOperationResult.ResultCode.FILE_NOT_FOUND) {
            return RemoteKind.MISSING
        }
        throw ReportNetworkException("folder check failed: ${result.code}")
    }

    override fun createFolder(path: String): Boolean = CreateFolderRemoteOperation("$path/", true).execute(client).isSuccess

    @Suppress("DEPRECATION")
    override fun upload(local: File, remote: String): UploadStatus {
        val stamps = UploadStamps.at(System.currentTimeMillis())
        val result = UploadFileRemoteOperation(
            local.absolutePath,
            remote,
            MIME_ZIP,
            "",
            stamps.modifiedSeconds,
            stamps.createdSeconds,
            true
        ).execute(client)
        return when {
            result.isSuccess -> UploadStatus.DONE
            result.httpCode in HTTP_CLIENT_ERROR_RANGE || result.httpCode == HTTP_INSUFFICIENT_STORAGE -> UploadStatus.KEEP
            else -> UploadStatus.RETRY
        }
    }

    override fun hasSupportShare(folder: String): Boolean {
        val result = GetSharesForFileRemoteOperation(folder, false, false).execute(client)
        if (result.isSuccess) {
            return result.resultData.orEmpty().any {
                it.shareType == ShareType.USER && it.shareWith == ReportSender.SUPPORT_USER
            }
        }
        if (result.code == RemoteOperationResult.ResultCode.SHARE_NOT_FOUND) return false
        throw ReportNetworkException("share check failed: ${result.code}")
    }

    override fun shareWithSupport(folder: String): Boolean = CreateShareRemoteOperation(
        folder,
        ShareType.USER,
        ReportSender.SUPPORT_USER,
        false,
        "",
        OCShare.READ_PERMISSION_FLAG
    ).execute(client).isSuccess

    private companion object {
        const val MIME_ZIP = "application/zip"
        const val HTTP_NOT_FOUND = 404
        const val HTTP_INSUFFICIENT_STORAGE = 507
        val HTTP_CLIENT_ERROR_RANGE = 400..499
    }
}
