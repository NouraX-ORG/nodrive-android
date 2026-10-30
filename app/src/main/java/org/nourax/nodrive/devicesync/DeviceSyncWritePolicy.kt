/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

/**
 * The only writes device sync may send (plan 4.6). Reads are always allowed. Writes:
 *  - a calendar object (`.ics`) or an address-book object (`.vcf`) can be created, replaced and deleted, in the person's own
 *    calendars and address books only (the sync adds `If-Match` / `If-None-Match`; the server keeps calendar deletes in its trash bin);
 *  - the safety copy of removed contacts: a new file `removed-contacts-<time>.vcf` in the `.Contacts-Backup` folder of the Drive
 *    (and the folder itself, if it is not there). A file of the Drive is never replaced or deleted.
 */
object DeviceSyncWritePolicy {
    private const val OBJECT_ICS = """[^/?#]+/[^/?#]+/[^/?#]+\.ics"""
    private const val OBJECT_VCF = """[^/?#]+/[^/?#]+/[^/?#]+\.vcf"""
    private val calendarObject = Regex("""^/?(?:[^?#]*/)?remote\.php/dav/calendars/$OBJECT_ICS$""")
    private val contactObject = Regex("""^/?(?:[^?#]*/)?remote\.php/dav/addressbooks/users/$OBJECT_VCF$""")
    private val safetyFile = Regex("""^/?(?:[^?#]*/)?remote\.php/dav/files/[^/?#]+/\.Contacts-Backup/removed-contacts-[A-Za-z0-9._-]+\.vcf$""")
    private val safetyFolder = Regex("""^/?(?:[^?#]*/)?remote\.php/dav/files/[^/?#]+/\.Contacts-Backup/?$""")

    fun allows(method: String, path: String): Boolean {
        if (method == "GET" || method == "PROPFIND" || method == "REPORT") return true
        val plain = path.substringBefore('?').removePrefix("/")
        if (plain.contains("..") || plain.contains("//")) return false
        val clean = path.substringBefore('?')
        return when (method) {
            "PUT" -> calendarObject.matches(clean) || contactObject.matches(clean) || safetyFile.matches(clean)
            "DELETE" -> calendarObject.matches(clean) || contactObject.matches(clean)
            "MKCOL" -> safetyFolder.matches(clean)
            else -> false
        }
    }
}
