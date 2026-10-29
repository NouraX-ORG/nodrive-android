/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.owncloud.android.lib.common.utils.Log_OC

/**
 * The one place NoAgenda logs. It writes ids, counts and status codes only, never titles, descriptions, attendees,
 * e-mail addresses or names: the app log goes into problem reports (plan, builder's call 11).
 */
object AgendaLog {
    /** Tests replace this to capture what would be logged. */
    @Volatile
    var sink: ((level: Char, tag: String, message: String) -> Unit)? = null

    fun i(tag: String, message: String) = write('i', tag, message)

    fun w(tag: String, message: String) = write('w', tag, message)

    private fun write(level: Char, tag: String, message: String) {
        sink?.invoke(level, tag, message)
        try {
            if (level == 'w') Log_OC.w(tag, message) else Log_OC.i(tag, message)
        } catch (_: Throwable) {
            // no logger in a plain JVM test
        }
    }
}
