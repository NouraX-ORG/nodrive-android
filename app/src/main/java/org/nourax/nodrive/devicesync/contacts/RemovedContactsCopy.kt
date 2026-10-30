/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.devicesync.dav.DavSync
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Address books have no trash bin on the server (plan 0.3, 4.6). Before the sync sends a CardDAV delete, the server's last version of
 * every contact it is about to delete goes into ONE new file `removed-contacts-<UTC time>.vcf` in `.Contacts-Backup` of the Drive. The
 * file is created with `If-None-Match: *`: it is never replaced, and nothing in the Drive is ever deleted. If it can not be saved, the
 * deletes wait for the next sync.
 */
class RemovedContactsCopy(private val http: AgendaHttp, private val davUser: String) {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun folder() = "remote.php/dav/files/${DavSync.encodeSegment(davUser)}/.Contacts-Backup/"

    /** True when the cards are saved on the Drive. */
    fun save(cards: List<String>, at: Instant): Boolean {
        if (cards.isEmpty()) return true
        val body = cards.joinToString("") { if (it.endsWith("\n")) it else it + "\r\n" }.toByteArray(Charsets.UTF_8)
        val base = "removed-contacts-" + stamp.format(at.atOffset(ZoneOffset.UTC))
        var made = false
        for (attempt in 0 until MAX_ATTEMPTS) {
            val name = if (attempt == 0) "$base.vcf" else "$base-${attempt + 1}.vcf"
            val reply = http.send("PUT", folder() + name, mapOf("If-None-Match" to "*"), body, "text/vcard; charset=utf-8")
            when {
                reply.ok -> return true
                reply.status == 412 -> continue // the name is taken: never replace, try the next one
                reply.status == 409 && !made -> {
                    // the folder is not there yet (the backup makes it, but it may not have run): make it, once
                    http.send("MKCOL", folder())
                    made = true
                }
                else -> return false
            }
        }
        return false
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
