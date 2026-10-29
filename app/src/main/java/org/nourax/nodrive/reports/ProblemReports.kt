/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.utils.PermissionUtil
import org.nourax.nodrive.backup.BackupControllers
import org.nourax.nodrive.backup.BackupState
import org.nourax.nodrive.backup.OfferState
import java.util.Collections
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * The entry points of the problem reports (round 3, contract B). Three small marked hooks in upstream files call this:
 * the app start, the main screen and the Settings screen (see [ReportsSettingsSection]). Everything is a no-op unless
 * R.bool.nodrive_reports is true, and nothing here ever throws into the app.
 */
object ProblemReports {
    private const val TAG = "ProblemReports"
    private val flushGate = FlushGate()
    private val observing: MutableSet<Activity> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))

    fun enabled(context: Context): Boolean = context.applicationContext.resources.getBoolean(R.bool.nodrive_reports)

    /** App start (one hook in MainApp.onCreate). Installs the crash recorder, then looks at earlier exits in the background. */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun onAppCreate(app: Application) {
        try {
            if (!enabled(app)) return
            // The crash screen runs in its own process (":crash"); only the main process records.
            if (Application.getProcessName() != app.packageName) return
            val files = ReportFiles.of(app)
            installRecorder(files)
            thread(name = "nodrive-reports-start") {
                try {
                    files.ensure()
                    ExitCheck.run(app, ReportSettings.create(app), files)
                    flow(app).atStart()
                } catch (e: Throwable) {
                    // Also an Error (for example OutOfMemoryError while scrubbing): it must not reach CrashRecorder, or the
                    // same report would be made again at every start.
                    Log_OC.w(TAG, "start work failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "not started: ${e.javaClass.simpleName}")
        }
    }

    private fun installRecorder(files: ReportFiles) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(files.pending, current))
    }

    /** The main screen is in front (one hook in FileDisplayActivity.onResume). */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun onMainScreen(activity: AppCompatActivity, user: User?) {
        try {
            if (!enabled(activity)) return
            observeResults(activity)
            val app = activity.applicationContext
            // The turn is used up only when an account is signed in (see FlushGate).
            val flush = flushGate.take(isSignedIn(app))
            val mayAsk = canAsk(activity, user)
            thread(name = "nodrive-reports-main") {
                try {
                    val question = flow(app).atMainScreen(flush, mayAsk)
                    if (question != null) activity.runOnUiThread { askToSend(activity) }
                } catch (e: Throwable) {
                    Log_OC.w(TAG, "main screen work failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "main screen hook failed: ${e.javaClass.simpleName}")
        }
    }

    fun flow(context: Context): ReportFlow {
        val app = context.applicationContext
        val settings = ReportSettings.create(app)
        return ReportFlow(
            files = ReportFiles.of(app),
            autoSend = { settings.autoSend },
            askedFor = { settings.askedFor },
            rememberAsked = { settings.askedFor = it },
            makeReport = { ReportMaker.makeFromPending(app, it) },
            startUpload = { ReportUploadWorker.enqueue(app) },
            signedIn = { isSignedIn(app) }
        )
    }

    fun isSignedIn(context: Context): Boolean = try {
        !UserAccountManagerImpl.fromContext(context).user.isAnonymous
    } catch (e: RuntimeException) {
        false
    }

    // The question must not stack on the Backup offer or upstream's storage question.
    private fun canAsk(activity: AppCompatActivity, user: User?): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val choiceDialogOpen =
            activity.supportFragmentManager.findFragmentByTag(PermissionUtil.PERMISSION_CHOICE_DIALOG_TAG) != null
        val storageQuestionFinished = PermissionUtil.checkStoragePermission(activity) ||
            AppPreferencesImpl.fromContext(activity).isStoragePermissionRequested
        val offerPending = user != null && !user.isAnonymous && BackupControllers.isAvailable(activity) &&
            BackupState(user.accountName, ArbitraryDataProviderImpl(activity)).offer() == OfferState.PENDING
        return !choiceDialogOpen && storageQuestionFinished && !offerPending
    }

    private fun askToSend(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val app = activity.applicationContext
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.nodrive_reports_crash_prompt)
            .setPositiveButton(R.string.nodrive_reports_send) { _, _ -> thread(name = "nodrive-reports-send") { flow(app).send() } }
            .setNegativeButton(R.string.nodrive_reports_not_now) { _, _ -> thread(name = "nodrive-reports-skip") { flow(app).notNow() } }
            .setOnCancelListener { thread(name = "nodrive-reports-skip") { flow(app).notNow() } }
            .show()
    }

    /** The message for a finished upload run, or null when the run has nothing to tell. */
    fun messageFor(outcome: String?): Int? = ReportMessages.forOutcome(outcome)

    // Shows "Problem report sent" (or the saved message) once per finished run, on the main screen.
    private fun observeResults(activity: AppCompatActivity) {
        if (!observing.add(activity)) return
        val settings = ReportSettings.create(activity)
        WorkManager.getInstance(activity.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ReportUploadWorker.UNIQUE_NAME)
            .observe(activity) { infos ->
                infos.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { info ->
                    val id = info.id.toString()
                    if (settings.isShown(id)) return@forEach
                    settings.markShown(id)
                    val message = messageFor(info.outputData.getString(ReportUploadWorker.OUTCOME)) ?: return@forEach
                    // "Waiting" is told on the report screen, not as a surprise on the main screen.
                    if (message == R.string.nodrive_reports_waiting) return@forEach
                    show(activity, message)
                }
            }
    }

    fun show(activity: Activity, message: Int) {
        val content = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        Snackbar.make(content, message, Snackbar.LENGTH_LONG).show()
    }
}
