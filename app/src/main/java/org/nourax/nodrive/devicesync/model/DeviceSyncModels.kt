/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.model

import androidx.core.graphics.toColorInt

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
        colorText.ifEmpty { DEFAULT_COLOR }.toColorInt()
    } catch (_: IllegalArgumentException) {
        DEFAULT_COLOR.toColorInt()
    }

    companion object {
        const val CALENDAR = "calendar"
        const val CONTACTS = "contacts"
        const val DEFAULT_COLOR = "#2563EB"

        /** Chosen once, but the server no longer lists it (deleted, or no longer shared): not synced any more, taken away on Stop. */
        const val OFF_GONE = "gone"

        /** NoGantt's copy calendar (it holds copies of tasks): never offered, and taken off the phone if it was chosen. */
        const val OFF_TASK_COPY = "taskcopy"
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
    val message: String = "",
    /** The deletes that the delete guard held back (Android's "too many deletions" screen shows this number). */
    val heldBack: Int = 0
) {
    fun plus(other: SyncOutcome): SyncOutcome = SyncOutcome(
        ok && other.ok, added + other.added, changed + other.changed, sentToServer + other.sentToServer,
        removed + other.removed, notSaved + other.notSaved, tooManyDeletions || other.tooManyDeletions,
        waiting + other.waiting, message.ifEmpty { other.message }, heldBack + other.heldBack
    )

    companion object {
        val EMPTY = SyncOutcome(true)
        fun failed(message: String) = SyncOutcome(false, message = message)
    }
}
