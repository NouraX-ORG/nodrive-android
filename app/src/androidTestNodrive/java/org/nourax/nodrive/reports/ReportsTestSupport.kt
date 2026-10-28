/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.accounts.Account
import android.content.Context
import android.graphics.Bitmap
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.owncloud.android.R
import org.nourax.nodrive.backup.BackupTestSupport
import java.io.File
import java.io.FileOutputStream

/** Accounts, screenshots and the report folders for the problem report tests. */
class ReportsTestSupport {
    val backup = BackupTestSupport()
    val context: Context get() = backup.context
    private val arguments = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    val serverToken: String? get() = arguments.getString(ARG_TOKEN)
    val hasServer: Boolean get() = backup.serverUrl != null && backup.userId != null && serverToken != null

    fun addLocalAccount(): Account = backup.addLocalAccount()

    fun addServerAccount(): Account =
        backup.addAccount(checkNotNull(backup.userId), checkNotNull(backup.serverUrl), checkNotNull(serverToken))

    val files: ReportFiles get() = ReportFiles.of(context)
    val settings: ReportSettings get() = ReportSettings.create(context)

    fun pendingNames(): List<String> = files.pending.listFiles().orEmpty().map { it.name }.sorted()

    fun outboxNames(): List<String> = Outbox(files).list().map { it.name }

    /** Every log file of the app, joined (the file logger writes in the background). */
    fun logText(): String = ReportMaker.logFiles(context).filter { it.isFile }.joinToString("\n") { it.readText() }

    fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        FileOutputStream(File(folder, "reports-$name-${backup.look}.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
        }
    }

    /** Types the note on the report screen and taps Send. */
    fun typeNoteAndSend(note: String) {
        onView(withId(R.id.nodrive_report_note)).perform(replaceText(note), closeSoftKeyboard())
        backup.sleepForAnimation()
        onView(withText("Send")).perform(click())
    }

    fun waitForResult(text: String, timeoutMillis: Long = RESULT_TIMEOUT_MILLIS) {
        backup.waitFor("the message \"$text\"", timeoutMillis) {
            onView(withText(text)).check(matches(isDisplayed()))
            true
        }
    }

    companion object {
        const val ARG_TOKEN = "NODRIVE_RP_TOKEN"
        const val RESULT_TIMEOUT_MILLIS = 120_000L
        private const val PNG_QUALITY = 100
    }
}
