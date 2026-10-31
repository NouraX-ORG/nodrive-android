/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import com.nextcloud.test.GrantTestPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A contacts round trip against the throwaway server (plan 4.9): the seeded cards become raw contacts with their group and photo, a phone
 * change of a number reaches the server card without losing its unknown line, a new phone contact becomes a new card, and a phone delete
 * first saves the card into `.Contacts-Backup` and then deletes it on the server.
 */
class DeviceSyncContactsIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = DeviceSyncTestSupport()
    private val book = "remote.php/dav/addressbooks/users/ali/contacts/"
    private val newCards = ArrayList<String>()

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.agenda.hasServer)
        support.grantPermissions()
        support.agenda.reset()
        support.signInAsAli()
        DeviceSyncAccounts.forget(support.context, support.account.name)
    }

    @After
    fun tearDown() {
        for (c in newCards) runCatching { support.serverDelete(c) }
        support.cleanUp()
        support.agenda.reset()
    }

    private fun rawIdOf(name: String): Long = support.contactsStore().rows().first { it.contact.displayName == name }.id

    private fun dataRows(rawId: Long, mime: String): List<Map<String, String?>> {
        val out = ArrayList<Map<String, String?>>()
        support.context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, null, ContactsContract.Data.RAW_CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?",
            arrayOf(rawId.toString(), mime), null
        )?.use { c ->
            while (c.moveToNext()) {
                out += (0 until c.columnCount).associate { c.getColumnName(it) to (if (c.isNull(it)) null else runCatching { c.getString(it) }.getOrNull()) }
            }
        }
        return out
    }

    private fun cardOf(name: String): String? {
        val hrefs = support.listing(book)
        for (h in hrefs) {
            val text = support.serverText(h.trimStart('/'))
            if (text.contains("FN:$name")) return text
        }
        return null
    }

    @Test
    fun aContactsRoundTrip() {
        support.switchOn("/contacts/")
        val first = support.sync(setOf("contacts"))
        assertTrue("the first sync of the address book", first.ok)
        assertTrue("the seeded cards are on the phone", first.added >= 3)

        // the group and the photo
        val family = rawIdOf("Family Member")
        val groupIds = dataRows(family, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE).mapNotNull { it[CommonDataKinds.GroupMembership.GROUP_ROW_ID]?.toLongOrNull() }
        val titles = support.contactsStore().groupTitles()
        assertTrue("member of the group Family: $groupIds $titles", groupIds.any { titles[it] == "Family" })
        val photo = rawIdOf("Photo Person")
        assertTrue("the photo is on the phone", dataRows(photo, CommonDataKinds.Photo.CONTENT_ITEM_TYPE).isNotEmpty())
        // the contact with a line that the phone has no place for
        val unknown = rawIdOf("Unknown Props")

        // a phone change of a number (as the Contacts app makes it: the raw contact becomes dirty)
        support.context.contentResolver.update(
            ContactsContract.Data.CONTENT_URI, ContentValues().apply { put(CommonDataKinds.Phone.NUMBER, "+49 30 9999999") },
            ContactsContract.Data.RAW_CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?",
            arrayOf(unknown.toString(), CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        )
        assertTrue(support.contactsStore().rows().first { it.id == unknown }.dirty)
        assertTrue(support.sync(setOf("contacts")).ok)
        val card = checkNotNull(cardOf("Unknown Props"))
        assertTrue(card, card.contains("+49 30 9999999"))
        assertTrue("the unknown line is kept: $card", card.contains("X-NOURAX-TEST:keep this"))

        // a new phone contact becomes a new card
        val id = support.newId()
        val name = "Phone Person $id"
        val rawUri = checkNotNull(support.context.contentResolver.insert(
            ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, support.account.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, support.accountType)
            }
        ))
        val rawId = ContentUris.parseId(rawUri)
        support.context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        })
        support.context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            put(CommonDataKinds.Phone.NUMBER, "+49 40 1234567")
            put(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
        })
        assertTrue(support.sync(setOf("contacts")).ok)
        val created = checkNotNull(cardOf(name))
        assertTrue(created, created.contains("+49 40 1234567"))
        newCards += support.listing(book).first { support.serverText(it.trimStart('/')).contains("FN:$name") }.trimStart('/')

        // a phone delete: the card is saved first, then deleted on the server
        val backupBefore = support.listing("remote.php/dav/files/ali/.Contacts-Backup/").filter { it.contains("removed-contacts-") }
        support.context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId), null, null)
        val outcome = support.sync(setOf("contacts"))
        assertTrue(outcome.message, outcome.ok)
        assertEquals("the server card is gone", null, cardOf(name))
        val saved = support.listing("remote.php/dav/files/ali/.Contacts-Backup/").filter { it.contains("removed-contacts-") }
        assertTrue("a removed-contacts file was saved: $saved", saved.size > backupBefore.size)
        val newest = (saved - backupBefore.toSet()).first()
        assertTrue("it holds the card", support.serverText(newest.trimStart('/')).contains("FN:$name"))
        assertFalse(support.contactsStore().rows().any { it.contact.displayName == name })
        assertEquals(1, saved.size - backupBefore.size)
    }

    /**
     * What the phone's Contacts editor offers for a NoDrive contact must reach the card and must survive a later edit of the card
     * (final review 2, S1): a phonetic name and an IM with a protocol, written the way the editor writes them (as an app, so the raw contact
     * becomes dirty), then a server edit of the same card, and the phone still has both.
     */
    @Test
    fun aPhoneticNameAndAnImProtocolReachTheCardAndSurviveALaterServerEdit() {
        support.switchOn("/contacts/")
        assertTrue(support.sync(setOf("contacts")).ok)
        val rawId = rawIdOf("Family Member")
        val resolver = support.context.contentResolver
        resolver.update(
            ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, "Kah-ya")
                put(CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, "Fah")
            },
            ContactsContract.Data.RAW_CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " = ?",
            arrayOf(rawId.toString(), CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        )
        resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, CommonDataKinds.Im.CONTENT_ITEM_TYPE)
            put(CommonDataKinds.Im.DATA, "bob.skype")
            put(CommonDataKinds.Im.PROTOCOL, CommonDataKinds.Im.PROTOCOL_SKYPE)
        })
        assertTrue(support.contactsStore().rows().first { it.id == rawId }.dirty)
        assertTrue(support.sync(setOf("contacts")).ok)
        val card = checkNotNull(cardOf("Family Member"))
        assertTrue("the phonetic family name is on the card: $card", card.contains("X-PHONETIC-LAST-NAME:Kah-ya"))
        assertTrue("the phonetic given name is on the card: $card", card.contains("X-PHONETIC-FIRST-NAME:Fah"))
        assertTrue("the Skype handle is a skype: address: $card", card.contains("IMPP:skype:bob.skype"))

        // the card is edited elsewhere (the web): the phone version is written again
        val href = support.listing(book).first { support.serverText(it.trimStart('/')).contains("FN:Family Member") }.trimStart('/')
        val edited = support.serverText(href).replace("END:VCARD", "NOTE:edited on the server
END:VCARD")
        val status = support.dav("PUT", href, edited, "text/vcard; charset=utf-8")
        assertTrue("the server accepted the edit: $status", status in 200..299)
        assertTrue(support.sync(setOf("contacts")).ok)

        val after = support.contactsStore().rows().first { it.id == rawId }
        assertEquals("edited on the server", after.contact.note)
        assertEquals("Kah-ya", after.contact.phoneticFamily)
        assertEquals("Fah", after.contact.phoneticGiven)
        assertEquals(listOf("skype:bob.skype"), after.contact.impps)
        val im = dataRows(rawId, CommonDataKinds.Im.CONTENT_ITEM_TYPE).single()
        assertEquals("the phone row is still a Skype row", CommonDataKinds.Im.PROTOCOL_SKYPE.toString(), im[CommonDataKinds.Im.PROTOCOL])
        assertEquals("bob.skype", im[CommonDataKinds.Im.DATA])
        assertFalse("the write from the server did not make it dirty", after.dirty)
    }
}
