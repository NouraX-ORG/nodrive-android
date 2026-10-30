/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import org.nourax.nodrive.devicesync.ProviderScope

/** One raw contact of ours on the phone. */
class ContactRow(
    val id: Long,
    val syncId: String?,
    val etag: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val contact: PhoneContact
)

/**
 * Reads and writes the phone's contacts of the account through [ProviderScope]: the only class that writes to the Contacts provider.
 * Writes are made as the sync adapter, so a change is not marked dirty (it comes from the server).
 */
class ContactsProviderStore(private val resolver: ContentResolver, private val scope: ProviderScope) {
    private class Builder {
        var display = ""
        var given = ""
        var family = ""
        var middle = ""
        var prefix = ""
        var suffix = ""
        val phones = ArrayList<TypedValue>()
        val emails = ArrayList<TypedValue>()
        val addresses = ArrayList<PhoneAddress>()
        var company = ""
        var department = ""
        var title = ""
        var note = ""
        val urls = ArrayList<String>()
        var birthday = ""
        var anniversary = ""
        val nicknames = ArrayList<String>()
        val impps = ArrayList<String>()
        val groupIds = ArrayList<Long>()
    }

    // ---- groups and settings ---------------------------------------------------------------

    /** Title of every group of ours by its row id. */
    fun groupTitles(): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        resolver.query(
            scope.groups, arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            scope.accountSelection(ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE) + " AND " +
                ContactsContract.Groups.DELETED + " = 0",
            scope.accountArgs, null
        )?.use { while (it.moveToNext()) out[it.getLong(0)] = it.getString(1).orEmpty() }
        return out
    }

    /** The row ids of the groups with these titles; a group that is not there yet is added. */
    fun ensureGroups(titles: List<String>): List<Long> {
        if (titles.isEmpty()) return emptyList()
        val existing = groupTitles().entries.associate { it.value to it.key }.toMutableMap()
        val out = ArrayList<Long>()
        for (title in titles) {
            val id = existing[title] ?: run {
                val v = ContentValues()
                v.put(ContactsContract.Groups.ACCOUNT_NAME, scope.accountName)
                v.put(ContactsContract.Groups.ACCOUNT_TYPE, scope.accountType)
                v.put(ContactsContract.Groups.TITLE, title)
                v.put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                v.put(ContactsContract.Groups.SHOULD_SYNC, 1)
                val uri = resolver.insert(scope.groups, v)
                val newId = if (uri == null) -1L else ContentUris.parseId(uri)
                existing[title] = newId
                newId
            }
            if (id >= 0) out += id
        }
        return out
    }

    /** Makes the phone show the contacts of this account (also those that are in no group). */
    fun ensureSettings() {
        var present = false
        resolver.query(
            scope.settings, arrayOf(ContactsContract.Settings.ACCOUNT_NAME),
            scope.accountSelection(ContactsContract.Settings.ACCOUNT_NAME, ContactsContract.Settings.ACCOUNT_TYPE), scope.accountArgs, null
        )?.use { present = it.count > 0 }
        if (present) return
        val v = ContentValues()
        v.put(ContactsContract.Settings.ACCOUNT_NAME, scope.accountName)
        v.put(ContactsContract.Settings.ACCOUNT_TYPE, scope.accountType)
        v.put(ContactsContract.Settings.SHOULD_SYNC, 1)
        v.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        resolver.insert(scope.settings, v)
    }

    // ---- reading ---------------------------------------------------------------------------

    private fun s(c: Cursor, name: String): String = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) "" else c.getString(it) }

    private fun readData(): Map<Long, Builder> {
        val out = HashMap<Long, Builder>()
        resolver.query(
            scope.data, null,
            scope.accountSelection(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
            scope.accountArgs, null
        )?.use { c ->
            val rawIndex = c.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeIndex = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (c.moveToNext()) {
                val b = out.getOrPut(c.getLong(rawIndex)) { Builder() }
                when (c.getString(mimeIndex)) {
                    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        b.display = s(c, CommonDataKinds.StructuredName.DISPLAY_NAME)
                        b.given = s(c, CommonDataKinds.StructuredName.GIVEN_NAME)
                        b.family = s(c, CommonDataKinds.StructuredName.FAMILY_NAME)
                        b.middle = s(c, CommonDataKinds.StructuredName.MIDDLE_NAME)
                        b.prefix = s(c, CommonDataKinds.StructuredName.PREFIX)
                        b.suffix = s(c, CommonDataKinds.StructuredName.SUFFIX)
                    }
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        b.phones += TypedValue(s(c, CommonDataKinds.Phone.NUMBER).trim(), s(c, CommonDataKinds.Phone.TYPE).toIntOrNull() ?: 7)
                    CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        b.emails += TypedValue(s(c, CommonDataKinds.Email.ADDRESS).trim(), s(c, CommonDataKinds.Email.TYPE).toIntOrNull() ?: 3)
                    CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> b.addresses += PhoneAddress(
                        s(c, CommonDataKinds.StructuredPostal.STREET), s(c, CommonDataKinds.StructuredPostal.CITY),
                        s(c, CommonDataKinds.StructuredPostal.REGION), s(c, CommonDataKinds.StructuredPostal.POSTCODE),
                        s(c, CommonDataKinds.StructuredPostal.COUNTRY), s(c, CommonDataKinds.StructuredPostal.TYPE).toIntOrNull() ?: 3
                    )
                    CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        b.company = s(c, CommonDataKinds.Organization.COMPANY)
                        b.department = s(c, CommonDataKinds.Organization.DEPARTMENT)
                        b.title = s(c, CommonDataKinds.Organization.TITLE)
                    }
                    CommonDataKinds.Note.CONTENT_ITEM_TYPE -> b.note = s(c, CommonDataKinds.Note.NOTE)
                    CommonDataKinds.Website.CONTENT_ITEM_TYPE -> b.urls += s(c, CommonDataKinds.Website.URL).trim()
                    CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val date = ContactMapper.normalDate(s(c, CommonDataKinds.Event.START_DATE))
                        when (s(c, CommonDataKinds.Event.TYPE).toIntOrNull()) {
                            CommonDataKinds.Event.TYPE_BIRTHDAY -> b.birthday = date
                            CommonDataKinds.Event.TYPE_ANNIVERSARY -> b.anniversary = date
                            else -> Unit
                        }
                    }
                    CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> b.nicknames += s(c, CommonDataKinds.Nickname.NAME).trim()
                    CommonDataKinds.Im.CONTENT_ITEM_TYPE -> b.impps += s(c, CommonDataKinds.Im.DATA).trim()
                    CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE ->
                        s(c, CommonDataKinds.GroupMembership.GROUP_ROW_ID).toLongOrNull()?.let { b.groupIds += it }
                }
            }
        }
        return out
    }

    /** Every raw contact of ours (also the deleted ones) with its data. */
    fun rows(): List<ContactRow> {
        val data = readData()
        val titles = groupTitles()
        val out = ArrayList<ContactRow>()
        resolver.query(
            scope.rawContacts,
            arrayOf(
                ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1,
                ContactsContract.RawContacts.SYNC2, ContactsContract.RawContacts.DIRTY, ContactsContract.RawContacts.DELETED
            ),
            scope.accountSelection(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE),
            scope.accountArgs, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val b = data[id] ?: Builder()
                val contact = PhoneContact(
                    uid = c.getString(3).orEmpty(),
                    displayName = b.display, given = b.given, family = b.family, middle = b.middle, prefix = b.prefix, suffix = b.suffix,
                    phones = b.phones.filter { it.value.isNotEmpty() }, emails = b.emails.filter { it.value.isNotEmpty() },
                    addresses = b.addresses.filter { it != PhoneAddress(type = it.type) },
                    company = b.company, department = b.department, title = b.title, note = b.note,
                    urls = b.urls.filter { it.isNotEmpty() }, birthday = b.birthday, anniversary = b.anniversary,
                    nicknames = b.nicknames.filter { it.isNotEmpty() }, impps = b.impps.filter { it.isNotEmpty() },
                    groups = b.groupIds.mapNotNull { titles[it] }.distinct()
                )
                out += ContactRow(id, c.getString(1), c.getString(2).orEmpty(), c.getInt(4) == 1, c.getInt(5) == 1, contact)
            }
        }
        return out
    }

    // ---- writing (as the sync adapter) -----------------------------------------------------

    private fun dataValues(contact: PhoneContact, photo: ByteArray?, groupIds: List<Long>): List<ContentValues> {
        val out = ArrayList<ContentValues>()
        fun row(mime: String, fill: ContentValues.() -> Unit) {
            val v = ContentValues()
            v.put(ContactsContract.Data.MIMETYPE, mime)
            v.fill()
            out += v
        }
        row(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            put(CommonDataKinds.StructuredName.GIVEN_NAME, contact.given)
            put(CommonDataKinds.StructuredName.FAMILY_NAME, contact.family)
            put(CommonDataKinds.StructuredName.MIDDLE_NAME, contact.middle)
            put(CommonDataKinds.StructuredName.PREFIX, contact.prefix)
            put(CommonDataKinds.StructuredName.SUFFIX, contact.suffix)
        }
        for (p in contact.phones) row(CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Phone.NUMBER, p.value)
            put(CommonDataKinds.Phone.TYPE, p.type)
        }
        for (e in contact.emails) row(CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Email.ADDRESS, e.value)
            put(CommonDataKinds.Email.TYPE, e.type)
        }
        for (a in contact.addresses) row(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.StructuredPostal.STREET, a.street)
            put(CommonDataKinds.StructuredPostal.CITY, a.city)
            put(CommonDataKinds.StructuredPostal.REGION, a.region)
            put(CommonDataKinds.StructuredPostal.POSTCODE, a.postcode)
            put(CommonDataKinds.StructuredPostal.COUNTRY, a.country)
            put(CommonDataKinds.StructuredPostal.TYPE, a.type)
        }
        if (contact.company.isNotEmpty() || contact.title.isNotEmpty() || contact.department.isNotEmpty()) {
            row(CommonDataKinds.Organization.CONTENT_ITEM_TYPE) {
                put(CommonDataKinds.Organization.COMPANY, contact.company)
                put(CommonDataKinds.Organization.DEPARTMENT, contact.department)
                put(CommonDataKinds.Organization.TITLE, contact.title)
                put(CommonDataKinds.Organization.TYPE, CommonDataKinds.Organization.TYPE_WORK)
            }
        }
        if (contact.note.isNotEmpty()) row(CommonDataKinds.Note.CONTENT_ITEM_TYPE) { put(CommonDataKinds.Note.NOTE, contact.note) }
        for (u in contact.urls) row(CommonDataKinds.Website.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Website.URL, u)
            put(CommonDataKinds.Website.TYPE, CommonDataKinds.Website.TYPE_OTHER)
        }
        if (contact.birthday.isNotEmpty()) row(CommonDataKinds.Event.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Event.START_DATE, contact.birthday)
            put(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
        }
        if (contact.anniversary.isNotEmpty()) row(CommonDataKinds.Event.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Event.START_DATE, contact.anniversary)
            put(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_ANNIVERSARY)
        }
        for (n in contact.nicknames) row(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Nickname.NAME, n)
            put(CommonDataKinds.Nickname.TYPE, CommonDataKinds.Nickname.TYPE_DEFAULT)
        }
        for (i in contact.impps) row(CommonDataKinds.Im.CONTENT_ITEM_TYPE) {
            put(CommonDataKinds.Im.DATA, i)
            put(CommonDataKinds.Im.PROTOCOL, CommonDataKinds.Im.PROTOCOL_CUSTOM)
            put(CommonDataKinds.Im.CUSTOM_PROTOCOL, "IMPP")
        }
        for (g in groupIds) row(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE) { put(CommonDataKinds.GroupMembership.GROUP_ROW_ID, g) }
        if (photo != null && photo.isNotEmpty()) row(CommonDataKinds.Photo.CONTENT_ITEM_TYPE) { put(CommonDataKinds.Photo.PHOTO, photo) }
        return out
    }

    private fun rawValues(href: String?, etag: String?, uid: String?): ContentValues {
        val v = ContentValues()
        if (href != null) v.put(ContactsContract.RawContacts.SOURCE_ID, href)
        if (etag != null) v.put(ContactsContract.RawContacts.SYNC1, etag)
        if (uid != null) v.put(ContactsContract.RawContacts.SYNC2, uid)
        v.put(ContactsContract.RawContacts.DIRTY, 0)
        v.put(ContactsContract.RawContacts.DELETED, 0)
        return v
    }

    /** Adds the contact as a new raw contact; returns its id. */
    fun insert(href: String, etag: String, parsed: ParsedContact): Long {
        ensureSettings()
        val groupIds = ensureGroups(parsed.contact.groups)
        val ops = ArrayList<ContentProviderOperation>()
        val raw = rawValues(href, etag, parsed.contact.uid)
        ops += ContentProviderOperation.newInsert(scope.rawContacts)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, scope.accountName)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, scope.accountType)
            .withValues(raw).build()
        for (v in dataValues(parsed.contact, parsed.photo, groupIds)) {
            ops += ContentProviderOperation.newInsert(scope.data).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValues(v).build()
        }
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return ContentUris.parseId(checkNotNull(results[0].uri) { "the phone did not add the contact" })
    }

    /** Puts the server's version into an existing raw contact: its data rows are written again. */
    fun replace(id: Long, href: String, etag: String, parsed: ParsedContact) {
        val groupIds = ensureGroups(parsed.contact.groups)
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newDelete(scope.data).withSelection(ContactsContract.Data.RAW_CONTACT_ID + " = ?", arrayOf(id.toString())).build()
        ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(scope.rawContacts, id)).withValues(rawValues(href, etag, parsed.contact.uid)).build()
        for (v in dataValues(parsed.contact, parsed.photo, groupIds)) {
            ops += ContentProviderOperation.newInsert(scope.data).withValue(ContactsContract.Data.RAW_CONTACT_ID, id).withValues(v).build()
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    /** Removes the raw contact from the phone for good (a sync adapter delete). */
    fun delete(id: Long) {
        resolver.delete(ContentUris.withAppendedId(scope.rawContacts, id), null, null)
    }

    /** The row was sent: clean, linked to its address, with the ETag. */
    fun markSent(id: Long, href: String?, etag: String, uid: String?) {
        resolver.update(ContentUris.withAppendedId(scope.rawContacts, id), rawValues(href, etag, uid), null, null)
    }

    /** Only the ETag and link of a row (a first-sync link). */
    fun link(id: Long, href: String, etag: String) {
        val v = ContentValues()
        v.put(ContactsContract.RawContacts.SOURCE_ID, href)
        v.put(ContactsContract.RawContacts.SYNC1, etag)
        resolver.update(ContentUris.withAppendedId(scope.rawContacts, id), v, null, null)
    }

    /** Removes every raw contact and group of our account from the phone (Stop syncing, after the final upload). */
    fun deleteAll() {
        resolver.delete(
            scope.rawContacts,
            scope.accountSelection(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE), scope.accountArgs
        )
        resolver.delete(
            scope.groups,
            scope.accountSelection(ContactsContract.Groups.ACCOUNT_NAME, ContactsContract.Groups.ACCOUNT_TYPE), scope.accountArgs
        )
    }
}
