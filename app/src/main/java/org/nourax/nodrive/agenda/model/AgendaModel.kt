/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The row type of every NoAgenda view (plans/noagenda-android-plan.md 1.3). No Android types: runs in JVM tests. */
enum class ItemKind { EVENT, TODO, GANTT_TASK, MILESTONE, CARD }

enum class Home { CALENDAR, TASKS, GANTT, BOARD }

/** Design 6.0: the three kinds of work. */
enum class WorkKind { SELF, MY_AGENT, TEAM }

/** For the All / Work / Personal switch. */
enum class Category { PERSONAL, WORK, INFO }

/** The problems of design 4.4, 6.3. The arguments are the words of the sentence, never a user id. */
enum class ProblemKind {
    LATE, // args: days
    DUE_TODAY,
    BLOCKED, // args: title of the late predecessor
    OVERBOOKED, // args: person names
    NOT_ASSIGNED,
    AGENT_NO_PERSON,
    AGENT_WITHOUT_YOU, // args: agent name, item title (my agent sits on an item that is not mine)
    AGENT_WRONG_PERSON, // args: agent name, owner name (may be empty), other person name
    TWO_AGENTS, // args: person name
    UNKNOWN_AGENT, // args: id or label
    RETIRED_AGENT, // args: agent name
    NOT_LINKED,
    ROLE_ONLY,
    NOT_SHARED // args: person name, number of tasks
}

enum class Audience { PERSON, MANAGER, BOTH }

data class ProblemFlag(val kind: ProblemKind, val audience: Audience, val args: List<String> = emptyList())

enum class ReadOnlyReason {
    NONE,
    SUBSCRIPTION,
    CALENDAR_READ_ONLY,
    VIEWER_PROJECT,
    BOARD_VIEW_ONLY,

    /** NoGantt or NoBoard does not accept the sign-in: the rows stay in view, but nothing is written there (design 5). */
    SOURCE_REFUSED,
    MILESTONE,
    PROBLEM_ROW
}

/** Who does the work: Self, or an agent of the registry (design 3). */
data class AgentRef(
    val isSelf: Boolean = true,
    /** AG-0001, or the unrecognised label or tag text. */
    val id: String = "",
    /** Empty when the agent is not in the registry. */
    val name: String = "",
    val status: String = "",
    val unknown: Boolean = false,
    val retired: Boolean = false,
    val paused: Boolean = false,
    /** The name is hidden ("Agent of Ali"): the agent belongs to another organisation. */
    val otherOrganisation: Boolean = false,
    /** Display name for "Agent of %1"; empty when it is not known (never a user id). */
    val owner: String = "",
    /** The row has no person, so nobody does it by hand or by agent: no agent marker is shown (not "Self"). */
    val none: Boolean = false
) {
    companion object {
        val SELF = AgentRef()
        val NONE = AgentRef(isSelf = false, none = true)
    }
}

data class AgendaItem(
    /** cal:<calendarUri>/<objectName>[#<recurrence-id>], gantt:task:<id>, gantt:ms:<id>, deck:card:<id>. */
    val key: String,
    val kind: ItemKind,
    val home: Home,
    val title: String = "",
    val timed: Boolean = false,
    val allDay: Boolean = false,
    val start: Instant? = null,
    val end: Instant? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val due: LocalDate? = null,
    val sourceKey: String = "",
    val sourceName: String = "",
    val sourceColor: String = "",
    val category: Category = Category.WORK,
    val workKind: WorkKind = WorkKind.SELF,
    val agent: AgentRef = AgentRef.SELF,
    /** Team rows: who the work belongs to. */
    val personName: String = "",
    val personUid: String = "",
    val status: String = "",
    val done: Boolean = false,
    val percentDone: Int = -1,
    val late: Boolean = false,
    val lateDays: Int = 0,
    val dueToday: Boolean = false,
    val blocked: Boolean = false,
    val blockedBy: String = "",
    val tentative: Boolean = false,
    val transparent: Boolean = false,
    val declined: Boolean = false,
    val repeating: Boolean = false,
    val invitation: Boolean = false,
    /** Holidays, birthdays, subscriptions: shown for information, never block time. */
    val info: Boolean = false,
    val readOnly: Boolean = false,
    val readOnlyReason: ReadOnlyReason = ReadOnlyReason.NONE,
    val versionReadOnly: Boolean = false,
    /** A Gantt task that is linked to a board card. */
    val boardBadge: Boolean = false,
    val timeApproximated: Boolean = false,
    val problems: List<ProblemFlag> = emptyList(),
    /** A row that only reports a problem that concerns me. It is not my work: no date, not in "No date". */
    val problemOnly: Boolean = false,
    val description: String = "",
    val location: String = "",
    /** ETag, updated_at or lastModified (the base version of an edit in the next step). */
    val version: String = "",
    val progressMode: String = "",
    val projectId: Int = 0,
    val projectName: String = "",
    val taskId: Int = 0,
    val boardId: Int = 0,
    val stackId: Int = 0,
    val cardId: Int = 0,
    val deckCardId: Int = 0,
    val calendarUri: String = "",
    val calendarHref: String = "",
    val objectUri: String = "",
    val objectHref: String = "",
    val uid: String = "",
    val recurrenceId: String = "",
    val organizerIsMe: Boolean = false,
    /** The event names an organiser (also when my address was not matched as an attendee). */
    val hasOrganizer: Boolean = false,
    val myPartstat: String = "",
    val myWorkHours: Double = -1.0,
    /** The work (hours) that the planning person of my agent has on this Gantt task; -1 = none or not known. */
    val agentWorkHours: Double = -1.0,
    /** A card or task with real start and end times on one day (the clash rule). Events use [start] and [end]. */
    val clashStart: Instant? = null,
    val clashEnd: Instant? = null,
    /** My change is made on the phone and waits to be sent (design 9: "Waiting to send"). */
    val pending: Boolean = false
) {
    val hasDate: Boolean get() = timed || startDate != null || endDate != null || due != null

    fun firstDay(zone: ZoneId): LocalDate? = when {
        timed && start != null -> start.atZone(zone).toLocalDate()
        startDate != null -> startDate
        endDate != null -> endDate
        else -> due
    }

    fun lastDay(zone: ZoneId): LocalDate? = when {
        timed && start != null -> {
            // An event that ends exactly at midnight does not belong to the next day.
            val last = if (end != null && end.isAfter(start)) end.minusSeconds(1) else start
            last.atZone(zone).toLocalDate()
        }
        endDate != null -> endDate
        due != null -> due
        else -> startDate
    }
}

/** The state of one source after its last read. */
enum class SourceState {
    NEVER_LOADED,
    FRESH,

    /** Read from the cache at start; no attempt yet (no banner). */
    SAVED,

    /** The last attempt failed; the saved copy is shown. */
    CACHED,
    FAILED,

    /** Shown read only by the version guard. */
    READ_ONLY,

    /** The app is not on the server. */
    UNAVAILABLE,

    /** NoGantt: the person is not linked to a planning person. */
    NOT_LINKED,

    /** 401 from the server's own sign-in (CalDAV, user info). */
    NEEDS_LOGIN,

    /** NoGantt or NoBoard answered 401 or 403 while the account is fine: turns into "Open in ...". */
    REFUSED
}

data class SourceStatus(
    val key: String,
    val state: SourceState = SourceState.NEVER_LOADED,
    /** When the data on screen was read (epoch milliseconds), 0 = never. */
    val savedAtMs: Long = 0,
    val readOnlyByGuard: Boolean = false,
    val httpStatus: Int = 0
)

enum class SourceType { CALENDAR, PROJECT, BOARD }

/** A row of the Sources screen. */
data class SourceEntry(
    val type: SourceType,
    val key: String,
    val name: String,
    val color: String,
    val note: SourceNote = SourceNote.NONE,
    val noteArg: String = "",
    /** Effective setting: personal, work, team, info, hidden, doOversee, do. */
    val mode: String,
    val defaultMode: String,
    val autoHidden: Boolean = false,
    val autoHiddenReason: AutoHiddenReason = AutoHiddenReason.NONE,
    /** An own calendar (listed in the one-time question). */
    val personalQuestion: Boolean = false
)

enum class SourceNote {
    NONE,
    SUBSCRIPTION,
    BIRTHDAYS,
    SHARED_WITH_YOU,
    SHARED_BY,
    READ_ONLY,
    YOU_RUN_PROJECT,
    SERVER_ADMIN_ONLY,
    YOU_RUN_BOARD
}

enum class AutoHiddenReason { NONE, BOARD_CALENDAR, TASK_COPY }
