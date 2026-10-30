/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.registry.Agent
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Timeline's date editor (design 8, 9; plan 3.4): no dragging on a phone, so a tap opens the sheet, which moves a bar by a day or
 * sets new dates. Every change goes through the same commands, rules and queue as the edit form (EditCommands, EditRules), so a locked
 * row gives no command. A NoGantt task moved by whole days is sent with `adjust_lag`, NoGantt's own move behaviour.
 */
object TimelineEdit {
    /** The first and the last day of what the date editor changes. A card has one day (its due date): [single]. */
    data class Span(val start: LocalDate, val end: LocalDate, val single: Boolean = false)

    /** The dates of a row that the date editor can change; null for a row without them (a milestone, an event, a task without a start). */
    fun spanOf(item: AgendaItem): Span? = when (item.kind) {
        ItemKind.GANTT_TASK -> item.startDate?.let { start ->
            val end = item.endDate ?: item.due ?: start
            Span(start, if (end.isBefore(start)) start else end)
        }
        ItemKind.TODO -> {
            val start = item.startDate
            val due = item.due
            if (start == null || due == null || due.isBefore(start)) null else Span(start, due)
        }
        ItemKind.CARD -> item.due?.let { Span(it, it, single = true) }
        else -> null
    }

    /** The dates after moving by [days]. */
    fun shifted(span: Span, days: Int): Pair<LocalDate, LocalDate> = span.start.plusDays(days.toLong()) to span.end.plusDays(days.toLong())

    /** True when both days moved by the same number of days. */
    fun isPureMove(span: Span, start: LocalDate, end: LocalDate): Boolean =
        ChronoUnit.DAYS.between(span.start, start) == ChronoUnit.DAYS.between(span.end, end)

    /** Whether these dates may be written: the end is not before the start, and a to-do's dates fit its times. */
    fun valid(item: AgendaItem, start: LocalDate, end: LocalDate, zone: ZoneId): Boolean {
        if (end.isBefore(start)) return false
        if (item.kind == ItemKind.TODO) return TodoForm.of(item, zone).copy(start = start, due = end).datesValid()
        return true
    }

    /**
     * The command that gives the row these dates. Null: nothing changes, the dates are not valid, or the rules say this row can not be
     * changed here (a locked bar gives no command).
     */
    fun command(item: AgendaItem, start: LocalDate, end: LocalDate, zone: ZoneId, agents: Collection<Agent>): EditCommand? {
        if (!EditRules.of(item).canEdit) return null
        val span = spanOf(item) ?: return null
        val newStart = if (span.single) end else start
        if (newStart == span.start && end == span.end) return null
        if (!valid(item, newStart, end, zone)) return null
        val command = when (item.kind) {
            ItemKind.GANTT_TASK -> EditCommands.task(item, TaskForm.of(item).copy(start = newStart, end = end), agents).firstOrNull { it.kind == CommandKind.GANTT_UPDATE }
            ItemKind.TODO -> EditCommands.todo(item, TodoForm.of(item, zone).copy(start = newStart, due = end), zone, agents)
            ItemKind.CARD -> EditCommands.card(item, CardForm.of(item).copy(due = end), agents).firstOrNull { it.kind == CommandKind.DECK_UPDATE }
            else -> null
        } ?: return null
        val how = if (isPureMove(span, newStart, end)) EditKeys.MOVE_PURE else EditKeys.MOVE_SET
        return command.copy(
            change = command.change + mapOf(EditKeys.MOVE to how, EditKeys.MOVE_START to newStart.toString(), EditKeys.MOVE_END to end.toString())
        )
    }

    /** Moves the row by [days] (negative = earlier). */
    fun moveBy(item: AgendaItem, days: Int, zone: ZoneId, agents: Collection<Agent>): EditCommand? {
        val span = spanOf(item) ?: return null
        val (start, end) = shifted(span, days)
        return command(item, start, end, zone, agents)
    }
}
