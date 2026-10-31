/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.calendar

/**
 * What the calendar engine needs of the phone's Calendar (a seam: [CalendarProviderStore] is the real one, the tests of the engine use a
 * store in memory). Every write is a write of the sync adapter: a change made through it is not marked dirty.
 */
interface CalendarStore {
    /** Our phone calendars: id to the server address they are a copy of. */
    fun calendarIds(): Map<Long, String>

    /** The id of our phone calendar for this address; the calendar is added when it is not there. */
    fun ensureCalendar(href: String, displayName: String, color: Int, readOnly: Boolean): Long

    /** Removes our phone calendar and every event in it. */
    fun deleteCalendar(calendarId: Long)

    /** Every row (also the deleted ones) of our phone calendar. */
    fun rows(calendarId: Long): List<CalendarRow>

    /** Adds the event and its changed occurrences as new rows; returns the id of the event's row. */
    fun insertObject(calendarId: Long, href: String, etag: String, parsed: ParsedObject): Long

    /**
     * Puts the server's version in place of the rows of the event. Guests that were added on the phone (not on the last server version,
     * whose guests are [previousGuests]) are put back.
     */
    fun replaceObject(
        masterId: Long, calendarId: Long, href: String, etag: String, parsed: ParsedObject, previousGuests: Set<String> = emptySet()
    ): Long

    /** Removes the event and its occurrences from the phone for good. */
    fun deleteObject(masterId: Long, calendarId: Long, href: String?)

    /** The row was sent: clean, linked to its address, with the ETag. */
    fun markSent(id: Long, href: String?, etag: String, uid: String?)

    /** Only the ETag and link of a row (a first-sync link). */
    fun link(id: Long, href: String, etag: String)
}
