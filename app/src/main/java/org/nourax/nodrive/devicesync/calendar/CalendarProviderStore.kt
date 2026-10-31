/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract
import org.nourax.nodrive.agenda.ical.ICalTime
import org.nourax.nodrive.devicesync.ProviderScope
import java.util.TimeZone

/** One row of the phone's `Events` table that belongs to us. */
class CalendarRow(
    val id: Long,
    val calendarId: Long,
    /** The server address, once linked. */
    val syncId: String?,
    val etag: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val originalSyncId: String?,
    val originalId: Long?,
    val event: PhoneEvent
) {
    val isException: Boolean get() = originalId != null || !originalSyncId.isNullOrEmpty()
}

/**
 * Reads and writes the phone's calendars of the account through [ProviderScope]: the only class that writes to the Calendar provider.
 * Writes are made as the sync adapter, so a change is not marked dirty (it comes from the server).
 */
class CalendarProviderStore(private val resolver: ContentResolver, private val scope: ProviderScope) : CalendarStore {
    private val eventColumns = arrayOf(
        CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events._SYNC_ID,
        CalendarContract.Events.SYNC_DATA1, CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED,
        CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
        CalendarContract.Events.ORIGINAL_ALL_DAY, CalendarContract.Events.UID_2445,
        CalendarContract.Events.TITLE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
        CalendarContract.Events.ALL_DAY, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION,
        CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXDATE,
        CalendarContract.Events.STATUS, CalendarContract.Events.AVAILABILITY, CalendarContract.Events.ACCESS_LEVEL,
        CalendarContract.Events.ORGANIZER
    )

    // ---- calendars -------------------------------------------------------------------------

    /** The id of our phone calendar for a server address, or null. */
    fun findCalendarId(href: String): Long? {
        resolver.query(
            scope.calendars, arrayOf(CalendarContract.Calendars._ID),
            scope.accountSelection(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE) +
                " AND " + CalendarContract.Calendars.NAME + " = ?",
            scope.accountArgs + href, null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    /** Every calendar of ours on the phone: id to server address. */
    override fun calendarIds(): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        resolver.query(
            scope.calendars, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME),
            scope.accountSelection(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE), scope.accountArgs, null
        )?.use { while (it.moveToNext()) out[it.getLong(0)] = it.getString(1).orEmpty() }
        return out
    }

    override fun ensureCalendar(href: String, displayName: String, color: Int, readOnly: Boolean): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, color)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, if (readOnly) CalendarContract.Calendars.CAL_ACCESS_READ else CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }
        val existing = findCalendarId(href)
        if (existing != null) {
            resolver.update(ContentUris.withAppendedId(scope.calendars, existing), values, null, null)
            return existing
        }
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, scope.accountName)
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, scope.accountType)
        values.put(CalendarContract.Calendars.NAME, href)
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, scope.accountName)
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        values.put(CalendarContract.Calendars.ALLOWED_REMINDERS, "${CalendarContract.Reminders.METHOD_DEFAULT},${CalendarContract.Reminders.METHOD_ALERT}")
        val uri = checkNotNull(resolver.insert(scope.calendars, values)) { "the phone did not add the calendar" }
        return ContentUris.parseId(uri)
    }

    /** Removes our phone calendar and every event in it (the provider removes the events of a deleted calendar). */
    override fun deleteCalendar(calendarId: Long) {
        resolver.delete(ContentUris.withAppendedId(scope.calendars, calendarId), null, null)
    }

    // ---- reading events --------------------------------------------------------------------

    private fun rowOf(c: Cursor): CalendarRow {
        fun s(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(name: String): Long? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getLong(it) }
        fun i(name: String): Int? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getInt(it) }
        val allDay = (i(CalendarContract.Events.ALL_DAY) ?: 0) == 1
        val start = l(CalendarContract.Events.DTSTART) ?: 0L
        var end = l(CalendarContract.Events.DTEND)
        if (end == null) {
            val seconds = s(CalendarContract.Events.DURATION)?.let { ICalTime.parseDurationSeconds(it) } ?: 0L
            end = start + (if (seconds > 0) seconds * 1000 else 0)
        }
        fun dates(text: String?): String = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
        val id = l(CalendarContract.Events._ID) ?: 0L
        val originalInstance = l(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)
        val event = PhoneEvent(
            uid = s(CalendarContract.Events.UID_2445).orEmpty(),
            title = s(CalendarContract.Events.TITLE).orEmpty(),
            location = s(CalendarContract.Events.EVENT_LOCATION).orEmpty(),
            description = s(CalendarContract.Events.DESCRIPTION).orEmpty(),
            allDay = allDay,
            startMs = start,
            endMs = end,
            timeZone = if (allDay) "UTC" else s(CalendarContract.Events.EVENT_TIMEZONE).orEmpty().ifEmpty { "UTC" },
            rrule = s(CalendarContract.Events.RRULE).orEmpty(),
            rdate = dates(s(CalendarContract.Events.RDATE)),
            exdate = dates(s(CalendarContract.Events.EXDATE)),
            status = i(CalendarContract.Events.STATUS),
            availability = i(CalendarContract.Events.AVAILABILITY) ?: 0,
            accessLevel = i(CalendarContract.Events.ACCESS_LEVEL) ?: 0,
            reminders = remindersOf(id),
            organizer = s(CalendarContract.Events.ORGANIZER).orEmpty().lowercase(),
            recurrenceIdMs = originalInstance,
            recurrenceAllDay = (i(CalendarContract.Events.ORIGINAL_ALL_DAY) ?: 0) == 1
        )
        return CalendarRow(
            id = id,
            calendarId = l(CalendarContract.Events.CALENDAR_ID) ?: 0L,
            syncId = s(CalendarContract.Events._SYNC_ID),
            etag = s(CalendarContract.Events.SYNC_DATA1).orEmpty(),
            dirty = (i(CalendarContract.Events.DIRTY) ?: 0) == 1,
            deleted = (i(CalendarContract.Events.DELETED) ?: 0) == 1,
            originalSyncId = s(CalendarContract.Events.ORIGINAL_SYNC_ID),
            originalId = l(CalendarContract.Events.ORIGINAL_ID),
            event = event
        )
    }

    private fun remindersOf(eventId: Long): List<Int> {
        val out = sortedSetOf<Int>()
        resolver.query(
            scope.reminders, arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            CalendarContract.Reminders.EVENT_ID + " = ?", arrayOf(eventId.toString()), null
        )?.use {
            while (it.moveToNext()) {
                val minutes = it.getInt(0)
                if (minutes >= 0) out += minutes
            }
        }
        return out.toList()
    }

    /** Every row (also the deleted ones) of our phone calendar. */
    override fun rows(calendarId: Long): List<CalendarRow> {
        val out = ArrayList<CalendarRow>()
        resolver.query(
            scope.events, eventColumns,
            scope.accountSelection(CalendarContract.Events.ACCOUNT_NAME, CalendarContract.Events.ACCOUNT_TYPE) + " AND " +
                CalendarContract.Events.CALENDAR_ID + " = ?",
            scope.accountArgs + calendarId.toString(), null
        )?.use { while (it.moveToNext()) out += rowOf(it) } ?: throw IllegalStateException(NO_ANSWER) // null = could not look, never "no rows"
        return out
    }

    fun row(id: Long): CalendarRow? {
        resolver.query(
            scope.events, eventColumns,
            scope.accountSelection(CalendarContract.Events.ACCOUNT_NAME, CalendarContract.Events.ACCOUNT_TYPE) + " AND " +
                CalendarContract.Events._ID + " = ?",
            scope.accountArgs + id.toString(), null
        )?.use { if (it.moveToFirst()) return rowOf(it) }
        return null
    }

    // ---- writing events (as the sync adapter) ----------------------------------------------

    private fun durationText(e: PhoneEvent): String {
        val ms = (e.endMs - e.startMs).coerceAtLeast(0)
        return if (e.allDay) "P${(ms / DAY_MS).coerceAtLeast(1)}D" else "PT${ms / 1000}S"
    }

    /** The columns of a row. The calendar is only named when the row is added: the provider refuses a changed calendar on an update. */
    private fun valuesOf(calendarId: Long?, e: PhoneEvent, href: String?, etag: String?): ContentValues {
        val v = ContentValues()
        if (calendarId != null) v.put(CalendarContract.Events.CALENDAR_ID, calendarId)
        v.put(CalendarContract.Events.TITLE, e.title)
        v.put(CalendarContract.Events.EVENT_LOCATION, e.location)
        v.put(CalendarContract.Events.DESCRIPTION, e.description)
        v.put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
        v.put(CalendarContract.Events.DTSTART, e.startMs)
        v.put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else e.timeZone)
        if (e.recurring && e.recurrenceIdMs == null) {
            v.putNull(CalendarContract.Events.DTEND)
            v.put(CalendarContract.Events.DURATION, durationText(e))
        } else {
            v.put(CalendarContract.Events.DTEND, e.endMs)
            v.putNull(CalendarContract.Events.DURATION)
        }
        if (e.recurrenceIdMs == null) {
            if (e.rrule.isEmpty()) v.putNull(CalendarContract.Events.RRULE) else v.put(CalendarContract.Events.RRULE, e.rrule)
            if (e.rdate.isEmpty()) v.putNull(CalendarContract.Events.RDATE) else v.put(CalendarContract.Events.RDATE, e.rdate)
            if (e.exdate.isEmpty()) v.putNull(CalendarContract.Events.EXDATE) else v.put(CalendarContract.Events.EXDATE, e.exdate)
        }
        if (e.status == null) v.putNull(CalendarContract.Events.STATUS) else v.put(CalendarContract.Events.STATUS, e.status)
        v.put(CalendarContract.Events.AVAILABILITY, e.availability)
        v.put(CalendarContract.Events.ACCESS_LEVEL, e.accessLevel)
        v.put(CalendarContract.Events.HAS_ALARM, if (e.reminders.isEmpty()) 0 else 1)
        if (e.guests.isNotEmpty()) {
            v.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
            if (e.organizer.isNotEmpty()) v.put(CalendarContract.Events.ORGANIZER, e.organizer)
        }
        v.put(CalendarContract.Events.UID_2445, e.uid)
        if (href != null) v.put(CalendarContract.Events._SYNC_ID, href)
        if (etag != null) v.put(CalendarContract.Events.SYNC_DATA1, etag)
        v.put(CalendarContract.Events.DIRTY, 0)
        v.put(CalendarContract.Events.DELETED, 0)
        return v
    }

    private fun addChildren(eventId: Long, e: PhoneEvent) {
        for (minutes in e.reminders) {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, eventId)
            r.put(CalendarContract.Reminders.MINUTES, minutes)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            resolver.insert(scope.reminders, r)
        }
        for (guest in e.guests) addGuest(eventId, guest)
    }

    private fun addGuest(eventId: Long, guest: PhoneGuest) {
        if (guest.email.isEmpty()) return
        val a = ContentValues()
        a.put(CalendarContract.Attendees.EVENT_ID, eventId)
        a.put(CalendarContract.Attendees.ATTENDEE_NAME, guest.name)
        a.put(CalendarContract.Attendees.ATTENDEE_EMAIL, guest.email)
        a.put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
        a.put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
        a.put(
            CalendarContract.Attendees.ATTENDEE_STATUS,
            when (guest.partstat) {
                "ACCEPTED" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                "DECLINED" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                "TENTATIVE" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
            }
        )
        try {
            resolver.insert(scope.attendees, a)
        } catch (_: RuntimeException) {
            // the guest list is a nicety; the event itself is what counts
        }
    }

    /** Adds the event and its changed occurrences as new rows; returns the id of the event's row. */
    override fun insertObject(calendarId: Long, href: String, etag: String, parsed: ParsedObject): Long {
        val uri = checkNotNull(resolver.insert(scope.events, valuesOf(calendarId, parsed.master, href, etag))) { "the phone did not add the event" }
        val masterId = ContentUris.parseId(uri)
        addChildren(masterId, parsed.master)
        insertExceptions(calendarId, href, masterId, parsed)
        return masterId
    }

    private fun insertExceptions(calendarId: Long, href: String, masterId: Long, parsed: ParsedObject) {
        for (ex in parsed.exceptions) {
            val v = valuesOf(calendarId, ex, null, null)
            v.put(CalendarContract.Events.ORIGINAL_SYNC_ID, href)
            v.put(CalendarContract.Events.ORIGINAL_ID, masterId)
            v.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, ex.recurrenceIdMs)
            v.put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (ex.recurrenceAllDay) 1 else 0)
            val uri = resolver.insert(scope.events, v) ?: continue
            addChildren(ContentUris.parseId(uri), ex)
        }
    }

    /**
     * Puts the server's version in place of the rows of the event: they are removed and written again as new rows (the provider's
     * insert path is the one that is proven for every column; an update of a whole row is refused by some provider versions).
     * Returns the id of the new row of the event.
     */
    override fun replaceObject(
        masterId: Long, calendarId: Long, href: String, etag: String, parsed: ParsedObject, previousGuests: Set<String>
    ): Long {
        // Guests are not sent to the server, so a guest that was added on the phone exists nowhere else: it is put back on the new row.
        val serverGuests = parsed.master.guests.map { it.email.lowercase() }.toSet()
        val added = phoneGuests(masterId).filter {
            it.email.isNotEmpty() && it.email.lowercase() !in previousGuests && it.email.lowercase() !in serverGuests
        }
        deleteObject(masterId, calendarId, href)
        val id = insertObject(calendarId, href, etag, parsed)
        if (added.isNotEmpty()) {
            if (parsed.master.guests.isEmpty()) {
                val v = ContentValues()
                v.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
                resolver.update(ContentUris.withAppendedId(scope.events, id), v, null, null)
            }
            for (guest in added) addGuest(id, guest)
        }
        return id
    }

    private fun phoneGuests(eventId: Long): List<PhoneGuest> {
        val out = ArrayList<PhoneGuest>()
        resolver.query(
            scope.attendees,
            arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_STATUS),
            CalendarContract.Attendees.EVENT_ID + " = ?", arrayOf(eventId.toString()), null
        )?.use {
            while (it.moveToNext()) {
                val partstat = when (it.getInt(2)) {
                    CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "ACCEPTED"
                    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "DECLINED"
                    CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "TENTATIVE"
                    else -> "NEEDS-ACTION"
                }
                out += PhoneGuest(it.getString(0).orEmpty(), it.getString(1).orEmpty(), partstat)
            }
        }
        return out
    }

    // The selection names the calendar (one of ours, found through the scoped query) next to the event it belongs to.
    private fun deleteExceptions(masterId: Long, calendarId: Long, href: String?) {
        resolver.delete(
            scope.events,
            CalendarContract.Events.CALENDAR_ID + " = ? AND (" + CalendarContract.Events.ORIGINAL_ID + " = ? OR " +
                CalendarContract.Events.ORIGINAL_SYNC_ID + " = ?)",
            arrayOf(calendarId.toString(), masterId.toString(), href ?: "-")
        )
    }

    /** Removes the event and its occurrences from the phone for good (a sync adapter delete). */
    override fun deleteObject(masterId: Long, calendarId: Long, href: String?) {
        deleteExceptions(masterId, calendarId, href)
        resolver.delete(ContentUris.withAppendedId(scope.events, masterId), null, null)
    }

    /** The row was sent: clean, linked to its address, with the ETag. */
    override fun markSent(id: Long, href: String?, etag: String, uid: String?) {
        val v = ContentValues()
        v.put(CalendarContract.Events.DIRTY, 0)
        if (href != null) v.put(CalendarContract.Events._SYNC_ID, href)
        v.put(CalendarContract.Events.SYNC_DATA1, etag)
        if (uid != null) v.put(CalendarContract.Events.UID_2445, uid)
        resolver.update(ContentUris.withAppendedId(scope.events, id), v, null, null)
    }

    /** Only the ETag and link of a row, nothing else (a first-sync link). */
    override fun link(id: Long, href: String, etag: String) {
        val v = ContentValues()
        v.put(CalendarContract.Events._SYNC_ID, href)
        v.put(CalendarContract.Events.SYNC_DATA1, etag)
        resolver.update(ContentUris.withAppendedId(scope.events, id), v, null, null)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NO_ANSWER = "the phone's calendar did not answer"
    }
}
