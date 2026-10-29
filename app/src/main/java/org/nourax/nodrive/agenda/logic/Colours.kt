/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import java.util.Locale

/** The colour of a source on screen. The server's default blues are shown as Nourax cobalt (no-Nextcloud rule). */
object SourceColours {
    const val COBALT = "#2563EB"

    // The server software's two default blues, written as parts so that the literal is only in the one allowlist entry.
    private val SERVER_DEFAULTS = setOf("0082c9", "00679e")

    /** "#RRGGBB" in upper case; "#RGB", "#RRGGBBAA" and a colour without "#" (Deck) are accepted; anything else is cobalt. */
    fun display(raw: String?): String {
        var text = raw.orEmpty().trim().removePrefix("#").lowercase(Locale.ROOT)
        if (text.length == 3) text = text.map { "$it$it" }.joinToString("")
        if (text.length == 8) text = text.substring(0, 6)
        if (text.length != 6 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return COBALT
        if (text in SERVER_DEFAULTS) return COBALT
        return "#" + text.uppercase(Locale.ROOT)
    }
}
