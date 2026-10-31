/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.devicesync.calendar.CalendarRow
import org.nourax.nodrive.devicesync.calendar.CalendarStore
import org.nourax.nodrive.devicesync.calendar.ParsedObject
import org.nourax.nodrive.devicesync.calendar.PhoneEvent
import org.nourax.nodrive.devicesync.contacts.ContactRow
import org.nourax.nodrive.devicesync.contacts.ContactsStore
import org.nourax.nodrive.devicesync.contacts.ParsedContact
import org.nourax.nodrive.devicesync.contacts.PhoneContact
import org.nourax.nodrive.devicesync.store.AccountEntity
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.DeviceSyncDao
import org.nourax.nodrive.devicesync.store.NotSavedEntity
import org.nourax.nodrive.devicesync.store.ObjectEntity

/** The sync's own database in memory. */
class FakeDao : DeviceSyncDao {
    private val accounts = LinkedHashMap<String, AccountEntity>()
    private val collectionRows = LinkedHashMap<Pair<String, String>, CollectionEntity>()
    private val objectRows = LinkedHashMap<Pair<String, String>, ObjectEntity>()
    private val notSavedRows = ArrayList<NotSavedEntity>()
    private var nextNotSaved = 1L

    override fun putAccount(row: AccountEntity) {
        accounts[row.account] = row
    }

    override fun account(account: String): AccountEntity? = accounts[account]

    override fun putCollection(row: CollectionEntity) {
        collectionRows[row.account to row.href] = row
    }

    override fun collections(account: String): List<CollectionEntity> =
        collectionRows.values.filter { it.account == account }.sortedWith(compareBy({ it.kind }, { it.name }))

    override fun collection(account: String, href: String): CollectionEntity? = collectionRows[account to href]

    override fun deleteCollection(account: String, href: String) {
        collectionRows.remove(account to href)
    }

    override fun putObject(row: ObjectEntity) {
        objectRows[row.account to row.href] = row
    }

    override fun objects(account: String, collection: String): List<ObjectEntity> =
        objectRows.values.filter { it.account == account && it.collection == collection }

    override fun obj(account: String, href: String): ObjectEntity? = objectRows[account to href]

    override fun syncedCount(account: String, collection: String): Int = objects(account, collection).count { it.state == "synced" }

    override fun deleteObject(account: String, href: String) {
        objectRows.remove(account to href)
    }

    override fun deleteObjects(account: String, collection: String) {
        objectRows.values.removeAll { it.account == account && it.collection == collection }
    }

    override fun addNotSaved(row: NotSavedEntity): Long {
        val id = nextNotSaved++
        notSavedRows += NotSavedEntity(
            id, row.account, row.collection, row.href, row.kind, row.title, row.phoneText, row.baseText, row.createdUtc
        )
        return id
    }

    override fun notSaved(account: String): List<NotSavedEntity> = notSavedRows.filter { it.account == account }

    override fun notSavedById(id: Long): NotSavedEntity? = notSavedRows.firstOrNull { it.id == id }

    override fun deleteNotSaved(id: Long) {
        notSavedRows.removeAll { it.id == id }
    }

    override fun deleteAccount(account: String) {
        accounts.remove(account)
    }

    override fun deleteCollections(account: String) {
        collectionRows.keys.removeAll { it.first == account }
    }

    override fun deleteAllObjects(account: String) {
        objectRows.keys.removeAll { it.first == account }
    }

    override fun deleteAllNotSaved(account: String) {
        notSavedRows.removeAll { it.account == account }
    }
}

/** What the fake server lists as calendars and address books. */
class FakeCollectionSpec(val href: String, val name: String, val readOnly: Boolean = false)

/**
 * A CalDAV and CardDAV server in memory, with the behaviour the sync depends on: ETags, `If-Match` (412) and `If-None-Match: *` (412),
 * `sync-collection` with a token (changes and removals since it), `multiget`, the Drive folder of the removed contacts, and the
 * calendar and address-book lists. It refuses what the app's own write policy refuses and records every request.
 */
class FakeDavServer : AgendaHttp {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class Obj(var text: String, var etag: Int, var changedAt: Int)

    private class Folder {
        val objects = LinkedHashMap<String, Obj>()
        val removed = ArrayList<Pair<String, Int>>()
    }

    val requests = ArrayList<Req>()
    private var revision = 0
    private val folders = LinkedHashMap<String, Folder>()
    private val files = LinkedHashMap<String, String>()

    /** The Drive folder of the removed contacts refuses new files (a failed safety copy). */
    var refuseSafetyFile = false
    var calendarList: List<FakeCollectionSpec> = emptyList()
    var bookList: List<FakeCollectionSpec> = emptyList()

    /** The calendar and address-book lists answer this status (0 = as they are). */
    var listStatus = 0

    /** What `task-sync` answers: status and body. */
    var taskSyncStatus = 404
    var taskSyncBody = ""

    private fun key(path: String) = "/" + path.substringBefore('?').trimStart('/')

    private fun folderOf(href: String) = key(href).substringBeforeLast('/') + "/"

    // ---- the server's own changes (what another device or the web page does)

    fun serverPut(href: String, text: String) {
        val folder = folders.getOrPut(folderOf(href)) { Folder() }
        val k = key(href)
        revision++
        val o = folder.objects[k]
        if (o == null) folder.objects[k] = Obj(text, 1, revision) else {
            o.text = text
            o.etag++
            o.changedAt = revision
        }
    }

    fun serverDelete(href: String) {
        val folder = folders[folderOf(href)] ?: return
        revision++
        if (folder.objects.remove(key(href)) != null) folder.removed += key(href) to revision
    }

    fun has(href: String): Boolean = folders[folderOf(href)]?.objects?.containsKey(key(href)) == true

    fun text(href: String): String? = folders[folderOf(href)]?.objects?.get(key(href))?.text

    fun count(folderHref: String): Int = folders[key(folderHref)]?.objects?.size ?: 0

    fun hrefs(folderHref: String): List<String> = folders[key(folderHref)]?.objects?.keys?.toList().orEmpty()

    fun safetyFiles(): Map<String, String> = files

    fun etagOf(href: String): String = "\"v${folders.getValue(folderOf(href)).objects.getValue(key(href)).etag}\""

    fun writes(): List<Req> = requests.filter { it.method == "PUT" || it.method == "DELETE" }

    fun deletes(): List<Req> = requests.filter { it.method == "DELETE" }

    // ---- the requests of the app

    private fun reply(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        HttpResult(status, body.toByteArray(Charsets.UTF_8), headers)

    private fun xml(status: Int, inner: String) = reply(
        status,
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" " +
            "xmlns:card=\"urn:ietf:params:xml:ns:carddav\" xmlns:oc=\"http://owncloud.org/ns\">$inner</d:multistatus>"
    )

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResult {
        val text = body?.toString(Charsets.UTF_8).orEmpty()
        requests += Req(method, path, headers, text)
        if (!DeviceSyncWritePolicy.allows(method, path)) return HttpResult(0, refused = true)
        val k = key(path)
        return when {
            path.contains("task-sync") -> reply(taskSyncStatus, taskSyncBody)
            method == "PROPFIND" && k.startsWith("/remote.php/dav/calendars/") && k.count { it == '/' } == 5 -> lists(calendarList, true)
            method == "PROPFIND" && k.startsWith("/remote.php/dav/addressbooks/users/") && k.count { it == '/' } == 6 -> lists(bookList, false)
            method == "PROPFIND" -> propfindOne(k)
            method == "REPORT" && text.contains("sync-collection") -> syncCollection(k, text)
            method == "REPORT" -> multiget(k, text)
            method == "GET" -> get(k)
            method == "PUT" -> put(k, headers, text)
            method == "DELETE" -> delete(k, headers)
            method == "MKCOL" -> reply(201)
            else -> reply(405)
        }
    }

    private fun lists(list: List<FakeCollectionSpec>, calendar: Boolean): HttpResult {
        if (listStatus != 0) return reply(listStatus)
        val inner = StringBuilder()
        for (c in list) {
            val kind = if (calendar) "<c:calendar/>" else "<card:addressbook/>"
            val comps = if (calendar) "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>" else ""
            val privileges = if (c.readOnly) "<d:privilege><d:read/></d:privilege>" else "<d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege>"
            inner.append("<d:response><d:href>${c.href}</d:href><d:propstat><d:prop>")
                .append("<d:resourcetype><d:collection/>$kind</d:resourcetype><d:displayname>${escape(c.name)}</d:displayname>$comps")
                .append("<d:current-user-privilege-set>$privileges</d:current-user-privilege-set>")
                .append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        return xml(207, inner.toString())
    }

    private fun propfindOne(k: String): HttpResult {
        val o = folders[folderOf(k)]?.objects?.get(k) ?: return reply(404)
        return xml(
            207,
            "<d:response><d:href>$k</d:href><d:propstat><d:prop><d:getetag>\"v${o.etag}\"</d:getetag></d:prop>" +
                "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        )
    }

    private fun syncCollection(k: String, text: String): HttpResult {
        val folder = folders[k] ?: Folder().also { folders[k] = it }
        val token = Regex("<d:sync-token>(.*?)</d:sync-token>").find(text)?.groupValues?.get(1).orEmpty()
        val since = if (token.isEmpty()) -1 else token.removePrefix("tok-").toIntOrNull() ?: return reply(403)
        val inner = StringBuilder()
        for ((href, o) in folder.objects) {
            if (o.changedAt <= since) continue
            inner.append("<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>\"v${o.etag}\"</d:getetag></d:prop>")
                .append("<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        if (token.isNotEmpty()) {
            for ((href, at) in folder.removed) {
                if (at > since) inner.append("<d:response><d:href>$href</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>")
            }
        }
        inner.append("<d:sync-token>tok-$revision</d:sync-token>")
        return xml(207, inner.toString())
    }

    private fun multiget(k: String, text: String): HttpResult {
        val folder = folders[k]
        val calendar = text.contains("calendar-multiget")
        val inner = StringBuilder()
        for (m in Regex("<d:href>(.*?)</d:href>").findAll(text)) {
            val href = m.groupValues[1]
            val o = folder?.objects?.get(key(href))
            if (o == null) {
                inner.append("<d:response><d:href>$href</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>")
                continue
            }
            val tag = if (calendar) "c:calendar-data" else "card:address-data"
            inner.append("<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>\"v${o.etag}\"</d:getetag>")
                .append("<$tag>${escape(o.text)}</$tag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        return xml(207, inner.toString())
    }

    private fun get(k: String): HttpResult {
        val o = folders[folderOf(k)]?.objects?.get(k) ?: return reply(404)
        return reply(200, o.text, mapOf("ETag" to "\"v${o.etag}\""))
    }

    private fun put(k: String, headers: Map<String, String>, text: String): HttpResult {
        if (k.contains("/.Contacts-Backup/")) {
            if (refuseSafetyFile) return reply(500)
            if (files.containsKey(k)) return reply(412)
            files[k] = text
            return reply(201)
        }
        val folder = folders.getOrPut(folderOf(k)) { Folder() }
        val o = folder.objects[k]
        if (headers["If-None-Match"] == "*" && o != null) return reply(412)
        val ifMatch = headers["If-Match"]
        if (ifMatch != null) {
            if (o == null) return reply(412)
            if (ifMatch != "\"v${o.etag}\"") return reply(412)
        }
        revision++
        val stored = o ?: Obj(text, 0, revision).also { folder.objects[k] = it }
        stored.text = text
        stored.etag++
        stored.changedAt = revision
        return reply(201, "", mapOf("ETag" to "\"v${stored.etag}\""))
    }

    private fun delete(k: String, headers: Map<String, String>): HttpResult {
        val folder = folders[folderOf(k)]
        val o = folder?.objects?.get(k) ?: return reply(404)
        val ifMatch = headers["If-Match"]
        if (ifMatch != null && ifMatch != "\"v${o.etag}\"") return reply(412)
        revision++
        folder.objects.remove(k)
        folder.removed += k to revision
        return reply(204)
    }
}

/** The phone's Calendar in memory: rows with the dirty and deleted flags the real provider keeps. */
class FakeCalendarStore : CalendarStore {
    class Cal(val id: Long, val href: String, var readOnly: Boolean)

    val calendars = ArrayList<Cal>()
    val rowList = ArrayList<CalendarRow>()
    private var nextId = 1L

    /** `ensureCalendar` throws this (a revoked permission, no storage). */
    var ensureFails: RuntimeException? = null

    override fun calendarIds(): Map<Long, String> = calendars.associate { it.id to it.href }

    override fun ensureCalendar(href: String, displayName: String, color: Int, readOnly: Boolean): Long {
        ensureFails?.let { throw it }
        val existing = calendars.firstOrNull { it.href == href }
        if (existing != null) {
            existing.readOnly = readOnly
            return existing.id
        }
        val cal = Cal(nextId++, href, readOnly)
        calendars += cal
        return cal.id
    }

    override fun deleteCalendar(calendarId: Long) {
        calendars.removeAll { it.id == calendarId }
        rowList.removeAll { it.calendarId == calendarId }
    }

    override fun rows(calendarId: Long): List<CalendarRow> = rowList.filter { it.calendarId == calendarId }

    override fun insertObject(calendarId: Long, href: String, etag: String, parsed: ParsedObject): Long {
        val id = nextId++
        rowList += CalendarRow(id, calendarId, href, etag, false, false, null, null, parsed.master)
        for (ex in parsed.exceptions) {
            rowList += CalendarRow(nextId++, calendarId, null, "", false, false, href, id, ex)
        }
        return id
    }

    /** The guests that `replaceObject` was asked to keep, for the test of the engine. */
    var lastPreviousGuests: Set<String> = emptySet()

    override fun replaceObject(
        masterId: Long, calendarId: Long, href: String, etag: String, parsed: ParsedObject, previousGuests: Set<String>
    ): Long {
        lastPreviousGuests = previousGuests
        deleteObject(masterId, calendarId, href)
        return insertObject(calendarId, href, etag, parsed)
    }

    override fun deleteObject(masterId: Long, calendarId: Long, href: String?) {
        rowList.removeAll { it.id == masterId || it.originalId == masterId }
    }

    private fun change(id: Long, edit: (CalendarRow) -> CalendarRow) {
        val i = rowList.indexOfFirst { it.id == id }
        check(i >= 0) { "no row $id" }
        rowList[i] = edit(rowList[i])
    }

    private fun CalendarRow.copyRow(
        syncId: String? = this.syncId, etag: String = this.etag, dirty: Boolean = this.dirty, deleted: Boolean = this.deleted,
        event: PhoneEvent = this.event
    ) = CalendarRow(id, calendarId, syncId, etag, dirty, deleted, originalSyncId, originalId, event)

    override fun markSent(id: Long, href: String?, etag: String, uid: String?) = change(id) {
        it.copyRow(syncId = href ?: it.syncId, etag = etag, dirty = false, event = if (uid != null) it.event.copy(uid = uid) else it.event)
    }

    override fun link(id: Long, href: String, etag: String) = change(id) { it.copyRow(syncId = href, etag = etag) }

    // ---- what the person does in the Calendar app

    fun appEdit(id: Long, edit: (PhoneEvent) -> PhoneEvent) = change(id) { it.copyRow(dirty = true, event = edit(it.event)) }

    fun appDelete(id: Long) = change(id) { it.copyRow(dirty = true, deleted = true) }

    fun appInsert(calendarId: Long, event: PhoneEvent): Long {
        val id = nextId++
        rowList += CalendarRow(id, calendarId, null, "", true, false, null, null, event)
        return id
    }

    fun byTitle(title: String): CalendarRow? = rowList.firstOrNull { it.event.title == title && !it.deleted }

    fun live(): List<CalendarRow> = rowList.filter { !it.deleted && !it.isException }

    /** The phone's Calendar Storage was cleared. */
    fun clearStorage() {
        calendars.clear()
        rowList.clear()
    }
}

/** The phone's Contacts in memory. */
class FakeContactsStore : ContactsStore {
    val rowList = ArrayList<ContactRow>()
    private var nextId = 1L
    var ensureFails: RuntimeException? = null

    override fun ensureSettings() {
        ensureFails?.let { throw it }
    }

    override fun rows(): List<ContactRow> = rowList.toList()

    override fun insert(href: String, etag: String, parsed: ParsedContact): Long {
        val id = nextId++
        rowList += ContactRow(id, href, etag, false, false, parsed.contact)
        return id
    }

    override fun replace(id: Long, href: String, etag: String, parsed: ParsedContact) = change(id) {
        ContactRow(id, href, etag, false, false, parsed.contact)
    }

    override fun delete(id: Long) {
        rowList.removeAll { it.id == id }
    }

    private fun change(id: Long, edit: (ContactRow) -> ContactRow) {
        val i = rowList.indexOfFirst { it.id == id }
        check(i >= 0) { "no contact $id" }
        rowList[i] = edit(rowList[i])
    }

    override fun markSent(id: Long, href: String?, etag: String, uid: String?) = change(id) {
        ContactRow(id, href ?: it.syncId, etag, false, false, if (uid != null) it.contact.copy(uid = uid) else it.contact)
    }

    override fun link(id: Long, href: String, etag: String) = change(id) { ContactRow(id, href, etag, it.dirty, it.deleted, it.contact) }

    // ---- what the person does in the Contacts app

    fun appEdit(id: Long, edit: (PhoneContact) -> PhoneContact) = change(id) {
        ContactRow(id, it.syncId, it.etag, true, it.deleted, edit(it.contact))
    }

    fun appDelete(id: Long) = change(id) { ContactRow(id, it.syncId, it.etag, true, true, it.contact) }

    fun appInsert(contact: PhoneContact): Long {
        val id = nextId++
        rowList += ContactRow(id, null, "", true, false, contact)
        return id
    }

    fun byName(name: String): ContactRow? = rowList.firstOrNull { it.contact.displayName == name && !it.deleted }

    fun clearStorage() = rowList.clear()
}
