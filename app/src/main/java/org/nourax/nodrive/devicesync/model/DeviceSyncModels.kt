/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.model

import android.graphics.Color

/** A calendar or an address book of the account, as the server lists it. */
class RemoteCollection(
    /** The server's address of the collection ("/remote.php/dav/calendars/ali/work/"). */
    val href: String,
    val kind: String,
    val name: String,
    /** "#RRGGBB" (calendars) or empty. */
    val colorText: String,
    /** Read only for this person (a subscription, a shared calendar without write, the system address book). */
    val readOnly: Boolean,
    /** Why it is off by default ("birthdays", "subscription", "readonly"), or empty when it is on by default. */
    val offReason: String
) {
    val defaultOn: Boolean get() = offReason.isEmpty()

    fun colorInt(): Int = try {
        Color.parseColor(colorText.ifEmpty { DEFAULT_COLOR })
    } catch (_: IllegalArgumentException) {
        Color.parseColor(DEFAULT_COLOR)
    }

    companion object {
        const val CALENDAR = "calendar"
        const val CONTACTS = "contacts"
        const val DEFAULT_COLOR = "#2563EB"
    }
}

/** What one sync of one account did, in counts (never any content). */
class SyncOutcome(
    val ok: Boolean,
    val added: Int = 0,
    val changed: Int = 0,
    val sentToServer: Int = 0,
    val removed: Int = 0,
    val notSaved: Int = 0,
    val tooManyDeletions: Boolean = false,
    /** A change on the phone that could not be sent (offline, error). */
    val waiting: Int = 0,
    val message: String = ""
) {
    fun plus(other: SyncOutcome): SyncOutcome = SyncOutcome(
        ok && other.ok, added + other.added, changed + other.changed, sentToServer + other.sentToServer,
        removed + other.removed, notSaved + other.notSaved, tooManyDeletions || other.tooManyDeletions,
        waiting + other.waiting, message.ifEmpty { other.message }
    )

    companion object {
        val EMPTY = SyncOutcome(true)
        fun failed(message: String) = SyncOutcome(false, message = message)
    }
}
