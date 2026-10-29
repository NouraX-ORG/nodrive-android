/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
 * What the report screen shows. It lives in a [ViewModel], so a rotation while the report is made does not lose the result:
 * the background thread queues the upload whatever the screen is doing and posts the next phase here.
 */
class ReportProblemModel : ViewModel() {
    sealed interface Phase {
        /** Nothing started yet. */
        object Idle : Phase

        /** The report is being made on this phone. */
        object Making : Phase

        /** The upload run [id] was queued; [zipName] is the zip this screen made (null after the process was gone). */
        class Watching(val id: UUID, val zipName: String? = null) : Phase

        /** The end: [message] is shown. */
        class Done(@StringRes val message: Int) : Phase
    }

    private val mutablePhase = MutableLiveData<Phase>(Phase.Idle)
    val phase: LiveData<Phase> get() = mutablePhase

    /** From the main thread. */
    fun set(next: Phase) {
        mutablePhase.value = next
    }

    /** From any thread. */
    fun post(next: Phase) {
        mutablePhase.postValue(next)
    }
}

/**
 * "Report a problem": an optional note, a link to what is included, and Send. It works with the switch off, because
 * the user starts it. The report is made on this phone (scrubbed), then sent by [ReportUploadWorker] through the
 * signed-in account. Without a network the run stays queued: after a few seconds the screen says the report is saved on the
 * phone and Send works again, and the main screen shows the final message when the run ends.
 */
class ReportProblemActivity : ToolbarActivity() {
    private lateinit var send: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var result: TextView
    private lateinit var note: TextInputEditText
    private lateinit var model: ReportProblemModel

    /** The upload run being watched; kept in the saved state so a result is not lost or shown twice. */
    private var watching: String? = null

    /** The upload run this screen observes now, so one run is observed once. */
    private var observed: UUID? = null
    private var observedLive: LiveData<WorkInfo?>? = null

    private val queuedNotice = Runnable { showQueuedNotice() }

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
        model = ViewModelProvider(this)[ReportProblemModel::class.java]
        // After the whole process was gone the model is new: take the run up again from the saved state.
        if (model.phase.value is ReportProblemModel.Phase.Idle) {
            savedInstanceState?.getString(STATE_WATCHING)?.let {
                model.set(ReportProblemModel.Phase.Watching(UUID.fromString(it)))
            }
        }
        model.phase.observe(this) { render(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_WATCHING, watching)
    }

    override fun onDestroy() {
        result.removeCallbacks(queuedNotice)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render(phase: ReportProblemModel.Phase) {
        when (phase) {
            is ReportProblemModel.Phase.Idle -> Unit
            is ReportProblemModel.Phase.Making -> {
                busy(true)
                result.visibility = View.GONE
            }
            is ReportProblemModel.Phase.Watching -> watch(phase.id, phase.zipName)
            is ReportProblemModel.Phase.Done -> finished(phase.message)
        }
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
        val app = applicationContext
        val phases = model
        phases.set(ReportProblemModel.Phase.Making)
        thread(name = "nodrive-report-manual") {
            // The upload is queued here, whatever the screen does meanwhile (a rotation, leaving): the report is not lost.
            phases.post(nextPhase(app, text))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun nextPhase(app: Context, text: String): ReportProblemModel.Phase = try {
        val zip = ReportMaker.makeManual(app, text)
        when {
            zip == null -> ReportProblemModel.Phase.Done(R.string.nodrive_reports_failed)
            !ProblemReports.isSignedIn(app) -> ReportProblemModel.Phase.Done(R.string.nodrive_reports_waiting)
            else -> ReportProblemModel.Phase.Watching(ReportUploadWorker.enqueue(app), zip.name)
        }
    } catch (e: Throwable) {
        ReportProblemModel.Phase.Done(R.string.nodrive_reports_failed)
    }

    private fun watch(id: UUID, zipName: String?) {
        if (observed == id) return
        observedLive?.removeObservers(this)
        observed = id
        watching = id.toString()
        busy(true)
        result.visibility = View.GONE
        val live = WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(id)
        observedLive = live
        live.observe(this) { info ->
            if (info == null) return@observe
            when {
                info.state.isFinished -> {
                    result.removeCallbacks(queuedNotice)
                    watching = null
                    ReportSettings.create(applicationContext).markShown(id.toString())
                    val message = if (info.state == WorkInfo.State.SUCCEEDED) {
                        val outbox = Outbox(ReportFiles.of(applicationContext)).list().map { it.name }
                        ReportMessages.forScreen(info.outputData.getString(ReportUploadWorker.OUTCOME), zipName, outbox)
                    } else {
                        R.string.nodrive_reports_waiting
                    }
                    model.set(ReportProblemModel.Phase.Done(message ?: R.string.nodrive_reports_waiting))
                }
                // Queued: no network yet, or waiting to try again. Told after a short while (see showQueuedNotice).
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> {
                    result.removeCallbacks(queuedNotice)
                    result.postDelayed(queuedNotice, QUEUED_NOTICE_MILLIS)
                }
                // Running: the notice is taken back.
                else -> {
                    result.removeCallbacks(queuedNotice)
                    result.visibility = View.GONE
                    busy(true)
                }
            }
        }
    }

    /** The run is still queued: the report stays on the phone and goes out as soon as there is a network. */
    private fun showQueuedNotice() {
        busy(false)
        result.setText(R.string.nodrive_reports_waiting)
        result.visibility = View.VISIBLE
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
        const val QUEUED_NOTICE_MILLIS = 5_000L
    }
}
