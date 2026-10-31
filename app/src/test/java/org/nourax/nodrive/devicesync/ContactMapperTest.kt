/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.devicesync.contacts.ContactMapper
import org.nourax.nodrive.devicesync.contacts.ImRow
import org.nourax.nodrive.devicesync.contacts.PhoneAddress
import org.nourax.nodrive.devicesync.contacts.PhoneContact
import org.nourax.nodrive.devicesync.contacts.PhotoBytes
import org.nourax.nodrive.devicesync.contacts.TypedValue
import java.time.Instant
import java.util.Base64

/** Contacts both ways (plan 4.9): every field, groups as CATEGORIES, unknown lines, vCard 3.0 kept. */
class ContactMapperTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

    private fun card(vararg lines: String) = (listOf("BEGIN:VCARD") + lines + listOf("END:VCARD")).joinToString("\r\n") + "\r\n"

    private val full = card(
        "VERSION:3.0", "UID:c1", "FN:Sara Contact", "N:Contact;Sara;Maria;Dr.;Jr.", "TEL;TYPE=CELL:+49 170 1234567", "TEL;TYPE=WORK,VOICE:+49 30 123",
        "EMAIL;TYPE=HOME:sara@example.test", "EMAIL;TYPE=WORK:sara@work.test", "ADR;TYPE=HOME:;;Main street 1;Berlin;BE;10115;Germany",
        "ORG:Nourax;Sales", "TITLE:Head of sales", "NOTE:Line one\\nLine two", "URL:https://example.test/sara", "BDAY:1990-05-12",
        "NICKNAME:Sari", "CATEGORIES:Family,Friends", "X-NOURAX-TEST:keep this", "PHOTO;ENCODING=b;TYPE=PNG:$png", "REV:20260101T000000Z"
    )

    @Test
    fun everyFieldIsRead() {
        val p = checkNotNull(ContactMapper.parse(full))
        val c = p.contact
        assertEquals("c1", c.uid)
        assertEquals("Sara Contact", c.displayName)
        assertEquals("Sara", c.given)
        assertEquals("Contact", c.family)
        assertEquals("Maria", c.middle)
        assertEquals("Dr.", c.prefix)
        assertEquals("Jr.", c.suffix)
        assertEquals(listOf(TypedValue("+49 170 1234567", 2), TypedValue("+49 30 123", 3)), c.phones)
        assertEquals(listOf(TypedValue("sara@example.test", 1), TypedValue("sara@work.test", 2)), c.emails)
        assertEquals(listOf(PhoneAddress("Main street 1", "Berlin", "BE", "10115", "Germany", 1)), c.addresses)
        assertEquals("Nourax", c.company)
        assertEquals("Sales", c.department)
        assertEquals("Head of sales", c.title)
        assertEquals("Line one\nLine two", c.note)
        assertEquals(listOf("https://example.test/sara"), c.urls)
        assertEquals("1990-05-12", c.birthday)
        assertEquals(listOf("Sari"), c.nicknames)
        assertEquals(listOf("Family", "Friends"), c.groups)
        assertEquals(9, checkNotNull(p.photo).size)
    }

    @Test
    fun groupsAreTheCategoriesOfTheCard() {
        val c = checkNotNull(ContactMapper.parse(card("VERSION:3.0", "FN:A", "CATEGORIES:Family", "CATEGORIES:Work,Family"))).contact
        assertEquals(listOf("Family", "Work"), c.groups)
    }

    @Test
    fun aVersion4CardWithADataUriPhotoAndShortBirthdayIsRead() {
        val p = checkNotNull(ContactMapper.parse(card("VERSION:4.0", "FN:B", "BDAY:--0512", "PHOTO:data:image/png;base64,$png", "TEL;TYPE=\"cell,voice\":tel:+1555")))
        assertEquals("--05-12", p.contact.birthday)
        assertEquals(9, checkNotNull(p.photo).size)
        assertEquals(listOf(TypedValue("+1555", 2)), p.contact.phones)
    }

    @Test
    fun aPhoneChangeOfOneNumberKeepsTheUnknownLinesThePhotoAndTheVersionByteForByte() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val now2 = base.copy(phones = listOf(TypedValue("+49 170 7654321", 2), TypedValue("+49 30 123", 3)))
        val after = ContactMapper.patch(full, base, now2, now)
        assertTrue(after.contains("TEL;TYPE=CELL:+49 170 7654321"))
        assertFalse(after.contains("+49 170 1234567"))
        for (kept in listOf(
            "VERSION:3.0", "X-NOURAX-TEST:keep this", "PHOTO;ENCODING=b;TYPE=PNG:$png", "TEL;TYPE=WORK,VOICE:+49 30 123", "N:Contact;Sara;Maria;Dr.;Jr.",
            "CATEGORIES:Family,Friends", "ORG:Nourax;Sales", "REV:2026"
        )) assertTrue("lost: $kept\n$after", after.contains(kept))
        assertTrue(after.contains("REV:20261030T120000Z"))
        // reading it again gives the new number and everything else as before
        val back = checkNotNull(ContactMapper.parse(after)).contact
        assertEquals(now2.phones.toSet(), back.phones.toSet())
        assertEquals(now2.copy(phones = emptyList()), back.copy(phones = emptyList()))
    }

    @Test
    fun nothingChangedGivesTheSameTextBack() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        assertEquals(full, ContactMapper.patch(full, base, base, now))
    }

    @Test
    fun aNumberInADifferentOrderIsNotAChange() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        assertEquals(full, ContactMapper.patch(full, base, base.copy(phones = base.phones.reversed()), now))
    }

    @Test
    fun aGroupChangeRewritesTheCategories() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val after = ContactMapper.patch(full, base, base.copy(groups = listOf("Family", "Work")), now)
        assertTrue(after.contains("CATEGORIES:Family,Work"))
        assertEquals(listOf("Family", "Work"), checkNotNull(ContactMapper.parse(after)).contact.groups)
        val none = ContactMapper.patch(full, base, base.copy(groups = emptyList()), now)
        assertFalse(none.contains("CATEGORIES"))
    }

    @Test
    fun aCardWithOnlyFnIsNotRewrittenWhenThePhoneSplitsTheName() {
        val text = card("VERSION:3.0", "UID:c2", "FN:Alice Example", "TEL:123")
        val base = checkNotNull(ContactMapper.parse(text)).contact
        val phone = base.copy(given = "Alice", family = "Example")
        assertEquals(text, ContactMapper.patch(text, base, phone, now))
    }

    @Test
    fun aNewContactIsBuiltFromNothingAndReadsBackTheSame() {
        val c = ContactMapper.parse(full)!!.contact.copy(uid = "")
        val text = ContactMapper.create("new-uid", c, now)
        assertTrue(text.contains("VERSION:3.0"))
        val back = checkNotNull(ContactMapper.parse(text)).contact
        assertEquals(c.copy(uid = "new-uid"), back)
        assertNotNull(text)
    }

    @Test
    fun relationsAreReadAndSentBothWays() {
        val text = card("VERSION:3.0", "UID:r1", "FN:Rel", "RELATED;TYPE=spouse:Sam Spouse", "RELATED;TYPE=friend:Fay Friend", "RELATED:Somebody")
        val c = checkNotNull(ContactMapper.parse(text)).contact
        assertEquals(listOf(TypedValue("Sam Spouse", 14), TypedValue("Fay Friend", 6), TypedValue("Somebody", 12)), c.relations)
        // a relation made on the phone
        val sent = ContactMapper.patch(text, c, c.copy(relations = c.relations + TypedValue("Chris Child", 3)), now)
        assertTrue(sent, sent.contains("RELATED;TYPE=child:Chris Child"))
        assertTrue("the others are untouched", sent.contains("RELATED;TYPE=spouse:Sam Spouse") && sent.contains("RELATED:Somebody"))
        // a relation removed on the phone
        val removed = ContactMapper.patch(text, c, c.copy(relations = c.relations.drop(1)), now)
        assertFalse(removed, removed.contains("Sam Spouse"))
        assertTrue(removed.contains("Fay Friend"))
        // the finer types of the phone go to the nearest kind
        assertEquals(";TYPE=parent", ContactMapper.relationTypeParam(5))
        assertEquals(";TYPE=sibling", ContactMapper.relationTypeParam(13))
        assertEquals("", ContactMapper.relationTypeParam(7))
    }

    @Test
    fun aPhotoOfThePhoneIsSentOnlyWhenTheCardHasNone() {
        val none = card("VERSION:3.0", "UID:p1", "FN:No Photo")
        val base = checkNotNull(ContactMapper.parse(none)).contact
        val photo = PhotoBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9, 8, 7))
        val sent = ContactMapper.patch(none, base, base.copy(photo = photo), now)
        assertTrue(sent, sent.contains("PHOTO;ENCODING=b;TYPE=PNG:"))
        assertEquals(photo, checkNotNull(ContactMapper.parse(sent)).contact.photo)
        // a version 4 card gets the data address form
        val four = card("VERSION:4.0", "UID:p2", "FN:Four")
        val sentFour = ContactMapper.patch(four, checkNotNull(ContactMapper.parse(four)).contact, PhoneContact(displayName = "Four", photo = photo, uid = "p2"), now)
        assertTrue(sentFour, sentFour.contains("PHOTO:data:image/png;base64,"))
        // a card that has a photo keeps it, whatever the phone holds
        val has = card("VERSION:3.0", "UID:p3", "FN:Has", "PHOTO;ENCODING=b;TYPE=PNG:$png")
        val hasBase = checkNotNull(ContactMapper.parse(has)).contact
        assertEquals(has, ContactMapper.patch(has, hasBase, hasBase.copy(photo = PhotoBytes(byteArrayOf(5, 5))), now))
    }

    @Test
    fun aContactIsBlankOnlyWhenNothingInItCouldBeSent() {
        assertTrue(PhoneContact().isBlank())
        assertTrue(PhoneContact(uid = "x", groups = listOf("Family")).isBlank())
        assertFalse(PhoneContact(nicknames = listOf("Bo")).isBlank())
        assertFalse(PhoneContact(birthday = "--05-12").isBlank())
        assertFalse(PhoneContact(urls = listOf("https://example.test")).isBlank())
        assertFalse(PhoneContact(impps = listOf("xmpp:a@b")).isBlank())
        assertFalse(PhoneContact(title = "Boss").isBlank())
        assertFalse(PhoneContact(photo = PhotoBytes(byteArrayOf(1))).isBlank())
    }

    @Test
    fun rebaseKeepsTheServersNewerFieldsAndPutsOnlyThePhonesChangeOnTop() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val merged = ContactMapper.rebase(base, base.copy(title = "CEO"), base.copy(note = "server note"))
        assertEquals("CEO", merged.title)
        assertEquals("server note", merged.note)
    }

    @Test
    fun phoneticNamesAreReadFromTheCard() {
        val c = checkNotNull(ContactMapper.parse(card("VERSION:3.0", "FN:Sara Contact", "N:Contact;Sara;;;", "X-PHONETIC-FIRST-NAME:Sah-rah",
            "X-PHONETIC-MIDDLE-NAME:Mah", "X-PHONETIC-LAST-NAME:Kon-takt"))).contact
        assertEquals("Sah-rah", c.phoneticGiven)
        assertEquals("Mah", c.phoneticMiddle)
        assertEquals("Kon-takt", c.phoneticFamily)
        assertFalse(PhoneContact(phoneticFamily = "Kah").isBlank())
    }

    @Test
    fun aPhoneticNameTypedOnThePhoneIsPatchedIntoTheCardAndAnUnchangedOneIsKeptByteForByte() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val sent = ContactMapper.patch(full, base, base.copy(phoneticFamily = "Kon-takt", phoneticGiven = "Sah-rah"), now)
        assertTrue(sent, sent.contains("X-PHONETIC-LAST-NAME:Kon-takt"))
        assertTrue(sent, sent.contains("X-PHONETIC-FIRST-NAME:Sah-rah"))
        assertFalse(sent, sent.contains("X-PHONETIC-MIDDLE-NAME"))
        assertTrue("every other line stays", sent.contains("X-NOURAX-TEST:keep this") && sent.contains("PHOTO;ENCODING=b;TYPE=PNG:$png"))
        // the card is read back as the phone holds it: nothing differs, so nothing is rewritten
        val again = checkNotNull(ContactMapper.parse(sent)).contact
        assertEquals(sent, ContactMapper.patch(sent, again, again, now))
        // one part is cleared on the phone: only its line goes
        val cleared = ContactMapper.patch(sent, again, again.copy(phoneticGiven = ""), now)
        assertFalse(cleared, cleared.contains("X-PHONETIC-FIRST-NAME"))
        assertTrue(cleared, cleared.contains("X-PHONETIC-LAST-NAME:Kon-takt"))
        // a new card made on the phone carries them too
        val created = ContactMapper.create("n1", PhoneContact(displayName = "New", phoneticFamily = "Nyoo"), now)
        assertTrue(created, created.contains("X-PHONETIC-LAST-NAME:Nyoo"))
    }

    @Test
    fun rebaseKeepsAPhoneticNameThePhoneTypedOnTopOfTheServersNewerCard() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val merged = ContactMapper.rebase(base, base.copy(phoneticFamily = "Kah"), base.copy(title = "CEO"))
        assertEquals("Kah", merged.phoneticFamily)
        assertEquals("CEO", merged.title)
    }

    @Test
    fun anImProtocolIsASchemeOnTheCardAndComesBackAsTheSameProtocol() {
        // phone to card
        assertEquals("skype:bob", ContactMapper.imppOf(3, "", "bob"))
        assertEquals("xmpp:bob@example.test", ContactMapper.imppOf(7, "", "bob@example.test"))
        assertEquals("gtalk:bob@example.test", ContactMapper.imppOf(5, "", "bob@example.test"))
        assertEquals("skype:bob", ContactMapper.imppOf(3, "", "skype:bob"))
        assertEquals("telegram:bob", ContactMapper.imppOf(-1, "telegram", "bob"))
        assertEquals("bob", ContactMapper.imppOf(-1, "IMPP", "bob"))
        assertEquals("bob", ContactMapper.imppOf(-1, "", "bob"))
        assertEquals("a label that is not a scheme is not used", "bob", ContactMapper.imppOf(-1, "My own chat", "bob"))
        assertEquals("", ContactMapper.imppOf(3, "", "  "))
        // card to phone
        assertEquals(ImRow(3, "", "bob"), ContactMapper.imRowOf("skype:bob"))
        assertEquals(ImRow(7, "", "bob@example.test"), ContactMapper.imRowOf("xmpp:bob@example.test"))
        assertEquals(ImRow(5, "", "bob@example.test"), ContactMapper.imRowOf("GTALK:bob@example.test"))
        assertEquals(ImRow(-1, "telegram", "bob"), ContactMapper.imRowOf("telegram:bob"))
        assertEquals("no scheme: written as it was before", ImRow(-1, "IMPP", "bob"), ContactMapper.imRowOf("bob"))
        assertEquals("a web address stays whole", ImRow(-1, "IMPP", "https://chat.example.test/bob"), ContactMapper.imRowOf("https://chat.example.test/bob"))
        // and back: every card text is the same after a round trip through the phone's row
        for (raw in listOf("skype:bob", "xmpp:bob@example.test", "gtalk:bob@example.test", "telegram:bob", "bob", "https://chat.example.test/bob", "sip:bob@example.test")) {
            val row = ContactMapper.imRowOf(raw)
            assertEquals(raw, raw, ContactMapper.imppOf(row.protocol, row.label, row.data))
        }
    }
}
