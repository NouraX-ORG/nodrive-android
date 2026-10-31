/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.contacts

/**
 * What the contacts engine needs of the phone's Contacts (a seam: [ContactsProviderStore] is the real one, the tests of the engine use
 * a store in memory). Every write is a write of the sync adapter: a change made through it is not marked dirty.
 */
interface ContactsStore {
    /** Makes the phone show the contacts of this account. Throws when the phone has no contacts storage or no permission. */
    fun ensureSettings()

    /** Every raw contact of ours (also the deleted ones) with its data. */
    fun rows(): List<ContactRow>

    /** Adds the contact as a new raw contact; returns its id. */
    fun insert(href: String, etag: String, parsed: ParsedContact): Long

    /**
     * Puts the server's version into an existing raw contact. Data the sync does not manage (a photo the card has none of, ...) stays,
     * except that [dropPhoto] (the server had a photo before and the card has none now) takes the phone's photo away too.
     */
    fun replace(id: Long, href: String, etag: String, parsed: ParsedContact, dropPhoto: Boolean = false)

    /** Removes the raw contact from the phone for good. */
    fun delete(id: Long)

    /** The row was sent: clean, linked to its address, with the ETag. */
    fun markSent(id: Long, href: String?, etag: String, uid: String?)

    /** Only the ETag and link of a row (a first-sync link). */
    fun link(id: Long, href: String, etag: String)
}
