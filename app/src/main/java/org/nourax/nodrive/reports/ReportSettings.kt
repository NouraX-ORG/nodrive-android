/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The few things the problem reports remember, in one private preferences file (one switch per phone, off by default).
 * A missing value always means "off" or "nothing yet". Every write is committed at once: the app can be stopped
 * right after the user turns the switch on.
 */
class ReportSettings(private val prefs: SharedPreferences) {
    /** "Send problem reports to Nourax". Off until the user turns it on. */
    var autoSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_AUTO_SEND, value) }

    /** When the system's list of finished processes was last looked at; 0 until the first start of this version. */
    var lastExitCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_EXIT_CHECK, 0L)
        set(value) = prefs.edit(commit = true) { putLong(KEY_LAST_EXIT_CHECK, value) }

    /** Name of the newest pending file the user was already asked about: each problem is asked about once. */
    var askedFor: String?
        get() = prefs.getString(KEY_ASKED_FOR, null)
        set(value) = prefs.edit(commit = true) { putString(KEY_ASKED_FOR, value) }

    /** Ids of finished upload runs whose result message was already shown (the newest few, oldest first). */
    fun isShown(workId: String): Boolean = prefs.getString(KEY_SHOWN, "").orEmpty().split(SEPARATOR).contains(workId)

    fun markShown(workId: String) {
        val ids = pushShown(prefs.getString(KEY_SHOWN, "").orEmpty(), workId, MAX_SHOWN)
        prefs.edit(commit = true) { putString(KEY_SHOWN, ids) }
    }

    companion object {
        const val FILE = "org.nourax.nodrive.reports"
        private const val KEY_AUTO_SEND = "autoSend"
        private const val KEY_LAST_EXIT_CHECK = "lastExitCheckMillis"
        private const val KEY_ASKED_FOR = "askedFor"
        private const val KEY_SHOWN = "shownWorkIds"
        private const val SEPARATOR = ","
        private const val MAX_SHOWN = 20

        /** [current] is "id1,id2,..." oldest first. Puts [id] last (once) and keeps the newest [max]. */
        internal fun pushShown(current: String, id: String, max: Int): String =
            (current.split(SEPARATOR).filter { it.isNotEmpty() && it != id } + id).takeLast(max).joinToString(SEPARATOR)

        fun create(context: Context) =
            ReportSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
