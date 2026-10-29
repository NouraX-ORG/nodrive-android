/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.nourax.nodrive.agenda.logic.BuildOutput
import org.nourax.nodrive.agenda.logic.OwnCalendar
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Audience
import org.nourax.nodrive.agenda.model.AutoHiddenReason
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemFlag
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.SourceEntry
import org.nourax.nodrive.agenda.model.SourceNote
import org.nourax.nodrive.agenda.model.SourceState
import org.nourax.nodrive.agenda.model.SourceStatus
import org.nourax.nodrive.agenda.model.SourceType
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.RegistryStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Canned data for the screen tests that need no server: the same picture of one person's day, on a fixed day. */
object CannedAgenda {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 28)
    val NOW: Instant = Instant.parse("2026-10-28T08:42:00Z")
    val UTC: ZoneId = ZoneId.of("UTC")
    private const val BLUE = "#2563EB"
    private const val PURPLE = "#795AAF"
    private const val GREEN = "#16A34A"
    private val writer = AgentRef(isSelf = false, id = "AG-0001", name = "Writer-Bot", status = "active")
    private val designer = AgentRef(isSelf = false, id = "AG-0002", name = "Design-Bot", status = "active")

    private fun at(hour: Int, minute: Int = 0): Instant = TODAY.atTime(hour, minute).atZone(UTC).toInstant()

    private fun event(key: String, title: String, from: Int, to: Int, source: String, color: String, category: Category, agent: AgentRef = AgentRef.SELF) = AgendaItem(
        key = key,
        kind = ItemKind.EVENT,
        home = Home.CALENDAR,
        title = title,
        timed = true,
        start = at(from),
        end = at(to),
        sourceKey = "cal:$source",
        sourceName = source,
        sourceColor = color,
        category = category,
        agent = agent,
        workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT,
        calendarUri = source.lowercase(),
        objectUri = "$key.ics"
    )

    val items: List<AgendaItem> = listOf(
        event("cal:work/call.ics", "Client call", 10, 11, "Work", BLUE, Category.WORK),
        event("cal:personal/dentist.ics", "Dentist", 10, 11, "Personal", PURPLE, Category.PERSONAL),
        event("cal:work/review.ics", "Writer-Bot review", 14, 15, "Work", BLUE, Category.WORK, writer),
        AgendaItem(
            key = "gantt:task:101", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Prepare client demo",
            startDate = TODAY.minusDays(1), endDate = TODAY.plusDays(1), due = TODAY.plusDays(1),
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, taskId = 101, percentDone = 10
        ),
        AgendaItem(
            key = "gantt:task:102", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Write homepage copy",
            startDate = TODAY.minusDays(2), endDate = TODAY.plusDays(2), due = TODAY.plusDays(2),
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, taskId = 102,
            agent = writer, workKind = WorkKind.MY_AGENT, boardBadge = true, percentDone = 40
        ),
        AgendaItem(
            key = "cal:personal/bill.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Late bill",
            due = TODAY.minusDays(2), endDate = TODAY.minusDays(2), late = true, lateDays = 2,
            sourceKey = "cal:Personal", sourceName = "Personal", sourceColor = PURPLE, category = Category.PERSONAL,
            calendarUri = "personal", objectUri = "bill.ics"
        ),
        AgendaItem(
            key = "deck:card:503", kind = ItemKind.CARD, home = Home.BOARD, title = "Pay invoice",
            due = TODAY, endDate = TODAY, dueToday = true, sourceKey = "board:10", sourceName = "Team board",
            sourceColor = GREEN, boardId = 10, cardId = 503
        ),
        AgendaItem(
            key = "cal:personal/someday.ics", kind = ItemKind.TODO, home = Home.TASKS, title = "Someday",
            sourceKey = "cal:Personal", sourceName = "Personal", sourceColor = PURPLE, category = Category.PERSONAL,
            calendarUri = "personal", objectUri = "someday.ics"
        ),
        AgendaItem(
            key = "gantt:noperson:106:AG-0001", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Orphan writing",
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, taskId = 106, problemOnly = true,
            workKind = WorkKind.MY_AGENT, agent = writer, readOnly = true,
            problems = listOf(ProblemFlag(ProblemKind.AGENT_WITHOUT_YOU, Audience.PERSON, listOf("Writer-Bot", "Orphan writing")))
        )
    )

    val team: List<AgendaItem> = listOf(
        AgendaItem(
            key = "gantt:task:103:sara", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Banner design",
            startDate = TODAY.minusDays(8), endDate = TODAY.minusDays(2), due = TODAY.minusDays(2), late = true, lateDays = 2,
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, taskId = 103,
            workKind = WorkKind.TEAM, personName = "Sara", personUid = "sara", agent = designer,
            problems = listOf(ProblemFlag(ProblemKind.LATE, Audience.MANAGER, listOf("2")))
        ),
        AgendaItem(
            key = "gantt:task:104:ali", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "Publish banners",
            startDate = TODAY.minusDays(1), endDate = TODAY.plusDays(2), due = TODAY.plusDays(2), blocked = true,
            sourceKey = "gantt:1", sourceName = "Website", sourceColor = BLUE, projectId = 1, taskId = 104,
            workKind = WorkKind.TEAM, personName = "Ali", personUid = "ali",
            problems = listOf(ProblemFlag(ProblemKind.BLOCKED, Audience.MANAGER, listOf("Banner design")))
        ),
        AgendaItem(
            key = "gantt:notshared:2:sara", kind = ItemKind.GANTT_TASK, home = Home.GANTT, title = "",
            sourceKey = "gantt:2", sourceName = "Internal", sourceColor = "#E11D48", workKind = WorkKind.TEAM, personName = "Sara",
            personUid = "sara", projectId = 2, problems = listOf(ProblemFlag(ProblemKind.NOT_SHARED, Audience.MANAGER, listOf("Sara", "1")))
        )
    )

    private fun sources(): List<SourceEntry> = listOf(
        SourceEntry(SourceType.CALENDAR, "personal", "Personal", PURPLE, mode = "personal", defaultMode = "personal", personalQuestion = true),
        SourceEntry(SourceType.CALENDAR, "work", "Work", BLUE, mode = "work", defaultMode = "personal", personalQuestion = true),
        SourceEntry(SourceType.CALENDAR, "team", "Team", GREEN, SourceNote.SHARED_BY, "Mara", mode = "team", defaultMode = "team"),
        SourceEntry(SourceType.CALENDAR, "holidays", "Local holidays", "#F59E0B", SourceNote.SUBSCRIPTION, mode = "info", defaultMode = "info"),
        SourceEntry(
            SourceType.CALENDAR, "app-generated--deck--board-10", "Deck: Team board", BLUE, mode = "hidden", defaultMode = "hidden",
            autoHidden = true, autoHiddenReason = AutoHiddenReason.BOARD_CALENDAR
        ),
        SourceEntry(SourceType.PROJECT, "1", "Website", BLUE, SourceNote.SHARED_BY, "Mara", mode = "do", defaultMode = "do"),
        SourceEntry(SourceType.PROJECT, "2", "Internal", "#E11D48", mode = "hidden", defaultMode = "hidden"),
        SourceEntry(SourceType.BOARD, "10", "Team board", GREEN, mode = "do", defaultMode = "do")
    )

    private fun agentsOfMine(): List<Agent> = listOf(
        Agent("AG-0001", "Writer-Bot", "ali", "active", "demo-org"),
        Agent("AG-0003", "Research-Bot", "ali", "paused", "demo-org")
    )

    private fun fresh(key: String) = SourceStatus(key, SourceState.FRESH, NOW.toEpochMilli())

    private fun build(
        items: List<AgendaItem>,
        oversee: Boolean = false,
        question: Boolean = false,
        notLinked: Boolean = false
    ) = BuildOutput(
        items = items,
        sources = sources(),
        ownCalendars = listOf(OwnCalendar("personal", "Personal"), OwnCalendar("work", "Work")),
        needsPersonalQuestion = question,
        anyOversee = oversee,
        ganttNotLinked = notLinked,
        ganttUnavailable = false,
        boardUnavailable = false,
        ganttReadOnlyByGuard = false,
        ganttContractProblem = "",
        boardReadOnlyByGuard = false,
        myAgents = agentsOfMine(),
        taskCopyCalendarUri = "",
        workingHours = WorkingHours()
    )

    private fun snapshot(
        build: BuildOutput,
        statuses: List<SourceStatus> = listOf("calendar", "gantt", "board", "registry", "user").map { fresh(it) },
        registry: RegistryStatus = RegistryStatus.OK
    ) = AgendaSnapshot(
        build = build,
        statuses = statuses.associateBy { it.key },
        registryStatus = registry,
        registrySkipped = 0,
        settings = SourceSettings(personalAnswered = !build.needsPersonalQuestion),
        today = TODAY,
        zone = UTC,
        savedAtMs = NOW.toEpochMilli()
    )

    /** Ali's day. */
    fun day(): AgendaSnapshot = snapshot(build(items))

    /** The manager's day: a few team problems. */
    fun myTeam(): AgendaSnapshot = snapshot(build(items + team, oversee = true))

    /** The one-time question is open. */
    fun question(): AgendaSnapshot = snapshot(build(items, question = true))

    /** Not linked to a planning person in NoGantt, and nothing else to show. */
    fun notLinked(): AgendaSnapshot = snapshot(
        build(emptyList(), notLinked = true),
        statuses = listOf(fresh("calendar"), SourceStatus("gantt", SourceState.NOT_LINKED, NOW.toEpochMilli()), fresh("board"), fresh("registry"), fresh("user"))
    )

    /** Every source failed with no answer at all: the saved copy is shown with the time. */
    fun offline(): AgendaSnapshot = snapshot(
        build(items),
        statuses = listOf("calendar", "gantt", "board", "registry", "user").map { SourceStatus(it, SourceState.CACHED, NOW.toEpochMilli(), httpStatus = 0) }
    )

    /** NoGantt does not answer but the others do. */
    fun ganttDown(): AgendaSnapshot = snapshot(
        build(items),
        statuses = listOf(fresh("calendar"), SourceStatus("gantt", SourceState.CACHED, NOW.toEpochMilli(), httpStatus = 502), fresh("board"), fresh("registry"), fresh("user"))
    )

    /** No agent list in the person's NoDrive. */
    fun registryMissing(): AgendaSnapshot = snapshot(build(items).copy(myAgents = emptyList()), registry = RegistryStatus.MISSING)
}

/** An engine that answers with canned data; a setting that is changed is kept, and the question closes when it is answered. */
class CannedEngine(private var current: AgendaSnapshot) : AgendaEngine {
    override fun snapshot(): AgendaSnapshot = current

    override fun refresh(manual: Boolean): AgendaSnapshot = current

    override fun updateSettings(change: (SourceSettings) -> SourceSettings): AgendaSnapshot {
        val settings = change(current.settings)
        current = current.copy(
            settings = settings,
            build = if (settings.personalAnswered) current.build.copy(needsPersonalQuestion = false) else current.build
        )
        return current
    }
}
