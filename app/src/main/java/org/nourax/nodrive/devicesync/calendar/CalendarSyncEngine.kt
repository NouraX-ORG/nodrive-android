/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.calendar

import androidx.core.graphics.toColorInt
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.devicesync.ConflictPlan
import org.nourax.nodrive.devicesync.DeleteGuard
import org.nourax.nodrive.devicesync.Decision
import org.nourax.nodrive.devicesync.ServerState
import org.nourax.nodrive.devicesync.SyncEnv
import org.nourax.nodrive.devicesync.dav.CollectionDelta
import org.nourax.nodrive.devicesync.dav.DavSync
import org.nourax.nodrive.devicesync.dav.WriteResult
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.NotSavedEntity
import org.nourax.nodrive.devicesync.store.ObjectEntity
import org.nourax.nodrive.devicesync.with
import java.util.UUID

/**
 * One sync of one phone calendar (plan 4.3 to 4.6). Download first (the server's changes: `sync-collection`, then `multiget`),
 * then upload (the phone's changes: new, changed and deleted rows). Only events travel; a to-do in a mixed calendar is skipped.
 * The first sync of a calendar only adds: nothing is deleted or overwritten on either side.
 */
class CalendarSyncEngine(private val env: SyncEnv, private val store: CalendarStore) {
    private val dao = env.dao
    private val account = env.account

    private class Pass(val col: CollectionEntity, val calId: Long, val first: Boolean) {
        var added = 0
        var changed = 0
        var sent = 0
        var removed = 0
        var notSaved = 0
        var waiting = 0
        var tooMany = false
        var heldBack = 0
        var complete = true

        /** The first thing that went wrong (a code, never any content). */
        var message = ""

        fun fail(why: String) {
            complete = false
            if (message.isEmpty()) message = why
        }
    }

    fun sync(collection: CollectionEntity): SyncOutcome {
        val calId = try {
            store.ensureCalendar(collection.href, collection.name, parseColor(collection.color), collection.readOnly)
        } catch (e: SecurityException) {
            AgendaLog.w(TAG, "no permission for the phone calendar")
            return SyncOutcome.failed("no_permission")
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "no phone calendar: ${e.javaClass.simpleName}")
            return SyncOutcome.failed("no_calendar_storage")
        }
        var col = collection.with(androidId = calId)
        if (collection.androidId != 0L && collection.androidId != calId) {
            // The phone's copy was made again (its Calendar Storage was cleared, or the calendar was added again). What this sync remembers
            // is not on the phone any more and the stored token would say "nothing changed", so the collection starts over: the
            // first-sync rules put everything back, and nothing is deleted.
            AgendaLog.w(TAG, "the phone calendar is a new one: the collection starts over")
            dao.deleteObjects(account, col.href)
            col = col.with(syncToken = "", firstSyncDone = false, objectCount = 0)
        }
        dao.putCollection(col)
        val pass = Pass(col, calId, !col.firstSyncDone)
        val delta = DavSync.delta(env.http, col.href, col.syncToken)
        if (!delta.ok) return SyncOutcome.failed(if (delta.networkError) "offline" else "server_${delta.status}")
        try {
            download(pass, delta)
            upload(pass)
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "calendar sync stopped: ${e.javaClass.simpleName}")
            pass.fail("exception_${e.javaClass.simpleName}")
        }
        val done = pass.complete && !pass.tooMany
        dao.putCollection(
            col.with(
                syncToken = if (done) delta.newToken else col.syncToken,
                firstSyncDone = col.firstSyncDone || done,
                objectCount = dao.syncedCount(account, col.href)
            )
        )
        return SyncOutcome(
            ok = pass.complete, added = pass.added, changed = pass.changed, sentToServer = pass.sent, removed = pass.removed,
            notSaved = pass.notSaved, tooManyDeletions = pass.tooMany, waiting = pass.waiting, message = pass.message,
            heldBack = pass.heldBack
        )
    }

    private fun parseColor(text: String): Int = try {
        text.ifEmpty { RemoteCollection.DEFAULT_COLOR }.toColorInt()
    } catch (_: IllegalArgumentException) {
        RemoteCollection.DEFAULT_COLOR.toColorInt()
    }

    private fun exceptionsFor(rows: List<CalendarRow>, master: CalendarRow): List<CalendarRow> =
        rows.filter { it.isException && (it.originalId == master.id || (master.syncId != null && it.originalSyncId == master.syncId)) }

    /** The e-mail addresses of the guests of an object as the server last had it. */
    private fun guestEmails(original: String?): Set<String> {
        if (original.isNullOrEmpty()) return emptySet()
        return EventMapper.parse(original, env.zone)?.master?.guests?.map { it.email.lowercase() }?.toSet().orEmpty()
    }

    private fun phoneObject(master: CalendarRow, exceptions: List<CalendarRow>, uid: String) =
        PhoneObject(master.event.copy(uid = uid), exceptions.filter { !it.deleted }.map { it.event })

    // ------------------------------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------------------------------

    private fun download(p: Pass, delta: CollectionDelta) {
        val known = dao.objects(account, p.col.href).associateBy { it.href }
        val toFetch = delta.changed.filter { e ->
            val k = known[e.href]
            k == null || DavSync.normalEtag(k.etag) != DavSync.normalEtag(e.etag)
        }
        val rows = store.rows(p.calId)
        val masterByHref = rows.filter { !it.isException && it.syncId != null }.associateBy { it.syncId!! }
        if (toFetch.isNotEmpty()) {
            val got = DavSync.multiget(env.http, p.col.href, toFetch.map { it.href }, calendar = true)
            if (!got.ok) p.fail("multiget_${got.status}")
            for (entry in toFetch) {
                val obj = got.objects[entry.href] ?: continue
                applyServerObject(p, obj.href, obj.etag, obj.data, known[entry.href], masterByHref[entry.href], rows)
            }
        }
        removals(p, delta, known, masterByHref, rows)
    }

    private fun putObject(p: Pass, href: String, etag: String, uid: String, original: String, rowId: Long, state: String = "synced") {
        dao.putObject(ObjectEntity(account, href, p.col.href, etag, uid, original, rowId, state))
    }

    private fun applyServerObject(
        p: Pass, href: String, etag: String, data: String, known: ObjectEntity?, masterRow: CalendarRow?, rows: List<CalendarRow>
    ) {
        val parsed = EventMapper.parse(data, env.zone)
        if (parsed == null) {
            // not an event (a to-do, or unreadable): remembered by its ETag, never shown on the phone
            putObject(p, href, etag, "", "", 0, "skipped")
            return
        }
        val uid = parsed.master.uid
        if (masterRow == null) {
            if (p.first && uid.isNotEmpty()) {
                val match = rows.firstOrNull { !it.isException && it.syncId == null && !it.deleted && it.event.uid == uid }
                if (match != null) {
                    // on both sides: linked, nothing is overwritten
                    store.link(match.id, href, etag)
                    putObject(p, href, etag, uid, data, match.id)
                    return
                }
            }
            val id = store.insertObject(p.calId, href, etag, parsed)
            putObject(p, href, etag, uid, data, id)
            p.added++
            return
        }
        val exceptions = exceptionsFor(rows, masterRow)
        val dirty = masterRow.dirty || exceptions.any { it.dirty || it.deleted }
        if (p.first && (dirty || masterRow.deleted)) {
            // nothing is overwritten in the first sync: the phone's change is looked at after it
            putObject(p, href, etag, uid, data, masterRow.id)
            return
        }
        when (ConflictPlan.decide(dirty && !masterRow.deleted, masterRow.deleted, ServerState.CHANGED)) {
            Decision.APPLY_SERVER -> {
                val id = store.replaceObject(masterRow.id, p.calId, href, etag, parsed, guestEmails(known?.original))
                putObject(p, href, etag, uid, data, id)
                p.changed++
            }
            Decision.SERVER_WINS_KEEP_PHONE_VERSION -> {
                keepPhoneVersion(p, href, known, masterRow, exceptions, uid)
                val id = store.replaceObject(masterRow.id, p.calId, href, etag, parsed, guestEmails(known?.original))
                putObject(p, href, etag, uid, data, id)
                p.changed++
            }
            Decision.KEEP_SERVER_VERSION -> {
                store.deleteObject(masterRow.id, p.calId, href)
                val id = store.insertObject(p.calId, href, etag, parsed)
                putObject(p, href, etag, uid, data, id)
                p.changed++
            }
            else -> Unit
        }
    }

    /** The phone's version lost: it is kept in "Not saved" (plan 4.5). */
    private fun keepPhoneVersion(p: Pass, href: String, known: ObjectEntity?, master: CalendarRow, exceptions: List<CalendarRow>, uid: String) {
        val base = known?.original
        val text = EventDocument.upload(base, known?.uid?.ifEmpty { null } ?: uid, phoneObject(master, exceptions, uid), env.zone, env.clock())
        dao.addNotSaved(
            NotSavedEntity(0, account, p.col.href, href, RemoteCollection.CALENDAR, master.event.title, text, base.orEmpty(), env.clock().toEpochMilli())
        )
        p.notSaved++
    }

    private fun removals(p: Pass, delta: CollectionDelta, known: Map<String, ObjectEntity>, masterByHref: Map<String, CalendarRow>, rows: List<CalendarRow>) {
        if (p.first) return
        val gone: List<String> = if (delta.full) {
            val listed = delta.changed.map { it.href }.toSet()
            known.keys.filter { it !in listed }
        } else {
            delta.removed.filter { it in known }
        }
        val phoneRemovals = ArrayList<String>()
        for (href in gone) {
            val row = masterByHref[href]
            if (row == null) {
                dao.deleteObject(account, href)
                continue
            }
            val exceptions = exceptionsFor(rows, row)
            val dirty = row.dirty || exceptions.any { it.dirty || it.deleted }
            when (ConflictPlan.decide(dirty && !row.deleted, row.deleted, ServerState.DELETED)) {
                Decision.FORGET_ROW -> {
                    store.deleteObject(row.id, p.calId, href)
                    dao.deleteObject(account, href)
                }
                // an edit beats a delete: the row stays dirty and goes up again as a new object
                Decision.UPLOAD_AS_NEW -> dao.deleteObject(account, href)
                Decision.REMOVE_FROM_PHONE -> phoneRemovals += href
                else -> Unit
            }
        }
        if (phoneRemovals.isEmpty()) return
        val total = dao.syncedCount(account, p.col.href)
        if (!DeleteGuard.allow(phoneRemovals.size, total, env.flags.overrideTooManyDeletions)) {
            // nothing is deleted; the token stays, so the next sync reports the same removals (after the person answered)
            p.tooMany = true
            p.heldBack += phoneRemovals.size
            return
        }
        for (href in phoneRemovals) {
            val row = masterByHref[href] ?: continue
            store.deleteObject(row.id, p.calId, href)
            dao.deleteObject(account, href)
            p.removed++
        }
    }

    // ------------------------------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------------------------------

    private class Deletion(val row: CalendarRow, val obj: ObjectEntity)

    /**
     * A read-only copy is read only on the phone too: a change made there (by an app that ignores the calendar's access level) can never
     * be sent, so it must not wait for ever. The server's version is put back; an edit is kept in "Not saved" first, so it is not lost
     * silently. Nothing is put back in the first sync (it only adds).
     */
    private fun restoreReadOnly(p: Pass) {
        if (p.first) return
        val rows = store.rows(p.calId)
        val known = dao.objects(account, p.col.href).associateBy { it.href }
        for (master in rows.filter { !it.isException }) {
            val exceptions = exceptionsFor(rows, master)
            if (!(master.dirty || master.deleted || exceptions.any { it.dirty || it.deleted })) continue
            val obj = master.syncId?.let { known[it] } ?: continue
            val parsed = EventMapper.parse(obj.original, env.zone) ?: continue
            if (!master.deleted) keepPhoneVersion(p, obj.href, obj, master, exceptions, obj.uid.ifEmpty { master.event.uid })
            store.deleteObject(master.id, p.calId, master.syncId)
            val id = store.insertObject(p.calId, obj.href, obj.etag, parsed)
            putObject(p, obj.href, obj.etag, obj.uid, obj.original, id)
            p.changed++
        }
    }

    private fun upload(p: Pass) {
        if (p.col.readOnly) {
            restoreReadOnly(p)
            return
        }
        val rows = store.rows(p.calId)
        val known = dao.objects(account, p.col.href).associateBy { it.href }
        val deletions = ArrayList<Deletion>()
        for (master in rows.filter { !it.isException }) {
            val exceptions = exceptionsFor(rows, master)
            if (!(master.dirty || master.deleted || exceptions.any { it.dirty || it.deleted })) continue
            val obj = master.syncId?.let { known[it] }
            if (p.first) {
                // only rows that are not on the server yet are sent; no delete, no overwrite
                if (master.deleted || obj != null) continue
                create(p, master, exceptions)
                continue
            }
            if (master.deleted) {
                if (obj == null) {
                    store.deleteObject(master.id, p.calId, master.syncId) // it never reached the server
                } else {
                    deletions += Deletion(master, obj)
                }
                continue
            }
            if (obj == null) create(p, master, exceptions) else edit(p, master, exceptions, obj)
        }
        if (deletions.isNotEmpty()) deleteOnServer(p, deletions)
    }

    private fun newHref(p: Pass): String = p.col.href.trimEnd('/') + "/" + UUID.randomUUID().toString() + ".ics"

    private fun create(p: Pass, master: CalendarRow, exceptions: List<CalendarRow>) {
        val uid = master.event.uid.ifEmpty { UUID.randomUUID().toString() }
        val text = EventDocument.upload(null, uid, phoneObject(master, exceptions, uid), env.zone, env.clock())
        var href = master.syncId ?: newHref(p)
        var result = env.dav.create(href, text, ICS_TYPE)
        if (result is WriteResult.Precondition && master.syncId == null) {
            href = newHref(p)
            result = env.dav.create(href, text, ICS_TYPE)
        }
        when (result) {
            is WriteResult.Ok -> finishSent(p, master, exceptions, href, result.etag, uid, text)
            else -> failed(p, result)
        }
    }

    private fun failed(p: Pass, result: WriteResult) {
        p.waiting++
        p.fail("not_sent_${(result as? WriteResult.Failed)?.status ?: result.javaClass.simpleName}")
        AgendaLog.w(TAG, "a phone change was not sent: ${(result as? WriteResult.Failed)?.status ?: result.javaClass.simpleName}")
    }

    private fun finishSent(p: Pass, master: CalendarRow, exceptions: List<CalendarRow>, href: String, putEtag: String, uid: String, sentText: String) {
        val got = DavSync.get(env.http, href, "text/calendar")
        val etag = got.header("ETag") ?: putEtag.ifEmpty { DavSync.etagOf(env.http, href) }
        val text = if (got.ok) got.text() else sentText
        store.markSent(master.id, href, etag, uid)
        for (ex in exceptions) {
            if (ex.deleted) store.deleteObject(ex.id, p.calId, null) else store.markSent(ex.id, null, "", null)
        }
        putObject(p, href, etag, uid, text, master.id)
        p.sent++
    }

    private fun edit(p: Pass, master: CalendarRow, exceptions: List<CalendarRow>, obj: ObjectEntity) {
        val href = checkNotNull(master.syncId)
        val uid = obj.uid.ifEmpty { master.event.uid }.ifEmpty { UUID.randomUUID().toString() }
        val phone = phoneObject(master, exceptions, uid)
        val text = EventDocument.upload(obj.original, uid, phone, env.zone, env.clock())
        if (text == obj.original) {
            // dirty, but nothing that the server holds is different
            store.markSent(master.id, href, obj.etag, uid)
            for (ex in exceptions) if (ex.deleted) store.deleteObject(ex.id, p.calId, null) else store.markSent(ex.id, null, "", null)
            return
        }
        when (val result = env.dav.replace(href, obj.etag, text, ICS_TYPE)) {
            is WriteResult.Ok -> finishSent(p, master, exceptions, href, result.etag, uid, text)
            is WriteResult.Precondition -> conflict(p, master, exceptions, obj, uid, text)
            is WriteResult.Gone -> {
                // the server deleted it while the phone edited it: the edit wins, the object goes up again
                val again = env.dav.create(href, text, ICS_TYPE)
                if (again is WriteResult.Ok) finishSent(p, master, exceptions, href, again.etag, uid, text) else failed(p, again)
            }
            else -> failed(p, result)
        }
    }

    /** 412: the object changed on the server since the phone started its edit. The server wins; the phone version is kept (plan 4.5). */
    private fun conflict(p: Pass, master: CalendarRow, exceptions: List<CalendarRow>, obj: ObjectEntity, uid: String, phoneText: String) {
        val href = obj.href
        val got = DavSync.get(env.http, href, "text/calendar")
        if (got.status == 404) {
            val again = env.dav.create(href, phoneText, ICS_TYPE)
            if (again is WriteResult.Ok) finishSent(p, master, exceptions, href, again.etag, uid, phoneText) else failed(p, again)
            return
        }
        if (!got.ok) {
            failed(p, WriteResult.Failed(got.status, got.networkError))
            return
        }
        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
        val parsed = EventMapper.parse(got.text(), env.zone)
        val guests = guestEmails(obj.original)
        dao.addNotSaved(
            NotSavedEntity(0, account, p.col.href, href, RemoteCollection.CALENDAR, master.event.title, phoneText, obj.original, env.clock().toEpochMilli())
        )
        p.notSaved++
        if (parsed == null) {
            putObject(p, href, etag, uid, got.text(), master.id)
            return
        }
        val id = store.replaceObject(master.id, p.calId, href, etag, parsed, guests)
        putObject(p, href, etag, parsed.master.uid, got.text(), id)
        p.changed++
    }

    private fun deleteOnServer(p: Pass, deletions: List<Deletion>) {
        if (env.flags.discardLocalDeletions) {
            // the person said: undo what was deleted on the phone; the objects come back from what the server had
            for (d in deletions) restore(p, d.row, d.obj)
            return
        }
        val total = dao.syncedCount(account, p.col.href)
        if (!DeleteGuard.allow(deletions.size, total, env.flags.overrideTooManyDeletions)) {
            p.tooMany = true
            p.heldBack += deletions.size
            return
        }
        for (d in deletions) {
            val href = checkNotNull(d.row.syncId)
            when (val result = env.dav.delete(href, d.obj.etag)) {
                is WriteResult.Ok, is WriteResult.Gone -> {
                    store.deleteObject(d.row.id, p.calId, href)
                    dao.deleteObject(account, href)
                    p.sent++
                }
                is WriteResult.Precondition -> {
                    // an edit on the server against the delete on the phone: the edit wins, the object stays
                    val got = DavSync.get(env.http, href, "text/calendar")
                    val parsed = if (got.ok) EventMapper.parse(got.text(), env.zone) else null
                    if (parsed == null) {
                        failed(p, WriteResult.Failed(got.status, got.networkError))
                    } else {
                        store.deleteObject(d.row.id, p.calId, href)
                        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
                        val id = store.insertObject(p.calId, href, etag, parsed)
                        putObject(p, href, etag, parsed.master.uid, got.text(), id)
                        p.changed++
                    }
                }
                else -> failed(p, result)
            }
        }
    }

    private fun restore(p: Pass, row: CalendarRow, obj: ObjectEntity) {
        val parsed = EventMapper.parse(obj.original, env.zone) ?: return
        store.deleteObject(row.id, p.calId, row.syncId)
        val id = store.insertObject(p.calId, obj.href, obj.etag, parsed)
        putObject(p, obj.href, obj.etag, obj.uid, obj.original, id)
        p.changed++
    }

    // ------------------------------------------------------------------------------------------
    // "Apply my change again" (plan 4.5)
    // ------------------------------------------------------------------------------------------

    /** Puts the phone's version of a lost change on top of the server's current version and sends it. True when it was sent. */
    fun applyAgain(entry: NotSavedEntity): Boolean {
        val href = entry.href
        val phone = EventMapper.parse(entry.phoneText, env.zone) ?: return false
        val base = EventMapper.parse(entry.baseText, env.zone)
        val got = DavSync.get(env.http, href, "text/calendar")
        if (got.status == 404) return env.dav.create(href, entry.phoneText, ICS_TYPE) is WriteResult.Ok
        if (!got.ok) return false
        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
        val server = EventMapper.parse(got.text(), env.zone) ?: return false
        val merged = if (base == null) phone.master else EventMapper.rebase(base.master, phone.master, server.master)
        // The changed occurrences of the saved phone version go on top too (when the phone changed them), not only the event itself.
        val phoneChangedOccurrences = if (base == null) phone.exceptions.isNotEmpty() else phone.exceptions != base.exceptions
        val occurrences = if (phoneChangedOccurrences) phone.exceptions else server.exceptions
        val text = EventDocument.upload(got.text(), server.master.uid, PhoneObject(merged.copy(uid = server.master.uid), occurrences), env.zone, env.clock())
        return env.dav.replace(href, etag, text, ICS_TYPE) is WriteResult.Ok
    }

    private companion object {
        const val TAG = "DeviceSyncCalendar"
        const val ICS_TYPE = "text/calendar; charset=utf-8"
    }
}
