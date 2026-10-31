/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.devicesync.contacts.ContactMapper
import org.nourax.nodrive.devicesync.contacts.ContactsSyncEngine
import org.nourax.nodrive.devicesync.contacts.ParsedContact
import org.nourax.nodrive.devicesync.contacts.PhoneContact
import org.nourax.nodrive.devicesync.contacts.PhotoBytes
import org.nourax.nodrive.devicesync.contacts.RemovedContactsCopy
import org.nourax.nodrive.devicesync.contacts.TypedValue
import org.nourax.nodrive.devicesync.dav.CardDavClient
import org.nourax.nodrive.devicesync.model.RemoteCollection
import org.nourax.nodrive.devicesync.model.SyncOutcome
import org.nourax.nodrive.devicesync.store.CollectionEntity
import java.time.Instant
import java.time.ZoneOffset

/**
 * The contacts engine against a server in memory and a phone address book in memory (plan 4.5, 4.6): the first sync only adds, the
 * removed-contacts copy comes before every delete and a failed copy holds the deletes back, an edit beats a delete in both directions,
 * discarding the local deletions puts a contact back, read-only books are put back, a contact that was started and left empty is
 * cleared, a new contact goes to one address book only, and a photo set on the phone reaches the server.
 */
class ContactsEngineTest {
    private val account = "ali@drive.test"
    private val book = "/remote.php/dav/addressbooks/users/ali/contacts/"
    private val book2 = "/remote.php/dav/addressbooks/users/ali/work-contacts/"
    private val server = FakeDavServer()
    private val dao = FakeDao()
    private val store = FakeContactsStore()
    private var flags = SyncFlags()
    private var newBook: String? = null

    private fun env() = SyncEnv(
        account, server, CardDavClient(server, "ali"), dao, ZoneOffset.UTC, { Instant.parse("2026-11-01T10:00:00Z") }, flags, newBook
    )

    private fun choose(href: String = book, readOnly: Boolean = false, name: String = "Contacts") {
        dao.putCollection(CollectionEntity(account, href, RemoteCollection.CONTACTS, name, "", readOnly, "", true, "", 0L, false, 0))
    }

    private fun sync(href: String = book): SyncOutcome {
        if (dao.collection(account, href) == null) choose(href)
        val e = env()
        return ContactsSyncEngine(e, store, RemovedContactsCopy(server, "ali")).sync(checkNotNull(dao.collection(account, href)))
    }

    private fun card(uid: String, name: String, vararg extra: String): String =
        (listOf("BEGIN:VCARD", "VERSION:3.0", "UID:$uid", "FN:$name", "N:Person;$name;;;", "TEL;TYPE=CELL:+49 30 111") + extra + "END:VCARD")
            .joinToString("\r\n") + "\r\n"

    private fun putCard(name: String, uid: String, shown: String, folder: String = book, vararg extra: String) =
        server.serverPut(folder + name, card(uid, shown, *extra))

    private fun phoneContact(name: String, phone: String = "+49 40 222") =
        PhoneContact(displayName = name, given = name, phones = listOf(TypedValue(phone, 2)))

    // ---- the first sync only adds

    @Test
    fun theFirstSyncOnlyAddsAndNothingIsDeleted() {
        putCard("a.vcf", "ua", "Server Person")
        store.appInsert(phoneContact("Phone Person"))
        val old = store.insert(book + "old.vcf", "\"v1\"", ParsedContact(phoneContact("Old Address").copy(uid = "uold")))
        store.appEdit(old) { it }
        val gone = store.insert(book + "was.vcf", "\"v1\"", ParsedContact(phoneContact("Deleted Before").copy(uid = "uwas")))
        store.appDelete(gone)
        val before = store.rowList.size

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue(server.deletes().isEmpty())
        assertTrue(store.byName("Server Person") != null)
        assertTrue(server.hrefs(book).any { server.text(it)!!.contains("FN:Phone Person") })
        assertTrue(server.text(book + "old.vcf")!!.contains("FN:Old Address"))
        assertTrue("a delete waits for the first sync to be over", store.rowList.any { it.id == gone && it.deleted })
        assertTrue(store.rowList.size >= before)
        assertTrue(server.has(book + "a.vcf"))
    }

    // ---- the copy of the removed contacts

    @Test
    fun aFailedCopyOfTheRemovedContactsKeepsTheDeletesWaitingAndTheNextSyncSavesItFirst() {
        putCard("a.vcf", "ua", "Doomed Person")
        assertTrue(sync().ok)
        store.appDelete(checkNotNull(store.byName("Doomed Person")).id)
        server.refuseSafetyFile = true

        val held = sync()

        assertFalse(held.ok)
        assertEquals(1, held.waiting)
        assertTrue(held.message, held.message.startsWith("safety_copy_not_saved"))
        assertTrue("nothing was deleted on the server", server.deletes().isEmpty())
        assertTrue(server.has(book + "a.vcf"))
        assertTrue("the contact is still marked deleted on the phone, so it is retried", store.rowList.single().deleted)
        assertEquals("and it counts as waiting", 1, WaitingCount.of(FakeCalendarStore(), store, dao.collections(account)))

        server.refuseSafetyFile = false
        val retried = sync()

        assertTrue(retried.message, retried.ok)
        val saved = server.safetyFiles().values.single()
        assertTrue("the last version of the card was saved", saved.contains("FN:Doomed Person"))
        val putAt = server.requests.indexOfLast { it.method == "PUT" && it.path.contains(".Contacts-Backup") }
        val deleteAt = server.requests.indexOfFirst { it.method == "DELETE" }
        assertTrue("the copy comes before the delete", putAt in 0 until deleteAt)
    }

    // ---- an edit beats a delete, in both directions

    @Test
    fun aPhoneEditAgainstAServerDeleteUploadsTheContactAgain() {
        putCard("a.vcf", "ua", "Edited Person")
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byName("Edited Person")).id) { it.copy(phones = it.phones + TypedValue("+49 99 9999", 2)) }
        server.serverDelete(book + "a.vcf")

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("the edit wins", server.has(book + "a.vcf"))
        assertTrue(server.text(book + "a.vcf")!!.contains("+49 99 9999"))
        assertTrue(server.deletes().isEmpty())
        assertFalse(checkNotNull(store.byName("Edited Person")).dirty)
    }

    @Test
    fun aPhoneDeleteAgainstAServerEditKeepsTheServerVersion() {
        putCard("a.vcf", "ua", "Kept Person")
        assertTrue(sync().ok)
        store.appDelete(checkNotNull(store.byName("Kept Person")).id)
        putCard("a.vcf", "ua", "Kept Person Renamed")

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("back on the phone with the server's name", store.byName("Kept Person Renamed") != null)
        assertTrue(store.rowList.none { it.deleted })
        assertTrue(server.deletes().isEmpty())
        assertTrue(server.safetyFiles().isEmpty())
    }

    @Test
    fun discardingTheLocalDeletionsPutsTheContactBack() {
        putCard("a.vcf", "ua", "Brought Back")
        assertTrue(sync().ok)
        store.appDelete(checkNotNull(store.byName("Brought Back")).id)
        flags = SyncFlags(discardLocalDeletions = true)

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue(store.byName("Brought Back") != null)
        assertTrue(server.deletes().isEmpty())
        assertTrue("no copy was needed: nothing is deleted", server.safetyFiles().isEmpty())
    }

    // ---- a read-only address book

    @Test
    fun aChangeInAReadOnlyBookIsPutBackAndKeptInNotSaved() {
        choose(readOnly = true)
        putCard("a.vcf", "ua", "Shared Person")
        assertTrue(sync().ok)
        store.appEdit(checkNotNull(store.byName("Shared Person")).id) { it.copy(phones = emptyList()) }
        assertEquals(0, WaitingCount.of(FakeCalendarStore(), store, dao.collections(account)))

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        val back = checkNotNull(store.byName("Shared Person"))
        assertFalse(back.dirty)
        assertEquals("the server's number is back", listOf("+49 30 111"), back.contact.phones.map { it.value })
        assertTrue(server.writes().isEmpty())
        assertEquals(1, dao.notSaved(account).size)
    }

    // ---- an empty contact

    @Test
    fun aContactThatWasStartedAndLeftEmptyIsClearedNotKeptWaiting() {
        assertTrue(sync().ok)
        val id = store.appInsert(PhoneContact())
        assertEquals("an empty card can not be sent, so it does not wait", 0, WaitingCount.of(FakeCalendarStore(), store, dao.collections(account)))

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue(server.writes().isEmpty())
        assertFalse("the row is clean", store.rowList.single { it.id == id }.dirty)
    }

    @Test
    fun aContactWithOnlyANicknameABirthdayAWebsiteOrAPhotoIsSentNotCalledEmpty() {
        assertFalse(PhoneContact(nicknames = listOf("Bo")).isBlank())
        assertFalse(PhoneContact(birthday = "1990-05-12").isBlank())
        assertFalse(PhoneContact(urls = listOf("https://example.test")).isBlank())
        assertFalse(PhoneContact(photo = PhotoBytes(byteArrayOf(1, 2, 3))).isBlank())
        assertFalse(PhoneContact(relations = listOf(TypedValue("Sam", 14))).isBlank())
        assertTrue(PhoneContact(groups = listOf("Family")).isBlank())
        assertTrue(sync().ok)
        store.appInsert(PhoneContact(nicknames = listOf("Bo")))

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertTrue("a card with a nickname went to the server", server.hrefs(book).any { server.text(it)!!.contains("NICKNAME:Bo") })
    }

    // ---- a new contact goes to one address book

    @Test
    fun aNewPhoneContactGoesToTheDefaultAddressBookOnly() {
        choose(book, name = "Contacts")
        choose(book2, name = "Work contacts")
        newBook = book
        assertTrue(sync(book).ok)
        assertTrue(sync(book2).ok)
        store.appInsert(phoneContact("Only Once"))

        assertTrue(sync(book).ok)
        assertTrue(sync(book2).ok)

        assertEquals(1, server.count(book))
        assertEquals(0, server.count(book2))
        assertEquals(1, server.writes().size)
    }

    // ---- a phone copy that is gone

    @Test
    fun aContactsStorageThatWasClearedStartsOverAndTheContactsComeBack() {
        putCard("a.vcf", "ua", "First Person")
        putCard("b.vcf", "ub", "Second Person")
        assertTrue(sync().ok)
        store.clearStorage()

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertEquals(2, outcome.added)
        assertEquals(2, store.rowList.size)
        assertTrue(server.writes().isEmpty())
    }

    @Test
    fun aRevokedPermissionIsToldApartFromMissingStorage() {
        store.ensureFails = SecurityException("no")
        assertEquals("no_permission", sync().message)
        store.ensureFails = IllegalStateException("no storage")
        assertEquals("no_contacts_storage", sync().message)
    }

    // ---- the photo

    @Test
    fun aPhotoSetOnThePhoneReachesTheServerAndARelationToo() {
        putCard("a.vcf", "ua", "Photo Wanted")
        assertTrue(sync().ok)
        val photo = PhotoBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4))
        store.appEdit(checkNotNull(store.byName("Photo Wanted")).id) { it.copy(photo = photo, relations = listOf(TypedValue("Sam Spouse", 14))) }

        assertTrue(sync().ok)

        val text = server.text(book + "a.vcf")!!
        assertTrue("the photo is on the card: $text", text.contains("PHOTO;ENCODING=b;TYPE=PNG:"))
        assertTrue("the relation is on the card: $text", text.contains("RELATED;TYPE=spouse:Sam Spouse"))
        val parsed = checkNotNull(ContactMapper.parse(text))
        assertEquals(photo, parsed.contact.photo)
    }

    @Test
    fun aPhotoThatTheCardAlreadyHasIsNeverReplacedByThePhonesCopyOfIt() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        putCard("a.vcf", "ua", "Has Photo", book, "PHOTO;ENCODING=b;TYPE=JPEG:" + java.util.Base64.getEncoder().encodeToString(original))
        assertTrue(sync().ok)
        // the phone keeps its own re-encoded copy: other bytes
        store.appEdit(checkNotNull(store.byName("Has Photo")).id) { it.copy(photo = PhotoBytes(byteArrayOf(9, 9, 9)), nicknames = listOf("Hp")) }

        assertTrue(sync().ok)

        val parsed = checkNotNull(ContactMapper.parse(server.text(book + "a.vcf")!!))
        assertEquals("the server's photo is as it was", PhotoBytes(original), parsed.contact.photo)
        assertEquals("the real change went through", listOf("Hp"), parsed.contact.nicknames)
    }

    @Test
    fun aPhotoRemovedOnTheServerGoesFromThePhoneAndDoesNotComeBack() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        putCard("a.vcf", "ua", "Had Photo", book, "PHOTO;ENCODING=b;TYPE=JPEG:" + java.util.Base64.getEncoder().encodeToString(original))
        assertTrue(sync().ok)
        assertEquals(PhotoBytes(original), checkNotNull(store.byName("Had Photo")).contact.photo)
        // the photo is removed on the web
        putCard("a.vcf", "ua", "Had Photo")
        assertTrue(sync().ok)
        assertNull("the phone's photo went too", checkNotNull(store.byName("Had Photo")).contact.photo)
        // a later phone edit does not send it again
        store.appEdit(checkNotNull(store.byName("Had Photo")).id) { it.copy(nicknames = listOf("Hp")) }
        assertTrue(sync().ok)
        val text = server.text(book + "a.vcf")!!
        assertFalse("no photo on the card: $text", text.contains("PHOTO"))
        assertTrue(text.contains("NICKNAME:Hp"))
    }

    @Test
    fun aPhoneContactWithOnlyDataOfAKindThatIsNotReadIsNotCalledEmpty() {
        assertTrue(sync().ok)
        // a SIP address, a custom date or another app's data: the mapper has no field for it, but it is content
        store.appInsert(PhoneContact(), otherData = true)
        assertEquals("it waits to be sent", 1, WaitingCount.of(FakeCalendarStore(), store, dao.collections(account)))

        val outcome = sync()

        assertTrue(outcome.message, outcome.ok)
        assertEquals("it was sent, not cleared as empty", 1, server.writes().size)
        assertEquals(1, outcome.sentToServer)
    }

    // ---- no write without an ETag

    @Test
    fun aWriteWithoutAnETagIsNeverSent() {
        val client = CardDavClient(server, "ali")
        val replaced = client.replace(book + "a.vcf", "", "text", "text/vcard")
        val deleted = client.delete(book + "a.vcf", "")
        assertTrue(replaced is org.nourax.nodrive.devicesync.dav.WriteResult.NoEtag)
        assertTrue(deleted is org.nourax.nodrive.devicesync.dav.WriteResult.NoEtag)
        assertTrue("nothing left the app", server.requests.isEmpty())
    }
}
