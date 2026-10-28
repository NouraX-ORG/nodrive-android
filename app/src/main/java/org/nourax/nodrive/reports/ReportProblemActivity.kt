/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.graphics.Paint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.owncloud.android.R
import com.owncloud.android.ui.activity.ToolbarActivity
import java.util.UUID
import kotlin.concurrent.thread

/**
 * "Report a problem": an optional note, a link to what is included, and Send. It works with the switch off, because
 * the user starts it. The report is made on this phone (scrubbed), then sent by [ReportUploadWorker] through the
 * signed-in account.
 */
class ReportProblemActivity : ToolbarActivity() {
    private lateinit var send: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var result: TextView
    private lateinit var note: TextInputEditText

    /** The upload run being watched; kept over a rotation so a result is not lost or shown twice. */
    private var watching: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.nodrive_report_problem)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.let { viewThemeUtils.files.themeActionBar(this, it, getString(R.string.nodrive_reports_report_title)) }

        note = findViewById(R.id.nodrive_report_note)
        viewThemeUtils.material.colorTextInputLayout(findViewById<TextInputLayout>(R.id.nodrive_report_note_layout))
        progress = findViewById(R.id.nodrive_report_progress)
        result = findViewById(R.id.nodrive_report_result)
        findViewById<TextView>(R.id.nodrive_report_included).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { showIncluded() }
        }
        send = findViewById<MaterialButton>(R.id.nodrive_report_send).apply {
            viewThemeUtils.material.colorMaterialButtonPrimaryFilled(this)
            setOnClickListener { sendNow() }
        }
        watching = savedInstanceState?.getString(STATE_WATCHING)
        watching?.let { watch(UUID.fromString(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_WATCHING, watching)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showIncluded() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nodrive_reports_included_title)
            .setMessage(R.string.nodrive_reports_included_body)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun sendNow() {
        val text = note.text?.toString().orEmpty()
        busy(true)
        result.visibility = View.GONE
        val app = applicationContext
        thread(name = "nodrive-report-manual") {
            val zip = ReportMaker.makeManual(app, text)
            runOnUiThread {
                when {
                    isFinishing || isDestroyed -> Unit
                    zip == null -> finished(R.string.nodrive_reports_failed)
                    !ProblemReports.isSignedIn(app) -> finished(R.string.nodrive_reports_waiting)
                    else -> watch(ReportUploadWorker.enqueue(app))
                }
            }
        }
    }

    private fun watch(id: UUID) {
        watching = id.toString()
        busy(true)
        WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null || !info.state.isFinished) return@observe
            val settings = ReportSettings.create(applicationContext)
            watching = null
            settings.markShown(id.toString())
            val message = if (info.state == WorkInfo.State.SUCCEEDED) {
                ProblemReports.messageFor(info.outputData.getString(ReportUploadWorker.OUTCOME))
            } else {
                R.string.nodrive_reports_waiting
            }
            finished(message ?: R.string.nodrive_reports_waiting)
        }
    }

    private fun finished(message: Int) {
        busy(false)
        result.setText(message)
        result.visibility = View.VISIBLE
        if (message == R.string.nodrive_reports_sent || message == R.string.nodrive_reports_saved) note.text?.clear()
    }

    private fun busy(working: Boolean) {
        send.isEnabled = !working
        progress.visibility = if (working) View.VISIBLE else View.GONE
    }

    private companion object {
        const val STATE_WATCHING = "watching"
    }
}
