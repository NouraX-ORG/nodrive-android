/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import androidx.annotation.Keep
import org.nourax.nodrive.agenda.model.Home
import java.time.Instant
import java.time.LocalDate

enum class CommandKind {
    /** Event or to-do: change some properties. */
    CAL_PATCH,
    CAL_CREATE,
    CAL_DELETE,

    /** NoGantt task: dates, % done, status, done. */
    GANTT_UPDATE,

    /** NoGantt task: my agent (Self or one of mine). */
    GANTT_AGENT,

    /** Card: due date, done. */
    DECK_UPDATE,

    /** Card: my agent label. */
    DECK_AGENT
}

enum class CommandState { WAITING, SENDING }

/**
 * One change that the person made, kept until the server has it (design 9, plan 2.2 and 2.3). It holds the SEMANTIC change
 * ("end = 2026-11-04", "agent = AG-0002"), never a whole copy of the item, so it can be applied again on top of a newer version.
 * Only plain strings, so Gson writes it into the offline queue as it is.
 */
@Keep
data class EditCommand(
    val id: Long = 0,
    val account: String = "",
    val createdMs: Long = 0,
    val kind: CommandKind = CommandKind.CAL_PATCH,
    /** The key of the row (`cal:...`, `gantt:task:..`, `deck:card:..`); `new:<uuid>` for a new event or to-do. */
    val itemKey: String = "",
    val home: Home = Home.CALENDAR,
    /** The words the messages use ("Dentist"). Never sent anywhere. */
    val title: String = "",
    /** Where the item is: calendarHref, objectHref, projectId, taskId, boardId, stackId, cardId. */
    val locator: Map<String, String> = emptyMap(),
    /** The version the person saw when editing: ETag, `updated_at` or `lastModified`. Empty = take the newest (apply again). */
    val baseVersion: String = "",
    /** The changed fields; the keys are in [EditKeys]. */
    val change: Map<String, String> = emptyMap(),
    val attempts: Int = 0,
    val state: CommandState = CommandState.WAITING,
    /** True after "Apply my change again": the newest version is the base. */
    val rebase: Boolean = false
) {
    fun loc(key: String): String = locator[key].orEmpty()

    fun locInt(key: String): Int = locator[key]?.toIntOrNull() ?: 0
}

/** Why a change was not sent (design 9: nothing is lost silently). */
enum class ConflictReason {
    /** The item was changed somewhere else: ETag, `updated_at` or `lastModified` differs. The other change wins. */
    CHANGED_ELSEWHERE,

    /** The item is gone. */
    GONE,

    /** The server said no (403, or a calendar that is read only now). */
    REFUSED,

    /** The server said the value is wrong (400). [EditConflict.detail] has its code. */
    INVALID,
    TOO_MANY_PEOPLE,
    ROLES_ONLY,
    WORK_WOULD_CHANGE,
    AGENT_NOT_IN_PROJECT,
    NOW_REPEATING,
    LABEL_NEEDS_MANAGE,
    CHECK_IN_HOME,
    OTHER
}

@Keep
data class EditConflict(
    val id: Long = 0,
    val account: String = "",
    val command: EditCommand = EditCommand(),
    val reason: ConflictReason = ConflictReason.CHANGED_ELSEWHERE,
    /** A short server code such as `invalid_date`; empty otherwise. Never a user value. */
    val detail: String = "",
    val serverVersion: String = "",
    val createdMs: Long = 0
)

/** The keys of [EditCommand.change] and the way dates are written in it. */
object EditKeys {
    const val SUMMARY = "summary"
    const val DESCRIPTION = "description"

    /** `D:2026-11-04` (a day) or `T:1793000000000` (a moment, epoch milliseconds); empty = none. */
    const val START = "start"
    const val END = "end"
    const val DUE = "due"

    /** `true` or `false`. */
    const val DONE = "done"
    const val PERCENT = "percent"
    const val STATUS = "status"

    /** `AG-0001`, or `SELF`. */
    const val AGENT = "agent"
    const val AGENT_NAME = "agentName"

    /** The calendar tag of the new agent ("" for Self) and, joined with US, the tags of all my agents (to take off). */
    const val AGENT_TAG = "agentTag"
    const val ALL_TAGS = "allTags"
    const val BOARD_LABEL = "boardLabel"
    const val ALL_LABELS = "allLabels"
    const val SELF = "SELF"
    const val SEP = "\u001F"

    /** New object: `event` or `todo`, and the file name in the calendar. */
    const val NEW_KIND = "newKind"
    const val OBJECT_NAME = "objectName"
    const val UID = "uid"

    /** The id of the row that was edited, for a Gantt task: ids of the registry planning people of my agents. */
    const val MY_AGENT_RESOURCES = "myAgentResources"
    const val TARGET_RESOURCE = "targetResource"

    fun day(date: LocalDate): String = "D:$date"

    fun moment(instant: Instant): String = "T:${instant.toEpochMilli()}"

    sealed interface Value {
        data class Day(val date: LocalDate) : Value

        data class Moment(val instant: Instant) : Value

        data object None : Value
    }

    fun parse(text: String?): Value? {
        if (text == null) return null
        if (text.isEmpty()) return Value.None
        return try {
            when {
                text.startsWith("D:") -> Value.Day(LocalDate.parse(text.substring(2)))
                text.startsWith("T:") -> Value.Moment(Instant.ofEpochMilli(text.substring(2).toLong()))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
