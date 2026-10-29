/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.dav

import androidx.annotation.Keep
/**
 * What is read from CalDAV (calendars, events and to-dos), and its offline-cache form. Only strings, numbers and
 * lists, so Gson writes and reads it as it is (times are epoch milliseconds, days are "yyyy-MM-dd").
 */
@Keep
data class CalendarInfo(
    /** Server path of the calendar collection. */
    val href: String = "",
    /** Last path segment. */
    val uri: String = "",
    val name: String = "",
    val color: String = "",
    val ownerPrincipal: String = "",
    val ownerName: String = "",
    val mine: Boolean = false,
    val readOnly: Boolean = false,
    val subscribed: Boolean = false,
    val birthdays: Boolean = false,
    /** A calendar made by the board app. */
    val deckBoard: Boolean = false,
    /** Inbox, outbox, trash bin. */
    val ignored: Boolean = false,
    val hasEvents: Boolean = false,
    val hasTodos: Boolean = false,
    /** The whole calendar does not block time. */
    val transparent: Boolean = false,
    val syncToken: String = "",
    val ctag: String = ""
)

@Keep
data class CalAttendee(
    /** Lower case, "mailto:" removed. */
    val address: String = "",
    /** ACCEPTED, DECLINED, TENTATIVE, NEEDS-ACTION, ... */
    val partstat: String = ""
)

@Keep
data class CalRecord(
    val calendarUri: String = "",
    val href: String = "",
    val etag: String = "",
    val isTodo: Boolean = false,
    val uid: String = "",
    val summary: String = "",
    /** The first 2,000 characters. */
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val timeApproximated: Boolean = false,
    /** UTC, epoch milliseconds. */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** All day: first day. */
    val startDate: String? = null,
    /** All day: last day (inclusive). */
    val endDate: String? = null,
    /** A to-do with a due time (UTC, epoch milliseconds). */
    val dueMs: Long? = null,
    /** A to-do: the due day. */
    val dueDate: String? = null,
    /** OPAQUE | TRANSPARENT */
    val transp: String = "",
    /** CONFIRMED | TENTATIVE | CANCELLED | NEEDS-ACTION | COMPLETED | IN-PROCESS */
    val status: String = "",
    val percent: Int = -1,
    val completed: Boolean = false,
    val recurrenceId: String = "",
    val repeating: Boolean = false,
    /** Lower case address. */
    val organizer: String = "",
    val attendees: List<CalAttendee> = emptyList(),
    /** X-NOURAX-AGENT-ID */
    val agentId: String = "",
    val categories: List<String> = emptyList()
)

@Keep
data class CalendarSnapshot(
    val fetchedAtMs: Long = 0,
    val principalHref: String = "",
    val displayName: String = "",
    /** calendar-user-address-set, lower case, "mailto:" removed. */
    val addresses: List<String> = emptyList(),
    val defaultCalendarUri: String = "",
    val calendars: List<CalendarInfo> = emptyList(),
    val records: List<CalRecord> = emptyList(),
    /** Calendars whose read failed in this refresh (their old records are kept). */
    val failedCalendars: List<String> = emptyList(),
    /** Calendars whose records here are complete for the window (a calendar that was never read is not in it). */
    val readUris: List<String> = emptyList(),
    val windowFrom: String = "",
    val windowTo: String = "",
    val loaded: Boolean = false
) {
    fun calendar(uri: String): CalendarInfo? = calendars.firstOrNull { it.uri == uri }
}
