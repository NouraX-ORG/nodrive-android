/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.dav.CalendarSnapshot
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.deck.DeckMapper
import org.nourax.nodrive.agenda.deck.DeckSnapshot
import org.nourax.nodrive.agenda.gantt.GanttMapper
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AutoHiddenReason
import org.nourax.nodrive.agenda.model.SourceEntry
import org.nourax.nodrive.agenda.model.SourceNote
import org.nourax.nodrive.agenda.model.SourceType
import org.nourax.nodrive.agenda.ocs.ServerInfo
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.num
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class BuildInput(
    val davUser: String,
    val server: ServerInfo,
    val calendar: CalendarSnapshot,
    val gantt: GanttSnapshot,
    val deck: DeckSnapshot,
    val registry: AgentRegistry,
    val settings: SourceSettings,
    val today: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    val accountEmail: String = ""
)

data class OwnCalendar(val uri: String, val name: String)

/** A calendar or task list the person may add to (design 9: only where they may write). */
data class WritableCalendar(
    val uri: String,
    val href: String,
    val name: String,
    val color: String,
    val events: Boolean,
    val todos: Boolean,
    val isDefault: Boolean,
    val mode: CalendarMode
)

data class BuildOutput(
    val items: List<AgendaItem>,
    val sources: List<SourceEntry>,
    /** The own calendars of the one-time question "Which of your calendars are personal?". */
    val ownCalendars: List<OwnCalendar>,
    val needsPersonalQuestion: Boolean,
    /** At least one project or board is set to Do + Oversee: the My team section exists. */
    val anyOversee: Boolean,
    val ganttNotLinked: Boolean,
    val ganttUnavailable: Boolean,
    val boardUnavailable: Boolean,
    val ganttReadOnlyByGuard: Boolean,
    val ganttContractProblem: String,
    val boardReadOnlyByGuard: Boolean,
    val myAgents: List<Agent>,
    val taskCopyCalendarUri: String,
    val workingHours: WorkingHours,
    /** The holidays of NoGantt that apply to this person (their planning person's calendar, or for everybody). */
    val holidays: Set<LocalDate> = emptySet(),
    val writableCalendars: List<WritableCalendar> = emptyList()
)

/** Turns the raw data of all homes into the rows and the Sources screen (design 6). */
object AgendaBuilder {
    /** The person's working pattern: the override, else the schedule of the linked planning person, else Mon-Fri 09:00-17:00. */
    fun workingHours(input: BuildInput): WorkingHours {
        input.settings.workingHours?.let { return it }
        var days = 31
        var from = LocalTime.of(9, 0)
        var to = LocalTime.of(17, 0)
        val me = input.server.uid.ifEmpty { input.davUser }
        val scheduleId = input.gantt.resources.objects()
            .firstOrNull { it.str("type") == "person" && it.str("uid").equals(me, ignoreCase = true) }
            ?.let { intOf(it.get("schedule_id")) } ?: 0
        if (scheduleId > 0) {
            input.gantt.workSchedules.objects().firstOrNull { intOf(it.get("id")) == scheduleId }?.let { days = it.int("work_days", 31) }
        }
        val start = runCatching { LocalTime.parse(input.gantt.settings.str("work_start").take(5)) }.getOrNull()
        val end = runCatching { LocalTime.parse(input.gantt.settings.str("work_end").take(5)) }.getOrNull()
        if (start != null && end != null && start.isBefore(end)) {
            from = start
            to = end
        }
        return WorkingHours(days, from, to)
    }

    /** The holidays of this person: the ones for their planning person's work calendar, and the ones for everybody. */
    fun holidays(input: BuildInput): Set<LocalDate> {
        val me = input.server.uid.ifEmpty { input.davUser }
        val calendarId = input.gantt.resources.objects()
            .firstOrNull { it.str("type") == "person" && it.str("uid").equals(me, ignoreCase = true) }
            ?.get("calendar_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val out = HashSet<LocalDate>()
        for (holiday in input.gantt.holidays.objects()) {
            val id = holiday.get("calendar_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            if (id != null && id != calendarId) continue
            runCatching { LocalDate.parse(holiday.str("holiday_date").take(10)) }.getOrNull()?.let { out += it }
        }
        return out
    }

    fun build(input: BuildInput): BuildOutput {
        val me = input.server.uid.ifEmpty { input.davUser }
        val myAddresses = ArrayList(input.calendar.addresses)
        if (input.accountEmail.isNotEmpty()) myAddresses += input.accountEmail.lowercase()
        if (input.server.email.isNotEmpty()) myAddresses += input.server.email.lowercase()
        val hoursPerPt = input.gantt.settings.num("hours_per_pt", 8.0)
        val ctx = BuildContext(
            me = me,
            myName = input.server.displayName.ifEmpty { me },
            myAddresses = myAddresses,
            myGroups = input.server.groups,
            serverAdmin = input.server.isServerAdmin,
            registry = input.registry,
            settings = input.settings,
            today = input.today,
            zone = input.zone,
            now = input.now,
            hoursPerPt = if (hoursPerPt > 0) hoursPerPt else 8.0,
            taskCopyCalendarUri = GanttMapper.taskCopyCalendarUri(input.gantt)
        )

        // ---- rows --------------------------------------------------------------------------------------------
        val items = ArrayList<AgendaItem>()
        CalendarMapper.map(ctx, input.calendar, items)
        val gantt = GanttMapper.Result()
        GanttMapper.map(ctx, input.gantt, gantt)
        items += gantt.items
        DeckMapper.map(ctx, input.deck, gantt.linkedCardRows, items)

        // ---- Sources screen ------------------------------------------------------------------------------------
        val sources = ArrayList<SourceEntry>()
        val ownCalendars = ArrayList<OwnCalendar>()
        var anyOversee = false
        for (cal in input.calendar.calendars) {
            if (cal.ignored) continue
            val autoHidden = SourceSettings.autoHiddenReason(cal, ctx.taskCopyCalendarUri)
            val (note, noteArg) = when {
                cal.subscribed -> SourceNote.SUBSCRIPTION to ""
                cal.birthdays -> SourceNote.BIRTHDAYS to ""
                !cal.mine -> if (cal.ownerName.isEmpty()) SourceNote.SHARED_WITH_YOU to "" else SourceNote.SHARED_BY to cal.ownerName
                cal.readOnly -> SourceNote.READ_ONLY to ""
                else -> SourceNote.NONE to ""
            }
            val question = cal.mine && !cal.subscribed && !cal.birthdays && !cal.deckBoard && autoHidden == AutoHiddenReason.NONE
            sources += SourceEntry(
                type = SourceType.CALENDAR,
                key = cal.uri,
                name = cal.name,
                color = cal.color.ifEmpty { SourceColours.COBALT },
                note = note,
                noteArg = noteArg,
                mode = ctx.settings.calendarMode(cal, ctx.taskCopyCalendarUri).id,
                defaultMode = SourceSettings.defaultCalendarMode(cal).id,
                autoHidden = autoHidden != AutoHiddenReason.NONE,
                autoHiddenReason = autoHidden,
                personalQuestion = question
            )
            if (question) ownCalendars += OwnCalendar(cal.uri, cal.name)
        }
        val needsPersonalQuestion = input.calendar.loaded && !input.settings.personalAnswered && ownCalendars.isNotEmpty()

        if (input.gantt.loaded && !input.gantt.unavailable) {
            for (project in input.gantt.projects.objects()) {
                val relation = GanttMapper.relationOf(ctx, input.gantt, project)
                val mode = ctx.settings.projectMode(relation)
                val (note, noteArg) = when {
                    relation.owned || (relation.runByRealShare && !relation.seenOnlyAsServerAdmin) -> SourceNote.YOU_RUN_PROJECT to ""
                    relation.seenOnlyAsServerAdmin -> SourceNote.SERVER_ADMIN_ONLY to ""
                    relation.ownerName.isNotEmpty() -> SourceNote.SHARED_BY to relation.ownerName
                    else -> SourceNote.NONE to ""
                }
                if (mode == WorkMode.DO_OVERSEE) anyOversee = true
                sources += SourceEntry(
                    type = SourceType.PROJECT,
                    key = relation.id.toString(),
                    name = relation.title,
                    color = SourceColours.display(relation.color),
                    note = note,
                    noteArg = noteArg,
                    mode = mode.id,
                    defaultMode = relation.defaultMode().id
                )
            }
        }
        if (input.deck.loaded && !input.deck.unavailable) {
            for (board in input.deck.boards.objects()) {
                val relation = DeckMapper.relationOf(ctx, board)
                val mode = ctx.settings.boardMode(relation)
                if (mode == WorkMode.DO_OVERSEE) anyOversee = true
                sources += SourceEntry(
                    type = SourceType.BOARD,
                    key = relation.id.toString(),
                    name = relation.title,
                    color = SourceColours.display(relation.color),
                    note = if (relation.owned || relation.canManage) SourceNote.YOU_RUN_BOARD else SourceNote.NONE,
                    mode = mode.id,
                    defaultMode = relation.defaultMode().id
                )
            }
        }

        return BuildOutput(
            items = items,
            sources = sources,
            ownCalendars = ownCalendars,
            needsPersonalQuestion = needsPersonalQuestion,
            anyOversee = anyOversee,
            ganttNotLinked = input.gantt.loaded && !input.gantt.unavailable && !input.gantt.hasLinkedResource,
            ganttUnavailable = input.gantt.loaded && input.gantt.unavailable,
            boardUnavailable = input.deck.loaded && input.deck.unavailable,
            ganttReadOnlyByGuard = input.gantt.loaded && !input.gantt.unavailable && !input.gantt.contractOk,
            ganttContractProblem = input.gantt.contractProblem,
            boardReadOnlyByGuard = input.deck.loaded && !input.deck.unavailable && !DeckClient.versionIsTested(input.deck.version),
            myAgents = ctx.registry.ofPerson(me),
            taskCopyCalendarUri = ctx.taskCopyCalendarUri,
            workingHours = workingHours(input),
            holidays = holidays(input),
            // "+" offers only the person's own calendars and lists that show on the agenda (Personal or Work): an item made in a team
            // calendar would have no organiser or attendee and vanish at the next refresh, and private content would land where the
            // team sees it (design 6.4); a Hidden calendar is never read back.
            writableCalendars = input.calendar.calendars.filter {
                it.mine && !it.ignored && !it.readOnly && !it.subscribed && !it.birthdays && !it.deckBoard &&
                    SourceSettings.autoHiddenReason(it, ctx.taskCopyCalendarUri) == AutoHiddenReason.NONE &&
                    ctx.settings.calendarMode(it, ctx.taskCopyCalendarUri).let { mode -> mode == CalendarMode.PERSONAL || mode == CalendarMode.WORK }
            }.map {
                WritableCalendar(
                    uri = it.uri,
                    href = it.href,
                    name = it.name,
                    color = it.color.ifEmpty { SourceColours.COBALT },
                    events = it.hasEvents,
                    todos = it.hasTodos,
                    isDefault = it.uri == input.calendar.defaultCalendarUri,
                    mode = ctx.settings.calendarMode(it, ctx.taskCopyCalendarUri)
                )
            }
        )
    }
}
