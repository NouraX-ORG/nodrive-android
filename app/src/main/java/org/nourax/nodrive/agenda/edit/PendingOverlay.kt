/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The changes that wait to be sent are drawn on top of the saved data (plan 2.3), so the screen, the workload and the clashes show
 * the effect at once. A row that has a change waiting carries `pending` ("Waiting to send"). A done item, or one that is deleted,
 * leaves the lists. The saved data is not touched: once the server has the change, the next read brings the same picture.
 */
object PendingOverlay {
    fun apply(
        items: List<AgendaItem>,
        commands: List<EditCommand>,
        registry: AgentRegistry,
        today: LocalDate,
        zone: ZoneId
    ): List<AgendaItem> {
        if (commands.isEmpty()) return items
        val rows = ArrayList(items)
        for (command in commands) {
            when (command.kind) {
                CommandKind.CAL_CREATE -> rows += created(command, registry, today, zone)
                else -> {
                    val i = rows.indexOfFirst { it.key == command.itemKey }
                    if (i < 0) continue
                    val changed = change(rows[i], command, registry, today, zone)
                    if (changed == null) rows.removeAt(i) else rows[i] = changed
                }
            }
        }
        return rows
    }

    private fun agentRef(change: Map<String, String>, registry: AgentRegistry): Pair<AgentRef, WorkKind>? {
        val id = change[EditKeys.AGENT] ?: return null
        if (id == EditKeys.SELF || id.isEmpty()) return AgentRef.SELF to WorkKind.SELF
        val agent = registry.byId(id) ?: return AgentRegistry.unknownRef(id) to WorkKind.MY_AGENT
        return AgentRegistry.refFor(agent) to WorkKind.MY_AGENT
    }

    private fun dayOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    private fun withDue(item: AgendaItem, due: LocalDate?, moment: Instant?, today: LocalDate): AgendaItem {
        val late = due != null && due.isBefore(today)
        return item.copy(
            due = due,
            endDate = due,
            end = moment,
            late = late,
            lateDays = if (late && due != null) ChronoUnit.DAYS.between(due, today).toInt() else 0,
            dueToday = due != null && due == today
        )
    }

    /** The row with the change on it; null when the change takes it off the lists (done, deleted). */
    private fun change(item: AgendaItem, command: EditCommand, registry: AgentRegistry, today: LocalDate, zone: ZoneId): AgendaItem? {
        val c = command.change
        var out = item.copy(pending = true)
        when (command.kind) {
            CommandKind.CAL_DELETE -> return null
            CommandKind.CAL_PATCH -> {
                c[EditKeys.SUMMARY]?.let { out = out.copy(title = it) }
                c[EditKeys.DESCRIPTION]?.let { out = out.copy(description = it) }
                if (item.kind == ItemKind.EVENT) {
                    out = event(out, c, zone)
                } else {
                    if (c[EditKeys.DONE] == "true") return null
                    c[EditKeys.START]?.let { text ->
                        val v = EditKeys.parse(text)
                        out = out.copy(startDate = (v as? EditKeys.Value.Day)?.date ?: (v as? EditKeys.Value.Moment)?.let { dayOf(it.instant, zone) })
                    }
                    when (val v = EditKeys.parse(c[EditKeys.DUE])) {
                        is EditKeys.Value.Day -> out = withDue(out, v.date, null, today)
                        is EditKeys.Value.Moment -> out = withDue(out, dayOf(v.instant, zone), v.instant, today)
                        EditKeys.Value.None -> out = withDue(out, null, null, today)
                        null -> Unit
                    }
                }
            }
            CommandKind.GANTT_UPDATE -> {
                if (c[EditKeys.DONE] == "true" || c[EditKeys.STATUS] == "done") return null
                (EditKeys.parse(c[EditKeys.START]) as? EditKeys.Value.Day)?.let { out = out.copy(startDate = it.date) }
                (EditKeys.parse(c[EditKeys.END]) as? EditKeys.Value.Day)?.let { out = withDue(out, it.date, null, today) }
                c[EditKeys.PERCENT]?.toIntOrNull()?.let { out = out.copy(percentDone = it) }
                c[EditKeys.STATUS]?.let { out = out.copy(status = it) }
            }
            CommandKind.DECK_UPDATE -> {
                if (c[EditKeys.DONE] == "true") return null
                (EditKeys.parse(c[EditKeys.DUE]) as? EditKeys.Value.Day)?.let { out = withDue(out, it.date, null, today) }
            }
            CommandKind.GANTT_AGENT, CommandKind.DECK_AGENT -> Unit
            CommandKind.CAL_CREATE -> Unit
        }
        agentRef(c, registry)?.let { (ref, kind) -> out = out.copy(agent = ref, workKind = kind) }
        return out
    }

    private fun event(item: AgendaItem, c: Map<String, String>, zone: ZoneId): AgendaItem {
        var out = item
        val start = EditKeys.parse(c[EditKeys.START])
        val end = EditKeys.parse(c[EditKeys.END])
        when (start) {
            is EditKeys.Value.Day -> {
                out = out.copy(allDay = true, timed = false, start = null, end = null, startDate = start.date, endDate = (end as? EditKeys.Value.Day)?.date ?: start.date)
            }
            is EditKeys.Value.Moment -> {
                val stop = (end as? EditKeys.Value.Moment)?.instant ?: start.instant.plusSeconds(HOUR_SECONDS)
                out = out.copy(allDay = false, timed = true, start = start.instant, end = stop, startDate = null, endDate = null)
            }
            else -> Unit
        }
        return out
    }

    /** A new event or to-do that waits to be sent. */
    private fun created(command: EditCommand, registry: AgentRegistry, today: LocalDate, zone: ZoneId): AgendaItem {
        val isTodo = command.change[EditKeys.NEW_KIND] == "todo"
        var item = AgendaItem(
            key = command.itemKey,
            kind = if (isTodo) ItemKind.TODO else ItemKind.EVENT,
            home = if (isTodo) Home.TASKS else Home.CALENDAR,
            title = command.change[EditKeys.SUMMARY].orEmpty(),
            description = command.change[EditKeys.DESCRIPTION].orEmpty(),
            sourceKey = "cal:${command.loc("calendarUri")}",
            sourceName = command.loc("calendarName"),
            sourceColor = command.loc("calendarColor"),
            category = if (command.loc("category") == Category.PERSONAL.name) Category.PERSONAL else Category.WORK,
            calendarUri = command.loc("calendarUri"),
            calendarHref = command.loc("calendarHref"),
            pending = true
        )
        item = if (isTodo) {
            var todo = item
            (EditKeys.parse(command.change[EditKeys.START]) as? EditKeys.Value.Day)?.let { todo = todo.copy(startDate = it.date) }
            when (val v = EditKeys.parse(command.change[EditKeys.DUE])) {
                is EditKeys.Value.Day -> todo = withDue(todo, v.date, null, today)
                is EditKeys.Value.Moment -> todo = withDue(todo, dayOf(v.instant, zone), v.instant, today)
                else -> Unit
            }
            todo
        } else {
            event(item, command.change, zone)
        }
        val (ref, kind) = agentRef(command.change, registry) ?: (AgentRef.SELF to WorkKind.SELF)
        return item.copy(agent = ref, workKind = kind)
    }

    private const val HOUR_SECONDS = 3600L
}
