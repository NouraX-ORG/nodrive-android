/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** A phone number or an e-mail address with Android's type number (phone: 1 home, 2 mobile, 3 work, 4 fax work, 5 fax home, 6 pager, 7 other). */
data class TypedValue(val value: String, val type: Int)

data class PhoneAddress(
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    /** 1 home, 2 work, 3 other. */
    val type: Int = 3
)

/** The bytes of a photo; two photos are equal when their bytes are. */
class PhotoBytes(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PhotoBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * One contact as the phone's Contacts holds it (plain values, so it can be unit-tested). The [photo] of a row read from the phone is
 * only loaded for rows that were changed (it is what a change could send); the photo of a parsed card is always there.
 */
data class PhoneContact(
    val uid: String = "",
    val displayName: String = "",
    val given: String = "",
    val family: String = "",
    val middle: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val phones: List<TypedValue> = emptyList(),
    /** E-mail types: 1 home, 2 work, 3 other, 4 mobile. */
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<PhoneAddress> = emptyList(),
    val company: String = "",
    val department: String = "",
    val title: String = "",
    val note: String = "",
    val urls: List<String> = emptyList(),
    /** "yyyy-MM-dd" or "--MM-dd", or empty. */
    val birthday: String = "",
    val anniversary: String = "",
    val nicknames: List<String> = emptyList(),
    val impps: List<String> = emptyList(),
    /** Group titles (the server's CATEGORIES). */
    val groups: List<String> = emptyList(),
    /** `RELATED`: the name of the person and Android's relation type number (see [ContactMapper.relationType]). */
    val relations: List<TypedValue> = emptyList(),
    val photo: PhotoBytes? = null
) {
    /** Nothing in it that could be sent (a card that was started and left empty; only a group does not count). */
    fun isBlank(): Boolean = copy(uid = "", groups = emptyList()) == PhoneContact()
}

/** A parsed card. */
class ParsedContact(val contact: PhoneContact) {
    val photo: ByteArray? get() = contact.photo?.bytes
}

object ContactMapper {
    private val REV: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    // ---- type numbers
    fun phoneType(types: Set<String>): Int = when {
        "CELL" in types -> 2
        "FAX" in types -> if ("HOME" in types) 5 else 4
        "PAGER" in types -> 6
        "WORK" in types -> 3
        "HOME" in types -> 1
        else -> 7
    }

    fun phoneTypeParam(type: Int): String = when (type) {
        2 -> ";TYPE=CELL"
        3 -> ";TYPE=WORK"
        1 -> ";TYPE=HOME"
        4 -> ";TYPE=WORK;TYPE=FAX"
        5 -> ";TYPE=HOME;TYPE=FAX"
        6 -> ";TYPE=PAGER"
        else -> ";TYPE=VOICE"
    }

    fun emailType(types: Set<String>): Int = when {
        "HOME" in types -> 1
        "WORK" in types -> 2
        "CELL" in types || "MOBILE" in types -> 4
        else -> 3
    }

    fun emailTypeParam(type: Int): String = when (type) {
        1 -> ";TYPE=HOME"
        2 -> ";TYPE=WORK"
        4 -> ";TYPE=CELL"
        else -> ""
    }

    fun addressType(types: Set<String>): Int = when {
        "HOME" in types -> 1
        "WORK" in types -> 2
        else -> 3
    }

    fun addressTypeParam(type: Int): String = when (type) {
        1 -> ";TYPE=HOME"
        2 -> ";TYPE=WORK"
        else -> ""
    }

    /** "1990-05-12", "19900512", "--0512", "1990-05-12T00:00:00Z" all become "yyyy-MM-dd" or "--MM-dd"; anything else is empty. */
    fun normalDate(raw: String): String {
        val text = raw.trim().substringBefore('T')
        val noDash = text.replace("-", "")
        return when {
            text.startsWith("--") && noDash.length == 4 && noDash.all { it.isDigit() } -> "--" + noDash.substring(0, 2) + "-" + noDash.substring(2)
            noDash.length == 8 && noDash.all { it.isDigit() } -> noDash.substring(0, 4) + "-" + noDash.substring(4, 6) + "-" + noDash.substring(6)
            else -> ""
        }
    }

    /**
     * Android's relation type of a `RELATED` line: 14 spouse, 3 child, 9 parent, 2 brother (any sibling), 6 friend, 12 relative (anything
     * else). A relation made on the phone with a finer type (father, mother, manager...) is sent with the nearest kind and read back as it.
     */
    fun relationType(types: Set<String>): Int = when {
        "SPOUSE" in types -> 14
        "CHILD" in types -> 3
        "PARENT" in types -> 9
        "SIBLING" in types -> 2
        "FRIEND" in types -> 6
        else -> 12
    }

    fun relationTypeParam(type: Int): String = when (type) {
        14, 4, 10 -> ";TYPE=spouse"
        3 -> ";TYPE=child"
        9, 5, 8 -> ";TYPE=parent"
        2, 13 -> ";TYPE=sibling"
        6 -> ";TYPE=friend"
        else -> ""
    }

    private fun photoOf(props: List<VProp>): ByteArray? {
        val prop = props.firstOrNull { it.name == "PHOTO" } ?: return null
        val raw = prop.raw.trim()
        val encoded = when {
            raw.startsWith("data:", ignoreCase = true) -> raw.substringAfter("base64,", "")
            prop.param("ENCODING").equals("b", ignoreCase = true) || prop.param("ENCODING").equals("BASE64", ignoreCase = true) -> raw
            else -> ""
        }
        if (encoded.isEmpty()) return null
        return try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A vCard as a phone contact. Null when it holds neither a name nor anything to show. */
    fun parse(text: String): ParsedContact? {
        val props = VCardText.parse(text)
        if (props.isEmpty()) return null
        val n = props.firstOrNull { it.name == "N" }?.parts().orEmpty()
        fun part(i: Int) = n.getOrNull(i).orEmpty()
        val org = props.firstOrNull { it.name == "ORG" }?.parts().orEmpty()
        val given = part(1)
        val family = part(0)
        var display = props.firstOrNull { it.name == "FN" }?.text()?.trim().orEmpty()
        if (display.isEmpty()) display = listOf(part(3), given, part(2), family, part(4)).filter { it.isNotEmpty() }.joinToString(" ")
        val contact = PhoneContact(
            uid = props.firstOrNull { it.name == "UID" }?.text()?.trim().orEmpty(),
            displayName = display,
            given = given,
            family = family,
            middle = part(2),
            prefix = part(3),
            suffix = part(4),
            phones = props.filter { it.name == "TEL" }.map { TypedValue(it.text().trim().removePrefix("tel:"), phoneType(it.types)) }
                .filter { it.value.isNotEmpty() },
            emails = props.filter { it.name == "EMAIL" }.map { TypedValue(it.text().trim(), emailType(it.types)) }.filter { it.value.isNotEmpty() },
            addresses = props.filter { it.name == "ADR" }.map {
                val a = it.parts()
                PhoneAddress(a.getOrNull(2).orEmpty(), a.getOrNull(3).orEmpty(), a.getOrNull(4).orEmpty(), a.getOrNull(5).orEmpty(),
                    a.getOrNull(6).orEmpty(), addressType(it.types))
            }.filter { it != PhoneAddress(type = it.type) },
            company = org.getOrNull(0).orEmpty(),
            department = org.getOrNull(1).orEmpty(),
            title = props.firstOrNull { it.name == "TITLE" }?.text().orEmpty(),
            note = props.firstOrNull { it.name == "NOTE" }?.text().orEmpty(),
            urls = props.filter { it.name == "URL" }.map { it.text().trim() }.filter { it.isNotEmpty() },
            birthday = props.firstOrNull { it.name == "BDAY" }?.let { normalDate(it.raw) }.orEmpty(),
            anniversary = props.firstOrNull { it.name == "ANNIVERSARY" }?.let { normalDate(it.raw) }.orEmpty(),
            nicknames = props.filter { it.name == "NICKNAME" }.flatMap { it.list() },
            impps = props.filter { it.name == "IMPP" }.map { it.raw.trim() }.filter { it.isNotEmpty() },
            groups = props.filter { it.name == "CATEGORIES" }.flatMap { it.list() }.distinct(),
            relations = props.filter { it.name == "RELATED" }.map { TypedValue(it.text().trim(), relationType(it.types)) }.filter { it.value.isNotEmpty() },
            photo = photoOf(props)?.let { PhotoBytes(it) }
        )
        return ParsedContact(contact)
    }

    // ------------------------------------------------------------------------------------------
    // Phone to server
    // ------------------------------------------------------------------------------------------

    private fun esc(text: String) = VCardText.escape(text)

    private fun dateText(normal: String, version: String): String = when {
        !version.startsWith("4") -> normal
        normal.startsWith("--") -> "--" + normal.substring(2).replace("-", "")
        else -> normal.replace("-", "")
    }

    private fun photoLine(bytes: ByteArray, version: String): String {
        val png = bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return if (version.startsWith("4")) {
            "PHOTO:data:" + (if (png) "image/png" else "image/jpeg") + ";base64," + encoded
        } else {
            "PHOTO;ENCODING=b;TYPE=" + (if (png) "PNG" else "JPEG") + ":" + encoded
        }
    }

    /**
     * The lines of a repeating property: a line whose entry is still wanted stays as it is, a line whose entry is gone is removed,
     * an entry that is new is added. [entryOf] reads the entry of an existing line, [lineOf] writes one for a new entry.
     */
    private fun <T> list(patch: VCardPatch, name: String, old: List<T>, now: List<T>, entryOf: (VProp) -> T?, lineOf: (T) -> String): Boolean {
        val same = old.size == now.size && old.toMutableList().let { pool -> now.all { pool.remove(it) } }
        if (same) return false
        val remaining = now.toMutableList()
        patch.removeWhere(name) { prop ->
            val entry = entryOf(prop)
            if (entry != null && remaining.remove(entry)) false else entry != null
        }
        for (entry in remaining) patch.add(lineOf(entry))
        return true
    }

    /**
     * [text] is the server's card. The properties in which [now] differs from [base] (the card as the server had it) are rewritten;
     * every other line stays byte for byte (the photo, `X-` lines, group labels). Nothing changes when nothing differs.
     */
    fun patch(text: String, base: PhoneContact, now: PhoneContact, at: Instant): String {
        val p = VCardPatch(text)
        val version = p.version
        var changed = false
        if (base.displayName != now.displayName && now.displayName.isNotEmpty()) {
            p.set("FN", "FN:" + esc(now.displayName))
            changed = true
        }
        // A card with only FN gets split into given and family names by the phone: that is not a change of the person.
        val baseHasNoParts = base.given.isEmpty() && base.family.isEmpty() && base.middle.isEmpty() && base.prefix.isEmpty() && base.suffix.isEmpty()
        val partsChanged = base.given != now.given || base.family != now.family || base.middle != now.middle || base.prefix != now.prefix || base.suffix != now.suffix
        if (partsChanged && !(baseHasNoParts && base.displayName == now.displayName)) {
            p.set("N", "N:" + listOf(now.family, now.given, now.middle, now.prefix, now.suffix).joinToString(";") { esc(it) })
            changed = true
        }
        changed = list(p, "TEL", base.phones, now.phones, { TypedValue(it.text().trim().removePrefix("tel:"), phoneType(it.types)) },
            { "TEL" + phoneTypeParam(it.type) + ":" + esc(it.value) }) || changed
        changed = list(p, "EMAIL", base.emails, now.emails, { TypedValue(it.text().trim(), emailType(it.types)) },
            { "EMAIL" + emailTypeParam(it.type) + ":" + esc(it.value) }) || changed
        changed = list(p, "ADR", base.addresses, now.addresses, {
            val a = it.parts()
            PhoneAddress(a.getOrNull(2).orEmpty(), a.getOrNull(3).orEmpty(), a.getOrNull(4).orEmpty(), a.getOrNull(5).orEmpty(),
                a.getOrNull(6).orEmpty(), addressType(it.types))
        }, {
            "ADR" + addressTypeParam(it.type) + ":;;" + listOf(it.street, it.city, it.region, it.postcode, it.country).joinToString(";") { s -> esc(s) }
        }) || changed
        if (base.company != now.company || base.department != now.department) {
            if (now.company.isEmpty() && now.department.isEmpty()) {
                p.remove("ORG")
            } else {
                p.set("ORG", "ORG:" + esc(now.company) + if (now.department.isNotEmpty()) ";" + esc(now.department) else "")
            }
            changed = true
        }
        if (base.title != now.title) {
            if (now.title.isEmpty()) p.remove("TITLE") else p.set("TITLE", "TITLE:" + esc(now.title))
            changed = true
        }
        if (base.note != now.note) {
            if (now.note.isEmpty()) p.remove("NOTE") else p.set("NOTE", "NOTE:" + esc(now.note))
            changed = true
        }
        changed = list(p, "URL", base.urls, now.urls, { it.text().trim() }, { "URL:" + esc(it) }) || changed
        if (base.birthday != now.birthday) {
            if (now.birthday.isEmpty()) p.remove("BDAY") else p.set("BDAY", "BDAY:" + dateText(now.birthday, version))
            changed = true
        }
        if (base.anniversary != now.anniversary) {
            if (now.anniversary.isEmpty()) p.remove("ANNIVERSARY") else p.set("ANNIVERSARY", "ANNIVERSARY:" + dateText(now.anniversary, version))
            changed = true
        }
        if (base.nicknames != now.nicknames) {
            if (now.nicknames.isEmpty()) p.remove("NICKNAME") else p.set("NICKNAME", "NICKNAME:" + now.nicknames.joinToString(",") { esc(it) })
            changed = true
        }
        changed = list(p, "IMPP", base.impps, now.impps, { it.raw.trim() }, { "IMPP:$it" }) || changed
        changed = list(p, "RELATED", base.relations, now.relations, { TypedValue(it.text().trim(), relationType(it.types)) },
            { "RELATED" + relationTypeParam(it.type) + ":" + esc(it.value) }) || changed
        // A photo is sent when the phone has one and the card has none. A photo that the card already has is not compared: the phone keeps
        // its own re-encoded copy of it, so the bytes are never the same, and the server's photo is never replaced by that copy.
        if (base.photo == null && now.photo != null) {
            p.set("PHOTO", photoLine(now.photo.bytes, version))
            changed = true
        }
        if (base.groups.toSet() != now.groups.toSet()) {
            if (now.groups.isEmpty()) p.remove("CATEGORIES") else p.set("CATEGORIES", "CATEGORIES:" + now.groups.joinToString(",") { esc(it) })
            changed = true
        }
        if (changed) p.set("REV", "REV:" + REV.format(at.atOffset(ZoneOffset.UTC)))
        return p.build()
    }

    /** "Apply my change again": the server's contact with only what the phone changed (against [base]) put on top. */
    fun rebase(base: PhoneContact, phone: PhoneContact, server: PhoneContact): PhoneContact {
        fun <T> pick(b: T, p: T, s: T): T = if (p != b) p else s
        return server.copy(
            displayName = pick(base.displayName, phone.displayName, server.displayName),
            given = pick(base.given, phone.given, server.given),
            family = pick(base.family, phone.family, server.family),
            middle = pick(base.middle, phone.middle, server.middle),
            prefix = pick(base.prefix, phone.prefix, server.prefix),
            suffix = pick(base.suffix, phone.suffix, server.suffix),
            phones = pick(base.phones, phone.phones, server.phones),
            emails = pick(base.emails, phone.emails, server.emails),
            addresses = pick(base.addresses, phone.addresses, server.addresses),
            company = pick(base.company, phone.company, server.company),
            department = pick(base.department, phone.department, server.department),
            title = pick(base.title, phone.title, server.title),
            note = pick(base.note, phone.note, server.note),
            urls = pick(base.urls, phone.urls, server.urls),
            birthday = pick(base.birthday, phone.birthday, server.birthday),
            anniversary = pick(base.anniversary, phone.anniversary, server.anniversary),
            nicknames = pick(base.nicknames, phone.nicknames, server.nicknames),
            impps = pick(base.impps, phone.impps, server.impps),
            groups = pick(base.groups, phone.groups, server.groups),
            relations = pick(base.relations, phone.relations, server.relations),
            photo = server.photo ?: phone.photo
        )
    }

    /** A new card for a contact made on the phone. */
    fun create(uid: String, now: PhoneContact, at: Instant): String {
        val skeleton = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:\r\nN:;;;;\r\nEND:VCARD\r\n"
        val base = PhoneContact(uid = uid)
        val name = now.displayName.ifEmpty { listOf(now.prefix, now.given, now.middle, now.family, now.suffix).filter { it.isNotEmpty() }.joinToString(" ") }
        val patched = patch(skeleton, base, now.copy(uid = uid, displayName = name.ifEmpty { now.phones.firstOrNull()?.value ?: now.emails.firstOrNull()?.value ?: "?" }), at)
        return patched
    }
}
