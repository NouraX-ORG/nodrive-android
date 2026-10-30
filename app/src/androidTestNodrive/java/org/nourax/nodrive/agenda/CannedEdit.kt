/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.nourax.nodrive.agenda.edit.CommandKind
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.EditCommand
import org.nourax.nodrive.agenda.edit.EditConflict
import org.nourax.nodrive.agenda.edit.EditNotice
import org.nourax.nodrive.agenda.edit.EditState
import org.nourax.nodrive.agenda.edit.GanttAgentChange
import org.nourax.nodrive.agenda.edit.PendingOverlay
import org.nourax.nodrive.agenda.logic.Analysis
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.Conflicts
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WritableCalendar
import org.nourax.nodrive.agenda.logic.Workload
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Canned data for the edit screens: a clash, an overbooked day, a repeating event, an invitation, a task in a viewer project. */
object CannedEdit {
    private val registry = AgentRegistry.parse(
        (
            """{"schema":"nourax-agent-registry","version":1,"agents":[
            {"id":"AG-0001","name":"Writer-Bot","person":"ali","status":"active","organisation":"demo-org","links":{"calendar_tag":"agent:AG-0001","noboard_label":"Writer-Bot"}},
            {"id":"AG-0003","name":"Research-Bot","person":"ali","status":"paused","organisation":"demo-org","links":{"calendar_tag":"agent:AG-0003","noboard_label":"Research-Bot"}}]}"""
            ).toByteArray()
    )

    private val blue = "#2563EB"

    /** Twelve hours of own work on today: against seven free hours the day is overbooked. */
    private val bigPush = AgendaItem(
        key = "gantt:task:110", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Big push",
        startDate = CannedAgenda.TODAY, endDate = CannedAgenda.TODAY, due = CannedAgenda.TODAY, sourceKey = "gantt:1", sourceName = "Website",
        sourceColor = blue, projectId = 1, taskId = 110, myWorkHours = 12.0, percentDone = 0, status = "not_started", version = "2026-10-28 08:00:00"
    )

    private val repeating = AgendaItem(
        key = "cal:work/standup.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Stand-up", timed = true,
        start = Instant.parse("2026-10-28T09:30:00Z"), end = Instant.parse("2026-10-28T09:45:00Z"), repeating = true,
        sourceKey = "cal:Work", sourceName = "Work", sourceColor = blue, calendarUri = "work", objectUri = "standup.ics"
    )

    private val invitation = AgendaItem(
        key = "cal:work/invite.ics", kind = ItemKind.EVENT, home = Home.CALENDAR, title = "Planning meeting", timed = true,
        start = Instant.parse("2026-10-28T15:00:00Z"), end = Instant.parse("2026-10-28T16:00:00Z"), invitation = true,
        sourceKey = "cal:Work", sourceName = "Work", sourceColor = blue, calendarUri = "work", objectUri = "invite.ics"
    )

    private val viewerTask = AgendaItem(
        key = "gantt:task:120", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Internal checklist",
        startDate = CannedAgenda.TODAY, endDate = CannedAgenda.TODAY.plusDays(1), due = CannedAgenda.TODAY.plusDays(1),
        sourceKey = "gantt:2", sourceName = "Internal", sourceColor = "#E11D48", projectId = 2, taskId = 120, readOnly = true,
        readOnlyReason = ReadOnlyReason.VIEWER_PROJECT
    )

    private val calendars = listOf(
        WritableCalendar("work", "/remote.php/dav/calendars/ali/work/", "Work", blue, true, false, true, CalendarMode.WORK),
        WritableCalendar("personal", "/remote.php/dav/calendars/ali/personal/", "Personal", "#795AAF", true, true, false, CalendarMode.PERSONAL)
    )

    /** Self and the choices the analysis offers on a task: Writer-Bot, a paused agent, and one whose planning person is not in the project. */
    val choices = listOf(
        GanttAgentChange.Choice("AG-0001", "Writer-Bot", true, GanttAgentChange.ChoiceNote.NONE),
        GanttAgentChange.Choice("AG-0003", "Research-Bot", false, GanttAgentChange.ChoiceNote.PAUSED),
        GanttAgentChange.Choice("AG-0004", "Invoice-Bot", false, GanttAgentChange.ChoiceNote.NOT_IN_PROJECT)
    )

    fun snapshot(): AgendaSnapshot {
        val day = CannedAgenda.day()
        val items = day.build.items + listOf(bigPush, repeating, invitation, viewerTask)
        return day.copy(build = day.build.copy(items = items, writableCalendars = calendars, myAgents = registry.ofPerson("ali")))
    }

    fun agentRef(id: String): AgentRef = registry.byId(id)?.let { AgentRegistry.refFor(it) } ?: AgentRegistry.unknownRef(id)

    fun registry(): AgentRegistry = registry
}

/**
 * An engine for the edit screens: a change is "sent" at once (online) or kept as waiting (offline), the way the real one does it, with
 * the same overlay, clashes and workload. It records what the screen asked for.
 */
class CannedEditEngine(private val start: AgendaSnapshot, var online: Boolean = true) : AgendaEngine {
    val submitted = CopyOnWriteArrayList<EditCommand>()
    val resolved = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    private var items = start.build.items
    private val pending = ArrayList<EditCommand>()
    private val conflicts = ArrayList<EditConflict>()
    private var notice: EditNotice? = null
    private var sequence = 0L
    private var settings = start.settings

    /** A conflict the screen shows in the "Not sent" list. */
    fun injectConflict(command: EditCommand, reason: ConflictReason = ConflictReason.CHANGED_ELSEWHERE) {
        conflicts += EditConflict(++sequence, "ali", command, reason, "", "", 0)
    }

    private fun rebuild(): AgendaSnapshot {
        val shown = PendingOverlay.apply(items, pending, CannedEdit.registry(), CannedAgenda.TODAY, CannedAgenda.UTC)
        val build = start.build.copy(items = shown)
        val gantt = shown.filter { it.kind == ItemKind.GANTT_TASK && !it.problemOnly && it.workKind != WorkKind.TEAM }.associate { it.key to CannedEdit.choices }
        val analysis = Analysis(
            badges = Conflicts.badges(shown, build.workingHours, build.holidays),
            days = Workload.compute(shown, build.workingHours, build.holidays, CannedAgenda.UTC, CannedAgenda.TODAY.minusDays(7), CannedAgenda.TODAY.plusDays(30)),
            ganttChoices = gantt
        )
        return start.copy(build = build, analysis = analysis, settings = settings, edit = EditState(pending.toList(), conflicts.toList(), notice))
    }

    override fun snapshot(): AgendaSnapshot = rebuild()

    override fun refresh(manual: Boolean): AgendaSnapshot = rebuild()

    override fun updateSettings(change: (SourceSettings) -> SourceSettings): AgendaSnapshot {
        settings = change(settings)
        if (settings.personalAnswered) items = items.toList()
        return rebuild()
    }

    override fun submit(command: EditCommand): AgendaSnapshot {
        record(command)
        return rebuild()
    }

    // The screen stores the changes first (enqueue) and sends afterwards (sendPending).
    override fun enqueue(commands: List<EditCommand>) {
        commands.forEach { record(it) }
    }

    override fun hasPending(): Boolean = pending.isNotEmpty()

    private fun record(command: EditCommand) {
        val stored = command.copy(id = ++sequence)
        submitted += stored
        if (online) {
            // sent: the change is part of the data now
            items = PendingOverlay.apply(items, listOf(stored), CannedEdit.registry(), CannedAgenda.TODAY, CannedAgenda.UTC).map { it.copy(pending = false) }
            notice = EditNotice(sequence, stored.title)
        } else {
            pending += stored
            notice = EditNotice(sequence, stored.title, queued = true)
        }
    }

    override fun resolveConflict(id: Long, applyAgain: Boolean): AgendaSnapshot {
        resolved += id to applyAgain
        val conflict = conflicts.firstOrNull { it.id == id }
        conflicts.removeAll { it.id == id }
        if (conflict != null && applyAgain) return submit(conflict.command.copy(rebase = true))
        return rebuild()
    }

    override fun sendPending(manual: Boolean): AgendaSnapshot {
        if (online) {
            for (command in pending) items = PendingOverlay.apply(items, listOf(command), CannedEdit.registry(), CannedAgenda.TODAY, CannedAgenda.UTC).map { it.copy(pending = false) }
            pending.clear()
        }
        return rebuild()
    }

    fun kinds(): List<CommandKind> = submitted.map { it.kind }
}
