/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import android.content.Context
import android.content.SharedPreferences

/**
 * The few things the problem reports remember, in one private preferences file (one switch per phone, off by default).
 * A missing value always means "off" or "nothing yet".
 */
class ReportSettings(private val prefs: SharedPreferences) {
    /** "Send problem reports to Nourax". Off until the user turns it on. */
    var autoSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND, value).apply()

    /** When the system's list of finished processes was last looked at; 0 until the first start of this version. */
    var lastExitCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_EXIT_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EXIT_CHECK, value).apply()

    /** Name of the newest pending file the user was already asked about: each problem is asked about once. */
    var askedFor: String?
        get() = prefs.getString(KEY_ASKED_FOR, null)
        set(value) = prefs.edit().putString(KEY_ASKED_FOR, value).apply()

    /** Ids of finished upload runs whose result message was already shown (the newest few). */
    fun isShown(workId: String): Boolean = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty().contains(workId)

    fun markShown(workId: String) {
        val kept = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty().toList().takeLast(MAX_SHOWN - 1)
        prefs.edit().putStringSet(KEY_SHOWN, (kept + workId).toSet()).apply()
    }

    companion object {
        const val FILE = "org.nourax.nodrive.reports"
        private const val KEY_AUTO_SEND = "autoSend"
        private const val KEY_LAST_EXIT_CHECK = "lastExitCheckMillis"
        private const val KEY_ASKED_FOR = "askedFor"
        private const val KEY_SHOWN = "shownWork"
        private const val MAX_SHOWN = 20

        fun create(context: Context) =
            ReportSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
