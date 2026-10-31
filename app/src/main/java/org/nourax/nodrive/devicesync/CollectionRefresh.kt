/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.nourax.nodrive.devicesync.calendar.CalendarRow
import org.nourax.nodrive.devicesync.calendar.CalendarStore
import org.nourax.nodrive.devicesync.contacts.ContactsStore
import org.nourax.nodrive.devicesync.dav.DavSync
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.DeviceSyncDao

/** What asking the server for the calendars and address books came to. */
class RefreshResult(
    /** At least one of the two lists could be read. */
    val ok: Boolean,
    /** The calendars could be read, and NoGantt's copy calendar is known (or there is none): only then are calendars offered or synced. */
    val calendarsKnown: Boolean
)

/** The rows of the calendars and address books of an account, kept in line with what the server lists. */
object CollectionRefresh {
    /**
     * Asks the server and keeps the rows (what is chosen stays chosen). A collection that the server no longer lists is dropped when it
     * was never chosen, and marked [RemoteCollection.OFF_GONE] when it was (a deleted or unshared calendar): it is not synced any more and
     * does not hold up Stop syncing. NoGantt's copy calendar is never offered; one that was chosen is marked [RemoteCollection.OFF_TASK_COPY].
     */
    fun run(env: SyncEnv): RefreshResult {
        val copy = env.dav.ganttCopy()
        val calendars = if (copy.known) env.dav.calendars(if (copy.calendarUri.isEmpty()) emptySet() else setOf(copy.calendarUri)) else null
        val books = env.dav.addressBooks()
        if (calendars == null && books == null) return RefreshResult(false, false)
        merge(env, calendars, RemoteCollection.CALENDAR, copy.calendarUri)
        merge(env, books, RemoteCollection.CONTACTS, "")
        return RefreshResult(true, calendars != null)
    }

    private fun merge(env: SyncEnv, list: List<RemoteCollection>?, kind: String, taskCopyUri: String) {
        if (list == null) return
        val have = env.dao.collections(env.account).filter { it.kind == kind }.associateBy { it.href }
        for (r in list) {
            val old = have[r.href]
            env.dao.putCollection(
                CollectionEntity(
                    env.account, r.href, kind, r.name, r.colorText, r.readOnly, r.offReason, old?.chosen ?: false,
                    old?.syncToken.orEmpty(), old?.androidId ?: 0L, old?.firstSyncDone ?: false, old?.objectCount ?: 0
                )
            )
        }
        val listed = list.map { it.href }.toSet()
        for (old in have.values) {
            if (old.href in listed) continue
            if (!old.chosen) {
                env.dao.deleteCollection(env.account, old.href)
                continue
            }
            val reason = if (taskCopyUri.isNotEmpty() && DavSync.lastSegment(old.href) == taskCopyUri) RemoteCollection.OFF_TASK_COPY else RemoteCollection.OFF_GONE
            env.dao.putCollection(old.with(offReason = reason))
        }
    }

    /** The chosen collections that are still there and may be synced. */
    fun live(all: List<CollectionEntity>): List<CollectionEntity> =
        all.filter { it.chosen && it.offReason != RemoteCollection.OFF_GONE && it.offReason != RemoteCollection.OFF_TASK_COPY }
}

/** Taking the phone's copy of one calendar or address book away. */
object CollectionRemoval {
    /**
     * Removes the phone's copy of the collection and forgets what the sync knew of it. The server keeps everything. A collection that the
     * server no longer lists is forgotten for good; any other stays in the list, not chosen.
     */
    fun takeAway(dao: DeviceSyncDao, account: String, c: CollectionEntity, calendars: CalendarStore, contacts: ContactsStore) {
        if (c.kind == RemoteCollection.CALENDAR) {
            for ((id, href) in calendars.calendarIds()) if (href == c.href) calendars.deleteCalendar(id)
        } else {
            // the contacts of one address book are linked to its addresses
            for (row in contacts.rows()) if (row.syncId?.startsWith(c.href) == true) contacts.delete(row.id)
        }
        dao.deleteObjects(account, c.href)
        if (c.offReason == RemoteCollection.OFF_GONE) {
            dao.deleteCollection(account, c.href)
        } else {
            dao.putCollection(c.with(chosen = false, syncToken = "", androidId = 0, firstSyncDone = false, objectCount = 0))
        }
    }
}

/** How many changes on the phone are waiting to be sent (what the status screen says and what Stop syncing looks at). */
object WaitingCount {
    /**
     * Only a change that can still be sent counts: one in a calendar or address book that is chosen, still on the server and writable.
     * A change in a read-only copy is put back by the sync, one in a collection that is gone can not be sent (see [lost]), and a new
     * contact that is empty (or that has no writable address book to go to) is never sent. It does not depend on the list of the
     * server: it is the same while the server can not be asked.
     */
    fun of(calendars: CalendarStore, contacts: ContactsStore, collections: List<CollectionEntity>): Int {
        val writable = CollectionRefresh.live(collections).filter { !it.readOnly }
        val books = writable.filter { it.kind == RemoteCollection.CONTACTS }.map { it.href }
        return count(calendars, contacts, writable.filter { it.kind == RemoteCollection.CALENDAR }.map { it.href }.toSet(), books, books.isNotEmpty())
    }

    /**
     * The unsent phone changes that taking the collections away would take with them: those in a chosen calendar or address book that the
     * server no longer lists (a deleted or unshared one). They can not be sent, and removing the copy removes them. [hrefs] limits it to
     * some collections (null = all).
     */
    fun lost(calendars: CalendarStore, contacts: ContactsStore, collections: List<CollectionEntity>, hrefs: Set<String>? = null): Int {
        val gone = collections.filter { it.chosen && it.offReason == RemoteCollection.OFF_GONE && (hrefs == null || it.href in hrefs) }
        return count(
            calendars, contacts, gone.filter { it.kind == RemoteCollection.CALENDAR }.map { it.href }.toSet(),
            gone.filter { it.kind == RemoteCollection.CONTACTS }.map { it.href }, false
        )
    }

    /** An event counts once: when it, or one of its changed occurrences, was changed or deleted (the sync sends them together). */
    private fun changed(rows: List<CalendarRow>, master: CalendarRow): Boolean =
        master.dirty || master.deleted || rows.any {
            it.isException && (it.dirty || it.deleted) &&
                (it.originalId == master.id || (master.syncId != null && it.originalSyncId == master.syncId))
        }

    private fun count(calendars: CalendarStore, contacts: ContactsStore, calendarHrefs: Set<String>, books: List<String>, newContacts: Boolean): Int {
        var n = 0
        if (calendarHrefs.isNotEmpty()) {
            for ((id, href) in calendars.calendarIds()) {
                if (href !in calendarHrefs) continue
                val rows = calendars.rows(id)
                n += rows.count { !it.isException && changed(rows, it) }
            }
        }
        if (books.isNotEmpty() || newContacts) {
            for (row in contacts.rows()) {
                if (!(row.dirty || row.deleted)) continue
                val sid = row.syncId
                if (sid == null) {
                    if (newContacts && !row.deleted && !row.isBlank()) n++
                } else if (books.any { sid.startsWith(it) }) {
                    n++
                }
            }
        }
        return n
    }
}
