/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract

/**
 * The only place that builds the provider addresses of the phone's Calendar and Contacts (plan 4.2). Every address says "I am the sync
 * adapter" with OUR account name and type, and every query that goes with it is limited to our account ([accountSelection]), so no
 * row of another account can ever be read for writing, changed or deleted.
 */
class ProviderScope(val accountName: String, val accountType: String) {
    private fun calendar(base: Uri): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
        .build()

    private fun contacts(base: Uri): Uri = base.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
        .build()

    val calendars: Uri get() = calendar(CalendarContract.Calendars.CONTENT_URI)
    val events: Uri get() = calendar(CalendarContract.Events.CONTENT_URI)
    val reminders: Uri get() = calendar(CalendarContract.Reminders.CONTENT_URI)
    val attendees: Uri get() = calendar(CalendarContract.Attendees.CONTENT_URI)
    val alerts: Uri get() = CalendarContract.CalendarAlerts.CONTENT_URI

    val rawContacts: Uri get() = contacts(ContactsContract.RawContacts.CONTENT_URI)
    val data: Uri get() = contacts(ContactsContract.Data.CONTENT_URI)
    val groups: Uri get() = contacts(ContactsContract.Groups.CONTENT_URI)
    val settings: Uri get() = contacts(ContactsContract.Settings.CONTENT_URI)

    /** "account_name = ? AND account_type = ?" with its arguments, to put in front of every selection of a calendar or contacts table. */
    fun accountSelection(nameColumn: String, typeColumn: String): String = "$nameColumn = ? AND $typeColumn = ?"

    val accountArgs: Array<String> get() = arrayOf(accountName, accountType)
}
