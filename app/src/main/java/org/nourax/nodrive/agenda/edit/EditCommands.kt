/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.WritableCalendar
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.registry.Agent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** What the person typed for an event. [agentId] is `SELF` or the id of one of their agents. */
data class EventForm(
    val title: String,
    val allDay: Boolean,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val description: String,
    val agentId: String
) {
    companion object {
        fun of(item: AgendaItem, zone: ZoneId): EventForm {
            val start = item.start?.atZone(zone)
            val end = item.end?.atZone(zone)
            val today = LocalDate.now(zone)
            return EventForm(
                title = item.title,
                allDay = item.allDay,
                startDate = if (item.allDay) item.startDate ?: today else start?.toLocalDate() ?: today,
                startTime = start?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(DEFAULT_HOUR, 0),
                endDate = if (item.allDay) item.endDate ?: item.startDate ?: today else end?.toLocalDate() ?: start?.toLocalDate() ?: today,
                endTime = end?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(DEFAULT_HOUR + 1, 0),
                description = item.description,
                agentId = if (item.agent.isSelf) EditKeys.SELF else item.agent.id
            )
        }

        const val DEFAULT_HOUR = 9
    }
}

/**
 * What the person typed for a to-do. A null date is "none". [dueTime] is set only when the to-do has a due time. [startTime] is the
 * time of day the to-do's start already has: it is kept (not typed), and only used to check the dates.
 */
data class TodoForm(
    val title: String,
    val start: LocalDate?,
    val due: LocalDate?,
    val dueTime: LocalTime?,
    val description: String,
    val agentId: String,
    val startTime: LocalTime? = null
) {
    /**
     * A start after the due date is refused (the server would too: it is not a valid to-do). On the same day, a due time before the
     * start's time of day is refused as well, when both have a time.
     */
    fun datesValid(): Boolean {
        if (start == null || due == null) return true
        if (due.isBefore(start)) return false
        return !(due == start && startTime != null && dueTime != null && dueTime.isBefore(startTime))
    }

    companion object {
        fun of(item: AgendaItem, zone: ZoneId): TodoForm = TodoForm(
            title = item.title,
            start = item.startDate,
            due = item.due,
            dueTime = item.end?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0),
            description = item.description,
            agentId = if (item.agent.isSelf) EditKeys.SELF else item.agent.id,
            startTime = item.todoStartTime
        )
    }
}

data class TaskForm(val start: LocalDate?, val end: LocalDate?, val percent: Int, val status: String, val agentId: String) {
    companion object {
        fun of(item: AgendaItem): TaskForm = TaskForm(
            start = item.startDate,
            end = item.endDate ?: item.due,
            percent = item.percentDone.coerceAtLeast(0),
            status = item.status.ifEmpty { "not_started" },
            agentId = if (item.agent.isSelf) EditKeys.SELF else item.agent.id
        )
    }
}

data class CardForm(val due: LocalDate?, val agentId: String) {
    companion object {
        fun of(item: AgendaItem): CardForm = CardForm(item.due, if (item.agent.isSelf) EditKeys.SELF else item.agent.id)
    }
}

/**
 * Builds the commands of the table of design section 9 from what the person typed. A command holds only what changed, so it can be
 * applied again on a newer version without overwriting what someone else changed meanwhile. Null: nothing changed.
 */
object EditCommands {
    private fun base(item: AgendaItem, kind: CommandKind, change: Map<String, String>): EditCommand {
        val locator = HashMap<String, String>()
        when (item.home) {
            Home.CALENDAR, Home.TASKS -> {
                locator["objectHref"] = item.objectHref
                locator["calendarHref"] = item.calendarHref
                locator["isTodo"] = (item.kind == ItemKind.TODO).toString()
            }
            Home.GANTT -> {
                locator["projectId"] = item.projectId.toString()
                locator["taskId"] = item.taskId.toString()
            }
            Home.BOARD -> {
                locator["boardId"] = item.boardId.toString()
                locator["stackId"] = item.stackId.toString()
                locator["cardId"] = item.cardId.toString()
            }
        }
        return EditCommand(kind = kind, itemKey = item.key, home = item.home, title = item.title, locator = locator, baseVersion = item.version, change = change)
    }

    private fun agentChange(current: String, wanted: String, agents: Collection<Agent>): Map<String, String> {
        if (current == wanted) return emptyMap()
        val allTags = agents.map { it.calendarTag }.filter { it.isNotEmpty() }.joinToString(EditKeys.SEP)
        val agent = agents.firstOrNull { it.id == wanted }
        return mapOf(
            EditKeys.AGENT to (if (wanted == EditKeys.SELF || agent == null) EditKeys.SELF else agent.id),
            EditKeys.AGENT_TAG to agent?.calendarTag.orEmpty(),
            EditKeys.ALL_TAGS to allTags
        )
    }

    private fun currentAgent(item: AgendaItem): String = if (item.agent.isSelf) EditKeys.SELF else item.agent.id

    fun event(item: AgendaItem, form: EventForm, zone: ZoneId, agents: Collection<Agent>): EditCommand? {
        val before = EventForm.of(item, zone)
        val change = LinkedHashMap<String, String>()
        if (form.title != before.title) change[EditKeys.SUMMARY] = form.title
        // A description that was cut for the phone is never written back: the part after the cut would be lost on the server.
        if (form.description != before.description && !EditRules.descriptionLocked(item)) change[EditKeys.DESCRIPTION] = form.description
        val timeChanged = form.allDay != before.allDay || form.startDate != before.startDate || form.endDate != before.endDate ||
            (!form.allDay && (form.startTime != before.startTime || form.endTime != before.endTime))
        if (timeChanged) {
            if (form.allDay) {
                change[EditKeys.START] = EditKeys.day(form.startDate)
                change[EditKeys.END] = EditKeys.day(form.endDate)
            } else {
                change[EditKeys.START] = EditKeys.moment(instant(form.startDate, form.startTime, zone))
                change[EditKeys.END] = EditKeys.moment(instant(form.endDate, form.endTime, zone))
            }
        }
        change += agentChange(currentAgent(item), form.agentId, agents)
        return if (change.isEmpty()) null else base(item, CommandKind.CAL_PATCH, change)
    }

    fun todo(item: AgendaItem, form: TodoForm, zone: ZoneId, agents: Collection<Agent>): EditCommand? {
        val before = TodoForm.of(item, zone)
        val change = LinkedHashMap<String, String>()
        if (form.title != before.title) change[EditKeys.SUMMARY] = form.title
        if (form.description != before.description && !EditRules.descriptionLocked(item)) change[EditKeys.DESCRIPTION] = form.description
        if (form.start != before.start) change[EditKeys.START] = form.start?.let { EditKeys.day(it) }.orEmpty()
        if (form.due != before.due || form.dueTime != before.dueTime) {
            val due = form.due
            change[EditKeys.DUE] = when {
                due == null -> ""
                form.dueTime != null -> EditKeys.moment(instant(due, form.dueTime, zone))
                else -> EditKeys.day(due)
            }
        }
        change += agentChange(currentAgent(item), form.agentId, agents)
        return if (change.isEmpty()) null else base(item, CommandKind.CAL_PATCH, change)
    }

    /** Ticks a to-do done or not done. */
    fun todoDone(item: AgendaItem, done: Boolean, now: Instant): EditCommand =
        base(item, CommandKind.CAL_PATCH, mapOf(EditKeys.DONE to done.toString(), CalendarEdit.COMPLETED_AT to now.toEpochMilli().toString()))

    /** Removes an event or a to-do (it moves to the calendar's trash bin). Only after the person said yes. */
    fun removeObject(item: AgendaItem): EditCommand = base(item, CommandKind.CAL_DELETE, emptyMap())

    fun task(item: AgendaItem, form: TaskForm, agents: Collection<Agent>): List<EditCommand> {
        val before = TaskForm.of(item)
        val out = ArrayList<EditCommand>()
        val change = LinkedHashMap<String, String>()
        if (form.start != before.start && form.start != null) change[EditKeys.START] = EditKeys.day(form.start)
        if (form.end != before.end && form.end != null) change[EditKeys.END] = EditKeys.day(form.end)
        if (form.percent != before.percent) change[EditKeys.PERCENT] = form.percent.toString()
        if (form.status != before.status) {
            if (form.status == "done") change[EditKeys.DONE] = "true" else change[EditKeys.STATUS] = form.status
        }
        if (change.isNotEmpty()) out += base(item, CommandKind.GANTT_UPDATE, change)
        if (form.agentId != currentAgent(item)) {
            out += base(item, CommandKind.GANTT_AGENT, mapOf(EditKeys.AGENT to form.agentId))
        }
        return out
    }

    fun taskDone(item: AgendaItem): EditCommand = base(item, CommandKind.GANTT_UPDATE, mapOf(EditKeys.DONE to "true"))

    fun card(item: AgendaItem, form: CardForm, agents: Collection<Agent>): List<EditCommand> {
        val out = ArrayList<EditCommand>()
        if (form.due != item.due) {
            out += base(item, CommandKind.DECK_UPDATE, mapOf(EditKeys.DUE to (form.due?.let { EditKeys.day(it) }.orEmpty())))
        }
        if (form.agentId != currentAgent(item)) {
            out += base(item, CommandKind.DECK_AGENT, mapOf(EditKeys.AGENT to form.agentId))
        }
        return out
    }

    fun cardDone(item: AgendaItem): EditCommand = base(item, CommandKind.DECK_UPDATE, mapOf(EditKeys.DONE to "true"))

    /** A new event or to-do in [calendar]. The person is the owner of that calendar; the agent is Self or one of theirs. */
    fun create(calendar: WritableCalendar, isTodo: Boolean, change: Map<String, String>, title: String, agents: Collection<Agent>, wanted: String): EditCommand {
        val uid = UUID.randomUUID().toString()
        val agentPart = agentChange(EditKeys.SELF, wanted, agents)
        val full = change + mapOf(
            EditKeys.NEW_KIND to (if (isTodo) "todo" else "event"),
            EditKeys.UID to uid,
            EditKeys.OBJECT_NAME to "$uid.ics"
        ) + agentPart
        return EditCommand(
            kind = CommandKind.CAL_CREATE,
            itemKey = "new:$uid",
            home = if (isTodo) Home.TASKS else Home.CALENDAR,
            title = title,
            locator = mapOf(
                "calendarHref" to calendar.href,
                "calendarUri" to calendar.uri,
                "calendarName" to calendar.name,
                "calendarColor" to calendar.color,
                "category" to (if (calendar.mode == CalendarMode.PERSONAL) Category.PERSONAL.name else Category.WORK.name)
            ),
            change = full
        )
    }

    fun newEvent(calendar: WritableCalendar, form: EventForm, zone: ZoneId, agents: Collection<Agent>): EditCommand {
        val change = LinkedHashMap<String, String>()
        change[EditKeys.SUMMARY] = form.title
        if (form.description.isNotEmpty()) change[EditKeys.DESCRIPTION] = form.description
        if (form.allDay) {
            change[EditKeys.START] = EditKeys.day(form.startDate)
            change[EditKeys.END] = EditKeys.day(form.endDate)
        } else {
            change[EditKeys.START] = EditKeys.moment(instant(form.startDate, form.startTime, zone))
            change[EditKeys.END] = EditKeys.moment(instant(form.endDate, form.endTime, zone))
        }
        return create(calendar, false, change, form.title, agents, form.agentId)
    }

    fun newTodo(calendar: WritableCalendar, form: TodoForm, zone: ZoneId, agents: Collection<Agent>): EditCommand {
        val change = LinkedHashMap<String, String>()
        change[EditKeys.SUMMARY] = form.title
        if (form.description.isNotEmpty()) change[EditKeys.DESCRIPTION] = form.description
        form.start?.let { change[EditKeys.START] = EditKeys.day(it) }
        form.due?.let { change[EditKeys.DUE] = EditKeys.day(it) }
        return create(calendar, true, change, form.title, agents, form.agentId)
    }

    private fun instant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant = date.atTime(time).atZone(zone).toInstant()
}
