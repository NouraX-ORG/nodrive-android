/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.net

/**
 * The only writes NoAgenda may send (design 9, plan 2.4). The transport asks this before it sends anything that could change data:
 *  - a calendar object (event or to-do) can be created, replaced and deleted, in the person's calendars only;
 *  - a NoGantt task can be updated, and one of its changes undone; nothing in NoGantt can be created or deleted;
 *  - a NoBoard card can be updated, a label put on it or taken off, and a board label created; no card or board is deleted;
 *  - never a file of the Drive (`remote.php/dav/files`), and never a delete anywhere but a calendar object.
 */
object WritePolicy {
    private const val OBJECT = """[^/?#]+/[^/?#]+/[^/?#]+\.ics"""
    private val calendarObject = Regex("""^/?(?:[^?#]*/)?remote\.php/dav/calendars/$OBJECT$""")
    private val ganttTask = Regex("""^/?ocs/v2\.php/apps/nc_gantt/api/v1/tasks/\d+(?:\?[^#]*)?$""")
    private val ganttRevert = Regex("""^/?ocs/v2\.php/apps/nc_gantt/api/v1/changes/[A-Za-z0-9_-]+/revert(?:\?[^#]*)?$""")
    private val deckCard = Regex("""^/?index\.php/apps/deck/api/v1\.0/boards/\d+/stacks/\d+/cards/\d+(?:/(?:assignLabel|removeLabel))?$""")
    private val deckLabel = Regex("""^/?index\.php/apps/deck/api/v1\.0/boards/\d+/labels$""")

    private val READS = setOf("GET", "PROPFIND", "REPORT")

    fun isRead(method: String): Boolean = method in READS

    fun allows(method: String, path: String): Boolean {
        if (isRead(method)) return true
        val plain = path.removePrefix("/")
        if (plain.contains("..") || plain.contains("//")) return false
        return when (method) {
            "PUT" -> calendarObject.matches(path.substringBefore('?')) || ganttTask.matches(path) || deckCard.matches(path.substringBefore('?'))
            "DELETE" -> calendarObject.matches(path.substringBefore('?'))
            "POST" -> ganttRevert.matches(path) || deckLabel.matches(path.substringBefore('?'))
            else -> false
        }
    }
}
