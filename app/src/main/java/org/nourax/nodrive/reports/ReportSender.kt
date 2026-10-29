/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.io.File
import java.io.IOException

enum class RemoteKind { MISSING, FOLDER, FILE }

enum class UploadStatus {
    DONE,

    /** A network or server problem that may pass: try again later. */
    RETRY,

    /** The server said no: keep the report, try again at the next start. */
    KEEP
}

/** The network failed (no answer, timeout, a server error). */
class ReportNetworkException(message: String) : IOException(message)

/** What the sender needs from the server of the signed-in account. The real one is [WebDavReportServer]. */
interface ReportServer {
    @Throws(ReportNetworkException::class)
    fun kindOf(path: String): RemoteKind

    @Throws(ReportNetworkException::class)
    fun createFolder(path: String): Boolean

    fun upload(local: File, remote: String): UploadStatus

    @Throws(ReportNetworkException::class)
    fun hasSupportShare(folder: String): Boolean

    /** True when the folder is now shared read-only with Nourax Support. */
    fun shareWithSupport(folder: String): Boolean
}

/** What one run of the sender did. */
data class SendRun(
    /** Reports that arrived. */
    val sent: Int,
    /** Reports the server refused (they stay on the phone). */
    val kept: Int,
    /** A network problem stopped the run: worth another try soon. */
    val retry: Boolean,
    /** After the run the folder is shared with Nourax Support. False when the share failed or was not tried. */
    val sharedWithSupport: Boolean
)

/**
 * The flow of contract B, "where the report goes": make the folder, never overwrite a name, upload, and try the read-only
 * share to Nourax Support at most once per run. A share that fails is not an error: the report is still saved.
 */
class ReportSender(private val server: ReportServer) {
    fun sendAll(zips: List<File>, onUploaded: (File) -> Unit): SendRun {
        var sent = 0
        var kept = 0
        try {
            when (server.kindOf(FOLDER)) {
                RemoteKind.FILE -> return SendRun(0, zips.size, retry = false, sharedWithSupport = false)
                RemoteKind.MISSING -> if (!server.createFolder(FOLDER) || server.kindOf(FOLDER) != RemoteKind.FOLDER) {
                    return SendRun(0, zips.size, retry = false, sharedWithSupport = false)
                }
                RemoteKind.FOLDER -> Unit
            }
            for (zip in zips) {
                val name = ReportName.firstFree(zip.nameWithoutExtension) { server.kindOf("$FOLDER/$it") != RemoteKind.MISSING }
                if (name == null) {
                    // Every name is taken: nothing is overwritten, the report stays on the phone.
                    kept++
                    continue
                }
                when (server.upload(zip, "$FOLDER/$name")) {
                    UploadStatus.DONE -> {
                        onUploaded(zip)
                        sent++
                    }
                    UploadStatus.KEEP -> kept++
                    UploadStatus.RETRY -> return SendRun(sent, kept, retry = true, sharedWithSupport = shareOnce(sent))
                }
            }
        } catch (e: ReportNetworkException) {
            return SendRun(sent, kept, retry = true, sharedWithSupport = shareOnce(sent))
        }
        return SendRun(sent, kept, retry = false, sharedWithSupport = shareOnce(sent))
    }

    /** One try per run, and only after a report arrived. Any failure is silent. */
    private fun shareOnce(sent: Int): Boolean {
        if (sent == 0) return false
        return try {
            server.hasSupportShare(FOLDER_SHARE) || server.shareWithSupport(FOLDER_SHARE)
        } catch (e: ReportNetworkException) {
            false
        }
    }

    companion object {
        const val FOLDER = "/Problem reports"
        const val FOLDER_SHARE = "/Problem reports/"
        const val SUPPORT_USER = "nourax-support"
    }
}
