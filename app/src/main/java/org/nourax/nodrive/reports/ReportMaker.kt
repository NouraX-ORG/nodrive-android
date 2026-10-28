/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import android.os.Build
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.BuildConfig
import com.owncloud.android.lib.common.utils.Log_OC
import java.io.File
import java.io.IOException
import java.util.Locale

/** Collects what a report needs from the phone and the app, and has [ReportBuilder] make the zip in the outbox. */
object ReportMaker {
    private const val TAG = "ReportMaker"

    // The upstream file logger writes log.txt and rotates it to log.txt.0, .1 and .2 (the oldest).
    private val LOG_NAMES = listOf("log.txt.2", "log.txt.1", "log.txt.0", "log.txt")

    fun logFiles(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, "logs")
        return LOG_NAMES.map { File(dir, it) }
    }

    fun meta(context: Context, nowMillis: Long): ReportMeta = ReportMeta(
        appVersion = BuildConfig.VERSION_NAME,
        build = BuildConfig.VERSION_CODE.toString(),
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        locale = Locale.getDefault().toLanguageTag(),
        createdUtc = ReportName.isoUtc(nowMillis),
        serverHost = serverHost(context)
    )

    /** The host of the signed-in account's server, empty without an account. */
    @Suppress("TooGenericExceptionCaught")
    fun serverHost(context: Context): String = try {
        val user = UserAccountManagerImpl.fromContext(context).user
        if (user.isAnonymous) "" else user.server.uri.host.orEmpty()
    } catch (e: RuntimeException) {
        ""
    }

    /** Makes the zip. Returns null when it could not be made. */
    @Suppress("TooGenericExceptionCaught")
    fun make(context: Context, kind: ReportKind, note: String, crashText: String?): File? {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        return try {
            ReportBuilder(ReportFiles.of(app)).build(
                ReportInput(kind, meta(app, now), note, crashText, logFiles(app), now, BuildConfig.VERSION_NAME)
            )
        } catch (e: IOException) {
            Log_OC.w(TAG, "report not made: ${e.javaClass.simpleName}")
            null
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "report not made: ${e.javaClass.simpleName}")
            null
        }
    }

    fun makeManual(context: Context, note: String): File? = make(context, ReportKind.MANUAL, note, null)

    fun makeFromPending(context: Context, pending: PendingReport): Boolean =
        make(context, pending.kind, "", pending.text()) != null
}
