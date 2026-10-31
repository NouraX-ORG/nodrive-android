/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.client.jobs.JobInfo
import com.owncloud.android.MainApp
import com.owncloud.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.dav.Dav
import org.nourax.nodrive.agenda.dav.DavXml
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.devicesync.dav.DavSync

/** What the backup flow needs from the phone, upstream's backup jobs and the server (a seam: the tests of the flow use a fake). */
interface BackupSteps {
    /** The status of the last listing that could not be read (for the reason of a failure). */
    val lastStatus: Int

    /** True when the phone has contacts of another account to save. Throws when the phone's contacts can not be read. */
    fun phoneHasContacts(): Boolean

    /** True when the phone has calendars of another account to save. Throws when the phone's calendars can not be read. */
    fun phoneHasCalendars(): Boolean

    /** The names in the folder of one of the two backups; empty when it is not there. Null when the server could not be asked. */
    fun names(contacts: Boolean): Set<String>?

    /** Runs upstream's backup job of one kind; true when it ended well. */
    suspend fun runJob(contacts: Boolean): Boolean
}

/**
 * The flow of the backup before the first sync: the two jobs, then until a new file of each is on the server, at most [timeoutMs].
 * The flow is [BackupGate.Result.Done] only then. Any failure (offline, a job that failed or never ended, no new file in time) is a
 * [BackupGate.Result.Failed], and the first sync does not start.
 */
class BackupFlow(
    private val steps: BackupSteps,
    private val timeoutMs: Long,
    private val pollMs: Long = POLL_MS,
    private val runningText: Int = 0,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** Runs the backup; [progress] is told what is going on (a string resource). */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(progress: (Int) -> Unit = {}): BackupGate.Result {
        // everything that reads the phone or the server runs off the main thread
        val (wantContacts, wantCalendars) = try {
            withContext(Dispatchers.IO) { steps.phoneHasContacts() } to withContext(Dispatchers.IO) { steps.phoneHasCalendars() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A phone that can not be read is not "nothing to save": the first sync does not start without its backup.
            AgendaLog.w(TAG, "backup gate: the phone could not be read: ${e.javaClass.simpleName}")
            return BackupGate.Result.Failed("phone_unreadable")
        }
        if (!wantContacts && !wantCalendars) return BackupGate.Result.Done
        val beforeContacts = if (wantContacts) names(true) ?: return BackupGate.Result.Failed("offline_${steps.lastStatus}") else emptySet()
        val beforeCalendars = if (wantCalendars) names(false) ?: return BackupGate.Result.Failed("offline_${steps.lastStatus}") else emptySet()
        AgendaLog.i(TAG, "backup gate starts: contacts=$wantContacts calendars=$wantCalendars before=${beforeContacts.size}/${beforeCalendars.size}")
        progress(runningText)
        if (wantContacts && !steps.runJob(true)) return BackupGate.Result.Failed("contacts_job")
        AgendaLog.i(TAG, "backup gate: the contacts job ended")
        if (wantCalendars && !steps.runJob(false)) return BackupGate.Result.Failed("calendar_job")
        AgendaLog.i(TAG, "backup gate: the calendar job ended")
        // The jobs only queue the uploads: wait until each folder holds a new file.
        val deadline = clock() + timeoutMs
        var contactsDone = !wantContacts
        var calendarsDone = !wantCalendars
        var polls = 0
        while (clock() < deadline) {
            if (polls++ % LOG_EVERY == 0) AgendaLog.i(TAG, "backup gate waits for the uploads: contacts=$contactsDone calendars=$calendarsDone")
            if (!contactsDone) contactsDone = names(true)?.let { (it - beforeContacts).isNotEmpty() } ?: false
            if (!calendarsDone) calendarsDone = names(false)?.let { (it - beforeCalendars).isNotEmpty() } ?: false
            if (contactsDone && calendarsDone) return BackupGate.Result.Done
            delay(pollMs)
        }
        return BackupGate.Result.Failed("upload")
    }

    private suspend fun names(contacts: Boolean): Set<String>? = withContext(Dispatchers.IO) { steps.names(contacts) }

    private companion object {
        const val TAG = "DeviceSyncBackup"
        const val POLL_MS = 2000L
        const val LOG_EVERY = 15
    }
}

/**
 * The backup before the first sync (plan 4.6): upstream's own "Contacts and calendar backup" saves the phone's contacts and calendars
 * into NoDrive first. It never deletes (both uploads rename on a name clash; the contacts expiry is -1, which a safety test keeps). The
 * gate waits for the two jobs and then until a new file of each is on the server, at most [timeoutMs] ([BackupFlow]). Only then may the
 * first sync start.
 */
class BackupGate(
    private val context: Context,
    private val jobs: BackgroundJobManager,
    private val user: User,
    private val http: AgendaHttp,
    private val davUser: String,
    private val timeoutMs: Long = FIFTEEN_MINUTES
) : BackupSteps {
    @Volatile
    private var status = 0

    override val lastStatus: Int get() = status

    sealed class Result {
        object Done : Result()
        class Failed(val reason: String) : Result()
    }

    private fun filesPath(folderResource: Int): String =
        "remote.php/dav/files/${DavSync.encodeSegment(davUser)}" + context.getString(folderResource).trimEnd('/') + "/"

    // A read error is thrown (not "nothing on the phone"): [BackupFlow] turns it into a failed backup, so the person can try again.
    override fun phoneHasContacts(): Boolean {
        val type = MainApp.getAccountType(context)
        return context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            ContactsContract.RawContacts.DELETED + " = 0 AND (" + ContactsContract.RawContacts.ACCOUNT_TYPE + " IS NULL OR " +
                ContactsContract.RawContacts.ACCOUNT_TYPE + " != ?)",
            arrayOf(type), null
        )?.use { it.count > 0 } ?: false
    }

    override fun phoneHasCalendars(): Boolean {
        val type = MainApp.getAccountType(context)
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.ACCOUNT_TYPE + " != ?", arrayOf(type), null
        )?.use { it.count > 0 } ?: false
    }

    override fun names(contacts: Boolean): Set<String>? {
        val path = filesPath(if (contacts) R.string.contacts_backup_folder else R.string.calendar_backup_folder)
        val reply = http.send(
            "PROPFIND", path, mapOf("Depth" to "1"), DavXml.propfindBody(listOf(Dav.NS_DAV to "getetag")), "application/xml; charset=utf-8"
        )
        if (reply.status == 404) return emptySet()
        if (!reply.multistatus) {
            AgendaLog.w(TAG, "backup gate: the listing of a backup folder answered ${reply.status} (refused=${reply.refused}, network=${reply.networkError})")
            status = reply.status
            return null
        }
        return DavXml.parseMultistatus(reply.body).responses.map { it.href }.filter { !it.endsWith("/") }.toSet()
    }

    override suspend fun runJob(contacts: Boolean): Boolean =
        awaitJob { if (contacts) jobs.startImmediateContactsBackup(user) else jobs.startImmediateCalendarBackup(user) }

    private suspend fun awaitJob(start: () -> LiveData<JobInfo?>): Boolean = withContext(Dispatchers.Main) {
        val live = start()
        val finished = CompletableDeferred<Boolean>()
        val observer = Observer<JobInfo?> { info ->
            when (info?.state) {
                "SUCCEEDED" -> finished.complete(true)
                "FAILED", "CANCELLED" -> finished.complete(false)
                else -> Unit
            }
        }
        live.observeForever(observer)
        try {
            withTimeoutOrNull(timeoutMs) { finished.await() } ?: false
        } finally {
            live.removeObserver(observer)
        }
    }

    /** Runs the backup; [progress] is told what is going on (a string resource). */
    suspend fun run(progress: (Int) -> Unit = {}): Result =
        BackupFlow(this, timeoutMs, runningText = R.string.nodrive_device_sync_backup_running).run(progress)

    private companion object {
        const val TAG = "DeviceSyncBackup"
        const val FIFTEEN_MINUTES = 15L * 60L * 1000L
    }
}
