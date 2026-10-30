/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.WritableCalendar

/** Which calendar or list "+" starts with (plan 2.4): the server's default, then the last one used, then the first of the person's own. */
object NewItemDefaults {
    /** Only the person's own calendars count; a Team or Hidden one is never preselected, whatever the list holds. */
    fun pick(calendars: List<WritableCalendar>, lastUsedUri: String): WritableCalendar? {
        val own = calendars.filter { it.mode == CalendarMode.PERSONAL || it.mode == CalendarMode.WORK }
        return own.firstOrNull { it.isDefault }
            ?: own.firstOrNull { lastUsedUri.isNotEmpty() && it.uri == lastUsedUri }
            ?: own.firstOrNull()
    }
}
