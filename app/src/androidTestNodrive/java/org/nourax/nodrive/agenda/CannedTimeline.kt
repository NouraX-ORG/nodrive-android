/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemDependency
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind
import java.time.LocalDate

/**
 * Canned data for the Timeline screens (no server): two projects (one a viewer project), a phase, a bar with an agent, dependencies
 * (one from a task of somebody else, so it is a stub), a milestone, a to-do with a start and a due date, a dated card, and busy time
 * (a call today, a workshop and a half-day meeting later) on a fixed day.
 */
object CannedTimeline {
    private val today: LocalDate = CannedAgenda.TODAY
    private const val BLUE = "#2563EB"
    private const val RED = "#E11D48"
    private const val PURPLE = "#795AAF"
    private const val GREEN = "#16A34A"
    private val writer = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot", status = "active")

    private fun d(offset: Long): LocalDate = today.plusDays(offset)

    private fun task(
        id: Int,
        title: String,
        from: Long,
        to: Long,
        percent: Int,
        phase: Int = 0,
        deps: List<ItemDependency> = emptyList(),
        agent: AgentRef = AgentRef.SELF
    ) = AgendaItem(
        key = "gantt:task:$id", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = title,
        startDate = d(from), endDate = d(to), due = d(to), sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE,
        projectId = 1, projectName = "Website", phaseId = phase, phaseName = if (phase > 0) "Phase 1" else "", taskId = id,
        percentDone = percent, status = "in_progress", version = "2026-10-28 08:00:00", dependencies = deps,
        agent = agent, workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT, myWorkHours = 6.0
    )

    private val workshop = AgendaItem(
        key = "cal:work/workshop.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Workshop", timed = true,
        start = d(4).atTime(9, 0).atZone(CannedAgenda.UTC).toInstant(), end = d(4).atTime(17, 0).atZone(CannedAgenda.UTC).toInstant(),
        sourceKey = "cal:Work", sourceName = "Work", sourceColor = BLUE, category = Category.WORK, calendarUri = "work", objectUri = "workshop.ics"
    )

    private val halfDay = AgendaItem(
        key = "cal:work/planning.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Planning", timed = true,
        start = d(9).atTime(9, 0).atZone(CannedAgenda.UTC).toInstant(), end = d(9).atTime(13, 0).atZone(CannedAgenda.UTC).toInstant(),
        sourceKey = "cal:Work", sourceName = "Work", sourceColor = BLUE, category = Category.WORK, calendarUri = "work", objectUri = "planning.ics"
    )

    private fun work(): List<AgendaItem> = listOf(
        task(102, "Write homepage copy", -2, 2, 40, phase = 401, agent = writer),
        task(101, "Prepare client demo", -1, 1, 10),
        task(
            104, "Publish banners", 3, 6, 0,
            deps = listOf(
                ItemDependency("gantt:task:101", "EA", 0.0, "Prepare client demo", ""),
                ItemDependency("gantt:task:103", "EA", 0.0, "Banner design", "Sara")
            )
        ),
        task(105, "Final check", 8, 10, 0, phase = 401, deps = listOf(ItemDependency("gantt:task:104", "EA", 1.0, "Publish banners", ""))),
        AgendaItem(
            key = "gantt:ms:7", kind = ItemKind.MILESTONE, home = Home.GANTT, title = "Launch", startDate = d(12), endDate = d(12), due = d(12),
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, projectName = "Website", taskId = 7,
            readOnly = true, readOnlyReason = ReadOnlyReason.MILESTONE
        ),
        AgendaItem(
            key = "gantt:task:120", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Internal checklist", startDate = d(0), endDate = d(4), due = d(4),
            sourceKey = "gantt:2", sourceName = "Internal", sourceColor = RED, projectId = 2, projectName = "Internal", taskId = 120, percentDone = 25,
            readOnly = true, readOnlyReason = ReadOnlyReason.VIEWER_PROJECT, status = "in_progress"
        ),
        AgendaItem(
            key = "cal:personal/draft.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Draft report", startDate = d(-1), due = d(3),
            endDate = d(3), sourceKey = "cal:Personal", sourceName = "Personal", sourceColor = PURPLE, category = Category.PERSONAL,
            calendarUri = "personal", calendarHref = "/remote.php/dav/calendars/ali/personal/",
            objectHref = "/remote.php/dav/calendars/ali/personal/draft.ics", objectUri = "draft.ics", version = "\"e1\""
        ),
        AgendaItem(
            key = "deck:card:503", kind = ItemKind.CARD, home = Home.BOARD, title = "Pay invoice", due = d(2), endDate = d(2),
            sourceKey = "board:10", sourceName = "Team board", sourceColor = GREEN, boardId = 10, stackId = 4, cardId = 503, version = "1000"
        ),
        workshop,
        halfDay
    )

    /** Ali's Timeline: the picture above, with today's meetings of the Day view for the busy time. */
    fun snapshot(): AgendaSnapshot {
        val day = CannedAgenda.day()
        val events = CannedAgenda.items.filter { it.kind == ItemKind.EVENT }
        return day.copy(build = day.build.copy(items = events + work(), writableCalendars = CannedEdit.snapshot().build.writableCalendars))
    }

    /** Nothing with a start and an end in the range: the empty state. */
    fun empty(): AgendaSnapshot {
        val day = CannedAgenda.day()
        return day.copy(build = day.build.copy(items = CannedAgenda.items.filter { it.kind == ItemKind.EVENT }))
    }
}
