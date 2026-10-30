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
import org.nourax.nodrive.devicesync.contacts.PhoneAddress
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
    fun rebaseKeepsTheServersNewerFieldsAndPutsOnlyThePhonesChangeOnTop() {
        val base = checkNotNull(ContactMapper.parse(full)).contact
        val merged = ContactMapper.rebase(base, base.copy(title = "CEO"), base.copy(note = "server note"))
        assertEquals("CEO", merged.title)
        assertEquals("server note", merged.note)
    }
}
