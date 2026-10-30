/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

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
 * One sync of one address book (plan 4.3 to 4.6): the same rules as the calendar engine. Differences: before a CardDAV delete the
 * server's last version of the contact is saved into `.Contacts-Backup` (the server has no trash bin for contacts), and the photo
 * only travels from the server to the phone. The first sync only adds.
 */
class ContactsSyncEngine(private val env: SyncEnv, private val store: ContactsProviderStore, private val safety: RemovedContactsCopy) {
    private val dao = env.dao
    private val account = env.account

    private class Pass(val col: CollectionEntity, val first: Boolean) {
        var added = 0
        var changed = 0
        var sent = 0
        var removed = 0
        var notSaved = 0
        var waiting = 0
        var tooMany = false
        var complete = true

        /** The first thing that went wrong (a code, never any content). */
        var message = ""

        fun fail(why: String) {
            complete = false
            if (message.isEmpty()) message = why
        }
    }

    fun sync(collection: CollectionEntity): SyncOutcome {
        try {
            store.ensureSettings()
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "no phone contacts storage: ${e.javaClass.simpleName}")
            return SyncOutcome.failed("no_contacts_storage")
        }
        val pass = Pass(collection, !collection.firstSyncDone)
        val delta = DavSync.delta(env.http, collection.href, collection.syncToken)
        if (!delta.ok) return SyncOutcome.failed(if (delta.networkError) "offline" else "server_${delta.status}")
        try {
            download(pass, delta)
            upload(pass)
        } catch (e: RuntimeException) {
            AgendaLog.w(TAG, "contacts sync stopped: ${e.javaClass.simpleName}")
            pass.fail("exception_${e.javaClass.simpleName}: ${e.message.orEmpty().take(MESSAGE_LIMIT)}")
        }
        val done = pass.complete && !pass.tooMany
        dao.putCollection(
            collection.with(
                syncToken = if (done) delta.newToken else collection.syncToken,
                firstSyncDone = collection.firstSyncDone || done,
                objectCount = dao.syncedCount(account, collection.href)
            )
        )
        return SyncOutcome(
            ok = pass.complete, added = pass.added, changed = pass.changed, sentToServer = pass.sent, removed = pass.removed,
            notSaved = pass.notSaved, tooManyDeletions = pass.tooMany, waiting = pass.waiting, message = pass.message
        )
    }

    /** Rows of this address book: our account has one raw-contact space, so each address book owns the rows linked to its addresses. */
    private fun ownRows(p: Pass, all: List<ContactRow>, known: Map<String, ObjectEntity>): List<ContactRow> =
        all.filter { r -> r.syncId == null || r.syncId.startsWith(p.col.href) || known.containsKey(r.syncId) }

    private fun putObject(p: Pass, href: String, etag: String, uid: String, original: String, rowId: Long, state: String = "synced") {
        dao.putObject(ObjectEntity(account, href, p.col.href, etag, uid, original, rowId, state))
    }

    // ------------------------------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------------------------------

    private fun download(p: Pass, delta: CollectionDelta) {
        val known = dao.objects(account, p.col.href).associateBy { it.href }
        val toFetch = delta.changed.filter { e ->
            val k = known[e.href]
            k == null || DavSync.normalEtag(k.etag) != DavSync.normalEtag(e.etag)
        }
        val rows = ownRows(p, store.rows(), known)
        val byHref = rows.filter { it.syncId != null }.associateBy { it.syncId!! }
        if (toFetch.isNotEmpty()) {
            val got = DavSync.multiget(env.http, p.col.href, toFetch.map { it.href }, calendar = false)
            if (!got.ok) p.fail("multiget_${got.status}")
            for (entry in toFetch) {
                val obj = got.objects[entry.href] ?: continue
                applyServerObject(p, obj.href, obj.etag, obj.data, known[entry.href], byHref[entry.href], rows)
            }
        }
        removals(p, delta, known, byHref)
    }

    private fun applyServerObject(p: Pass, href: String, etag: String, data: String, known: ObjectEntity?, row: ContactRow?, rows: List<ContactRow>) {
        val parsed = ContactMapper.parse(data)
        if (parsed == null) {
            putObject(p, href, etag, "", "", 0, "skipped")
            return
        }
        val uid = parsed.contact.uid
        if (row == null) {
            if (p.first && uid.isNotEmpty()) {
                val match = rows.firstOrNull { it.syncId == null && !it.deleted && it.contact.uid == uid }
                if (match != null) {
                    store.link(match.id, href, etag)
                    putObject(p, href, etag, uid, data, match.id)
                    return
                }
            }
            val id = store.insert(href, etag, parsed)
            putObject(p, href, etag, uid, data, id)
            p.added++
            return
        }
        if (p.first && (row.dirty || row.deleted)) {
            putObject(p, href, etag, uid, data, row.id)
            return
        }
        when (ConflictPlan.decide(row.dirty && !row.deleted, row.deleted, ServerState.CHANGED)) {
            Decision.APPLY_SERVER -> {
                store.replace(row.id, href, etag, parsed)
                putObject(p, href, etag, uid, data, row.id)
                p.changed++
            }
            Decision.SERVER_WINS_KEEP_PHONE_VERSION -> {
                keepPhoneVersion(p, href, known, row)
                store.replace(row.id, href, etag, parsed)
                putObject(p, href, etag, uid, data, row.id)
                p.changed++
            }
            Decision.KEEP_SERVER_VERSION -> {
                store.delete(row.id)
                val id = store.insert(href, etag, parsed)
                putObject(p, href, etag, uid, data, id)
                p.changed++
            }
            else -> Unit
        }
    }

    private fun phoneVersionText(known: ObjectEntity?, row: ContactRow): String {
        val original = known?.original.orEmpty()
        val uid = known?.uid?.ifEmpty { null } ?: row.contact.uid.ifEmpty { UUID.randomUUID().toString() }
        val base = if (original.isNotEmpty()) ContactMapper.parse(original)?.contact else null
        return if (original.isNotEmpty() && base != null) {
            ContactMapper.patch(original, base, row.contact.copy(uid = uid), env.clock())
        } else {
            ContactMapper.create(uid, row.contact, env.clock())
        }
    }

    private fun keepPhoneVersion(p: Pass, href: String, known: ObjectEntity?, row: ContactRow) {
        dao.addNotSaved(
            NotSavedEntity(
                0, account, p.col.href, href, RemoteCollection.CONTACTS, row.contact.displayName, phoneVersionText(known, row),
                known?.original.orEmpty(), env.clock().toEpochMilli()
            )
        )
        p.notSaved++
    }

    private fun removals(p: Pass, delta: CollectionDelta, known: Map<String, ObjectEntity>, byHref: Map<String, ContactRow>) {
        if (p.first) return
        val gone: List<String> = if (delta.full) {
            val listed = delta.changed.map { it.href }.toSet()
            known.keys.filter { it !in listed }
        } else {
            delta.removed.filter { it in known }
        }
        val phoneRemovals = ArrayList<String>()
        for (href in gone) {
            val row = byHref[href]
            if (row == null) {
                dao.deleteObject(account, href)
                continue
            }
            when (ConflictPlan.decide(row.dirty && !row.deleted, row.deleted, ServerState.DELETED)) {
                Decision.FORGET_ROW -> {
                    store.delete(row.id)
                    dao.deleteObject(account, href)
                }
                Decision.UPLOAD_AS_NEW -> dao.deleteObject(account, href)
                Decision.REMOVE_FROM_PHONE -> phoneRemovals += href
                else -> Unit
            }
        }
        if (phoneRemovals.isEmpty()) return
        if (!DeleteGuard.allow(phoneRemovals.size, dao.syncedCount(account, p.col.href), env.flags.overrideTooManyDeletions)) {
            p.tooMany = true
            return
        }
        for (href in phoneRemovals) {
            val row = byHref[href] ?: continue
            store.delete(row.id)
            dao.deleteObject(account, href)
            p.removed++
        }
    }

    // ------------------------------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------------------------------

    private class Deletion(val row: ContactRow, val obj: ObjectEntity)

    private fun isEmpty(c: PhoneContact): Boolean =
        c.displayName.isEmpty() && c.given.isEmpty() && c.family.isEmpty() && c.phones.isEmpty() && c.emails.isEmpty() &&
            c.company.isEmpty() && c.addresses.isEmpty() && c.note.isEmpty()

    private fun upload(p: Pass) {
        if (p.col.readOnly) return
        val known = dao.objects(account, p.col.href).associateBy { it.href }
        val rows = ownRows(p, store.rows(), known)
        val deletions = ArrayList<Deletion>()
        for (row in rows) {
            if (!(row.dirty || row.deleted)) continue
            val obj = row.syncId?.let { known[it] }
            if (p.first) {
                if (row.deleted || obj != null) continue
                create(p, row)
                continue
            }
            if (row.deleted) {
                if (obj == null || row.syncId == null) store.delete(row.id) else deletions += Deletion(row, obj)
                continue
            }
            if (obj == null) create(p, row) else edit(p, row, obj)
        }
        if (deletions.isNotEmpty()) deleteOnServer(p, deletions)
    }

    private fun newHref(p: Pass): String = p.col.href.trimEnd('/') + "/" + UUID.randomUUID().toString() + ".vcf"

    private fun failed(p: Pass, result: WriteResult) {
        p.waiting++
        p.fail("not_sent_${(result as? WriteResult.Failed)?.status ?: result.javaClass.simpleName}")
        AgendaLog.w(TAG, "a phone change was not sent: ${(result as? WriteResult.Failed)?.status ?: result.javaClass.simpleName}")
    }

    private fun create(p: Pass, row: ContactRow) {
        if (isEmpty(row.contact)) return // an empty card (a Contacts app that was left open) is not sent
        val uid = row.contact.uid.ifEmpty { UUID.randomUUID().toString() }
        val text = ContactMapper.create(uid, row.contact, env.clock())
        var href = row.syncId ?: newHref(p)
        var result = env.dav.create(href, text, VCF_TYPE)
        if (result is WriteResult.Precondition && row.syncId == null) {
            href = newHref(p)
            result = env.dav.create(href, text, VCF_TYPE)
        }
        when (result) {
            is WriteResult.Ok -> finishSent(p, row, href, result.etag, uid, text)
            else -> failed(p, result)
        }
    }

    private fun finishSent(p: Pass, row: ContactRow, href: String, putEtag: String, uid: String, sentText: String) {
        val got = DavSync.get(env.http, href, "text/vcard")
        val etag = got.header("ETag") ?: putEtag.ifEmpty { DavSync.etagOf(env.http, href) }
        store.markSent(row.id, href, etag, uid)
        putObject(p, href, etag, uid, if (got.ok) got.text() else sentText, row.id)
        p.sent++
    }

    private fun edit(p: Pass, row: ContactRow, obj: ObjectEntity) {
        val href = checkNotNull(row.syncId)
        val base = ContactMapper.parse(obj.original)?.contact
        val uid = obj.uid.ifEmpty { row.contact.uid }
        val text = if (base == null) obj.original else ContactMapper.patch(obj.original, base, row.contact.copy(uid = uid), env.clock())
        if (text == obj.original) {
            store.markSent(row.id, href, obj.etag, uid.ifEmpty { null })
            return
        }
        when (val result = env.dav.replace(href, obj.etag, text, VCF_TYPE)) {
            is WriteResult.Ok -> finishSent(p, row, href, result.etag, uid, text)
            is WriteResult.Precondition -> conflict(p, row, obj, text)
            is WriteResult.Gone -> {
                val again = env.dav.create(href, text, VCF_TYPE)
                if (again is WriteResult.Ok) finishSent(p, row, href, again.etag, uid, text) else failed(p, again)
            }
            else -> failed(p, result)
        }
    }

    private fun conflict(p: Pass, row: ContactRow, obj: ObjectEntity, phoneText: String) {
        val href = obj.href
        val got = DavSync.get(env.http, href, "text/vcard")
        if (got.status == 404) {
            val again = env.dav.create(href, phoneText, VCF_TYPE)
            if (again is WriteResult.Ok) finishSent(p, row, href, again.etag, obj.uid, phoneText) else failed(p, again)
            return
        }
        if (!got.ok) {
            failed(p, WriteResult.Failed(got.status, got.networkError))
            return
        }
        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
        dao.addNotSaved(
            NotSavedEntity(0, account, p.col.href, href, RemoteCollection.CONTACTS, row.contact.displayName, phoneText, obj.original, env.clock().toEpochMilli())
        )
        p.notSaved++
        val parsed = ContactMapper.parse(got.text())
        if (parsed == null) {
            putObject(p, href, etag, obj.uid, got.text(), row.id)
            return
        }
        store.replace(row.id, href, etag, parsed)
        putObject(p, href, etag, parsed.contact.uid, got.text(), row.id)
        p.changed++
    }

    private fun deleteOnServer(p: Pass, deletions: List<Deletion>) {
        if (env.flags.discardLocalDeletions) {
            for (d in deletions) {
                val parsed = ContactMapper.parse(d.obj.original) ?: continue
                store.delete(d.row.id)
                val id = store.insert(d.obj.href, d.obj.etag, parsed)
                putObject(p, d.obj.href, d.obj.etag, d.obj.uid, d.obj.original, id)
                p.changed++
            }
            return
        }
        if (!DeleteGuard.allow(deletions.size, dao.syncedCount(account, p.col.href), env.flags.overrideTooManyDeletions)) {
            p.tooMany = true
            return
        }
        // The safety copy first: if it is not saved, nothing is deleted on the server (the rows stay deleted on the phone and are retried).
        if (!safety.save(deletions.map { it.obj.original }, env.clock())) {
            p.waiting += deletions.size
            p.fail("safety_copy_not_saved")
            AgendaLog.w(TAG, "the copy of the removed contacts was not saved: ${deletions.size} deletes wait")
            return
        }
        for (d in deletions) {
            val href = checkNotNull(d.row.syncId)
            when (val result = env.dav.delete(href, d.obj.etag)) {
                is WriteResult.Ok, is WriteResult.Gone -> {
                    store.delete(d.row.id)
                    dao.deleteObject(account, href)
                    p.sent++
                }
                is WriteResult.Precondition -> {
                    val got = DavSync.get(env.http, href, "text/vcard")
                    val parsed = if (got.ok) ContactMapper.parse(got.text()) else null
                    if (parsed == null) {
                        failed(p, WriteResult.Failed(got.status, got.networkError))
                    } else {
                        store.delete(d.row.id)
                        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
                        val id = store.insert(href, etag, parsed)
                        putObject(p, href, etag, parsed.contact.uid, got.text(), id)
                        p.changed++
                    }
                }
                else -> failed(p, result)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // "Apply my change again" (plan 4.5)
    // ------------------------------------------------------------------------------------------

    fun applyAgain(entry: NotSavedEntity): Boolean {
        val href = entry.href
        val phone = ContactMapper.parse(entry.phoneText)?.contact ?: return false
        val base = ContactMapper.parse(entry.baseText)?.contact
        val got = DavSync.get(env.http, href, "text/vcard")
        if (got.status == 404) return env.dav.create(href, entry.phoneText, VCF_TYPE) is WriteResult.Ok
        if (!got.ok) return false
        val etag = got.header("ETag") ?: DavSync.etagOf(env.http, href)
        val server = ContactMapper.parse(got.text())?.contact ?: return false
        val merged = if (base == null) phone else ContactMapper.rebase(base, phone, server)
        val text = ContactMapper.patch(got.text(), server, merged.copy(uid = server.uid), env.clock())
        if (text == got.text()) return true
        return env.dav.replace(href, etag, text, VCF_TYPE) is WriteResult.Ok
    }

    private companion object {
        const val TAG = "DeviceSyncContacts"
        const val VCF_TYPE = "text/vcard; charset=utf-8"
        const val MESSAGE_LIMIT = 100
    }
}
