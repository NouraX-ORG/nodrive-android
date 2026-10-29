/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import java.net.URLEncoder
import java.time.ZoneId

/**
 * Where "Open in NoCalendar / NoTasks / NoGantt / NoBoard" goes: a page of the account's own server, opened signed in
 * inside the app (the same hand-off as the Workspace apps). Plain strings, so it is tested without a phone.
 */
object AgendaLinks {
    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun root(base: String) = base.trimEnd('/')

    /** The home app itself (NoCalendar, NoTasks, NoGantt, NoBoard). */
    fun appUrl(home: Home, base: String): String = when (home) {
        Home.CALENDAR -> "${root(base)}/index.php/apps/calendar/"
        Home.TASKS -> "${root(base)}/index.php/apps/tasks/"
        Home.GANTT -> "${root(base)}/index.php/apps/nc_gantt/"
        Home.BOARD -> "${root(base)}/index.php/apps/deck/"
    }

    /**
     * The page of one item. An event opens NoCalendar on its day (the app's address for one event is built from the
     * object's own address, which is not verified against the live app yet). A to-do, a task and a card open their own page.
     */
    fun itemUrl(item: AgendaItem, base: String, zone: ZoneId): String {
        val b = root(base)
        return when {
            item.home == Home.CALENDAR -> {
                val day = item.firstDay(zone)
                if (day != null) "$b/index.php/apps/calendar/timeGridDay/$day" else appUrl(Home.CALENDAR, base)
            }
            item.home == Home.TASKS && item.calendarUri.isNotEmpty() && item.objectUri.isNotEmpty() ->
                "$b/index.php/apps/tasks/calendars/${encode(item.calendarUri)}/tasks/${encode(item.objectUri)}"
            item.home == Home.TASKS -> appUrl(Home.TASKS, base)
            item.home == Home.GANTT && item.projectId > 0 -> {
                val task = if (item.kind == ItemKind.GANTT_TASK && item.taskId > 0) "&task=${item.taskId}" else ""
                "$b/index.php/apps/nc_gantt/?project=${item.projectId}$task"
            }
            item.home == Home.GANTT -> appUrl(Home.GANTT, base)
            item.home == Home.BOARD && item.cardId > 0 -> "$b/index.php/apps/deck/card/${item.cardId}"
            else -> appUrl(Home.BOARD, base)
        }
    }
}
