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

/**
 * The backup before the first sync (plan 4.6): upstream's own "Contacts and calendar backup" saves the phone's contacts and calendars
 * into NoDrive first. It never deletes (both uploads rename on a name clash; the contacts expiry is -1, which a safety test keeps). The
 * gate waits for the two jobs and then until a new file of each is on the server, at most [timeoutMs]. Only then may the first sync start.
 */
class BackupGate(
    private val context: Context,
    private val jobs: BackgroundJobManager,
    private val user: User,
    private val http: AgendaHttp,
    private val davUser: String,
    private val timeoutMs: Long = FIFTEEN_MINUTES
) {
    /** The status of the last listing that could not be read (for the reason of a failure). */
    @Volatile
    private var lastStatus = 0

    sealed class Result {
        object Done : Result()
        class Failed(val reason: String) : Result()
    }

    private fun filesPath(folderResource: Int): String =
        "remote.php/dav/files/${DavSync.encodeSegment(davUser)}" + context.getString(folderResource).trimEnd('/') + "/"

    /** True when the phone has contacts of another account to save. */
    fun phoneHasContacts(): Boolean = try {
        val type = MainApp.getAccountType(context)
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            ContactsContract.RawContacts.DELETED + " = 0 AND (" + ContactsContract.RawContacts.ACCOUNT_TYPE + " IS NULL OR " +
                ContactsContract.RawContacts.ACCOUNT_TYPE + " != ?)",
            arrayOf(type), null
        )?.use { it.count > 0 } ?: false
    } catch (e: RuntimeException) {
        AgendaLog.w(TAG, "contacts not readable: ${e.javaClass.simpleName}")
        false
    }

    /** True when the phone has calendars of another account to save. */
    fun phoneHasCalendars(): Boolean = try {
        val type = MainApp.getAccountType(context)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.ACCOUNT_TYPE + " != ?", arrayOf(type), null
        )?.use { it.count > 0 } ?: false
    } catch (e: RuntimeException) {
        AgendaLog.w(TAG, "calendars not readable: ${e.javaClass.simpleName}")
        false
    }

    /** The names in a folder of the Drive; empty when it is not there. Null when the server could not be asked. */
    private fun names(path: String): Set<String>? {
        val reply = http.send(
            "PROPFIND", path, mapOf("Depth" to "1"), DavXml.propfindBody(listOf(Dav.NS_DAV to "getetag")), "application/xml; charset=utf-8"
        )
        if (reply.status == 404) return emptySet()
        if (!reply.multistatus) {
            AgendaLog.w(TAG, "backup gate: the listing of a backup folder answered ${reply.status} (refused=${reply.refused}, network=${reply.networkError})")
            lastStatus = reply.status
            return null
        }
        return DavXml.parseMultistatus(reply.body).responses.map { it.href }.filter { !it.endsWith("/") }.toSet()
    }

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
    suspend fun run(progress: (Int) -> Unit = {}): Result {
        val wantContacts = phoneHasContacts()
        val wantCalendars = phoneHasCalendars()
        if (!wantContacts && !wantCalendars) return Result.Done
        val contactsPath = filesPath(R.string.contacts_backup_folder)
        val calendarPath = filesPath(R.string.calendar_backup_folder)
        val beforeContacts = if (wantContacts) names(contactsPath) ?: return Result.Failed("offline_$lastStatus") else emptySet()
        val beforeCalendars = if (wantCalendars) names(calendarPath) ?: return Result.Failed("offline_$lastStatus") else emptySet()
        AgendaLog.i(TAG, "backup gate starts: contacts=$wantContacts calendars=$wantCalendars before=${beforeContacts.size}/${beforeCalendars.size}")
        progress(R.string.nodrive_device_sync_backup_running)
        if (wantContacts && !awaitJob { jobs.startImmediateContactsBackup(user) }) return Result.Failed("contacts_job")
        AgendaLog.i(TAG, "backup gate: the contacts job ended")
        if (wantCalendars && !awaitJob { jobs.startImmediateCalendarBackup(user) }) return Result.Failed("calendar_job")
        AgendaLog.i(TAG, "backup gate: the calendar job ended")
        // The jobs only queue the uploads: wait until each folder holds a new file.
        val deadline = System.currentTimeMillis() + timeoutMs
        var contactsDone = !wantContacts
        var calendarsDone = !wantCalendars
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            if (polls++ % LOG_EVERY == 0) AgendaLog.i(TAG, "backup gate waits for the uploads: contacts=$contactsDone calendars=$calendarsDone")
            if (!contactsDone) contactsDone = names(contactsPath)?.let { (it - beforeContacts).isNotEmpty() } ?: false
            if (!calendarsDone) calendarsDone = names(calendarPath)?.let { (it - beforeCalendars).isNotEmpty() } ?: false
            if (contactsDone && calendarsDone) return Result.Done
            delay(POLL_MS)
        }
        return Result.Failed("upload")
    }

    private companion object {
        const val TAG = "DeviceSyncBackup"
        const val FIFTEEN_MINUTES = 15L * 60L * 1000L
        const val POLL_MS = 2000L
        const val LOG_EVERY = 15
    }
}
