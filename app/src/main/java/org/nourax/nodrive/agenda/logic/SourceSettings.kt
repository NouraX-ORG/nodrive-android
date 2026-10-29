/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.dav.CalendarInfo
import org.nourax.nodrive.agenda.model.AutoHiddenReason
import org.nourax.nodrive.agenda.ocs.bool
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.obj
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import java.time.LocalTime

enum class CalendarMode(val id: String) {
    PERSONAL("personal"),
    WORK("work"),
    TEAM("team"),
    INFO("info"),
    HIDDEN("hidden");

    companion object {
        fun of(id: String, fallback: CalendarMode): CalendarMode = entries.firstOrNull { it.id == id } ?: fallback
    }
}

/** The setting of a project or a board. */
enum class WorkMode(val id: String) {
    DO_OVERSEE("doOversee"),
    DO("do"),
    HIDDEN("hidden");

    companion object {
        fun of(id: String, fallback: WorkMode): WorkMode = entries.firstOrNull { it.id == id } ?: fallback
    }
}

/** Working days (bit 0 = Monday ... bit 6 = Sunday, like NoGantt's `work_days`) and hours. Default Monday to Friday, 09:00-17:00. */
data class WorkingHours(
    val daysMask: Int = 31,
    val from: LocalTime = LocalTime.of(9, 0),
    val to: LocalTime = LocalTime.of(17, 0)
)

/** How a NoGantt project relates to the person (design 6.1). */
data class ProjectRelation(
    val id: Int,
    val title: String,
    val color: String,
    val ownerId: String,
    val ownerName: String,
    val permission: String,
    val owned: Boolean,
    val hasMyTasks: Boolean,
    val serverAdmin: Boolean,
    val sharesKnown: Boolean,
    /** The person runs the project through a real share (or owns it). */
    val runByRealShare: Boolean,
    val seenOnlyAsServerAdmin: Boolean
) {
    fun defaultMode(): WorkMode {
        if (runByRealShare) return WorkMode.DO_OVERSEE
        // Editor or viewer with tasks of mine: Do. Without: Hidden. Seen only as a server admin: Hidden.
        return if (hasMyTasks) WorkMode.DO else WorkMode.HIDDEN
    }

    companion object {
        fun of(
            project: JsonObject,
            me: String,
            serverAdmin: Boolean,
            myGroups: List<String>,
            myProjectIds: Set<Int>,
            shares: JsonArray,
            sharesKnown: Boolean
        ): ProjectRelation {
            val id = intOf(project.get("id"))
            val ownerId = project.str("owner_id")
            val permission = project.str("permission")
            val owned = ownerId.equals(me, ignoreCase = true)
            var runByRealShare = false
            var seenOnlyAsServerAdmin = false
            if (owned) {
                runByRealShare = true
            } else if (permission == "admin") {
                if (!serverAdmin) {
                    runByRealShare = true // the server gives "admin" through a real share
                } else {
                    // A server admin sees every project as admin: only a share to me (or to a group of mine) counts.
                    for (share in shares.objects()) {
                        if (share.str("permission") != "admin") continue
                        val type = share.str("principal_type")
                        val principal = share.str("principal_id")
                        if ((type == "user" && principal.equals(me, ignoreCase = true)) || (type == "group" && principal in myGroups)) {
                            runByRealShare = true
                            break
                        }
                    }
                    seenOnlyAsServerAdmin = !runByRealShare
                }
            }
            return ProjectRelation(
                id = id,
                title = project.str("title"),
                color = project.str("color"),
                ownerId = ownerId,
                ownerName = project.str("owner_display_name"),
                permission = permission,
                owned = owned,
                hasMyTasks = id in myProjectIds,
                serverAdmin = serverAdmin,
                sharesKnown = sharesKnown,
                runByRealShare = runByRealShare,
                seenOnlyAsServerAdmin = seenOnlyAsServerAdmin
            )
        }
    }
}

/** How a Deck board relates to the person (design 6.1). */
data class BoardRelation(val id: Int, val title: String, val color: String, val owned: Boolean, val canManage: Boolean) {
    fun defaultMode(): WorkMode = if (owned || canManage) WorkMode.DO_OVERSEE else WorkMode.DO

    companion object {
        fun of(board: JsonObject, me: String): BoardRelation = BoardRelation(
            id = intOf(board.get("id")),
            title = board.str("title"),
            color = board.str("color"),
            owned = board.obj("owner").str("uid").equals(me, ignoreCase = true),
            canManage = board.obj("permissions").bool("PERMISSION_MANAGE")
        )
    }
}

/**
 * The person's settings for the sources (design 6.1), per account. Anything not set has its default. Immutable: a
 * change gives a new object.
 */
data class SourceSettings(
    val calendars: Map<String, CalendarMode> = emptyMap(),
    val projects: Map<Int, WorkMode> = emptyMap(),
    val boards: Map<Int, WorkMode> = emptyMap(),
    val personalAnswered: Boolean = false,
    val workingHours: WorkingHours? = null,
    val lastView: String = "day",
    val lastCategory: String = "all",
    val chipMe: Boolean = true,
    val chipAgents: Boolean = true,
    val chipTeam: Boolean = true,
    val showAllTeam: Boolean = false,
    /** NoAgenda was opened at least once for this account: only then the background refresh runs. */
    val everOpened: Boolean = false
) {
    fun calendarMode(calendar: CalendarInfo, taskCopyCalendarUri: String): CalendarMode {
        if (autoHiddenReason(calendar, taskCopyCalendarUri) != AutoHiddenReason.NONE) return CalendarMode.HIDDEN
        return calendars[calendar.uri] ?: defaultCalendarMode(calendar)
    }

    fun projectMode(relation: ProjectRelation): WorkMode = projects[relation.id] ?: relation.defaultMode()

    fun boardMode(relation: BoardRelation): WorkMode = boards[relation.id] ?: relation.defaultMode()

    fun withCalendar(uri: String, mode: CalendarMode) = copy(calendars = calendars + (uri to mode))

    fun withProject(id: Int, mode: WorkMode) = copy(projects = projects + (id to mode))

    fun withBoard(id: Int, mode: WorkMode) = copy(boards = boards + (id to mode))

    /** The answer to "Which of your calendars are personal?". Skip keeps all as Personal and never asks again. */
    fun answerPersonalQuestion(personalUris: Set<String>, ownUris: List<String>, skipped: Boolean): SourceSettings {
        if (skipped) return copy(personalAnswered = true)
        val updated = calendars.toMutableMap()
        for (uri in ownUris) updated[uri] = if (uri in personalUris) CalendarMode.PERSONAL else CalendarMode.WORK
        return copy(personalAnswered = true, calendars = updated)
    }

    fun toJson(): JsonObject {
        val o = JsonObject()
        val cal = JsonObject()
        calendars.forEach { (k, v) -> cal.addProperty(k, v.id) }
        o.add("calendars", cal)
        val p = JsonObject()
        projects.forEach { (k, v) -> p.addProperty(k.toString(), v.id) }
        o.add("projects", p)
        val b = JsonObject()
        boards.forEach { (k, v) -> b.addProperty(k.toString(), v.id) }
        o.add("boards", b)
        o.addProperty("personalAnswered", personalAnswered)
        workingHours?.let {
            val h = JsonObject()
            h.addProperty("days", it.daysMask)
            h.addProperty("from", it.from.toString())
            h.addProperty("to", it.to.toString())
            o.add("workingHours", h)
        }
        o.addProperty("view", lastView)
        o.addProperty("category", lastCategory)
        o.addProperty("chipMe", chipMe)
        o.addProperty("chipAgents", chipAgents)
        o.addProperty("chipTeam", chipTeam)
        o.addProperty("showAllTeam", showAllTeam)
        o.addProperty("everOpened", everOpened)
        return o
    }

    companion object {
        fun defaultCalendarMode(c: CalendarInfo): CalendarMode = when {
            c.deckBoard -> CalendarMode.HIDDEN
            c.subscribed || c.birthdays -> CalendarMode.INFO
            !c.mine -> CalendarMode.TEAM
            else -> CalendarMode.PERSONAL
        }

        fun autoHiddenReason(c: CalendarInfo, taskCopyCalendarUri: String): AutoHiddenReason = when {
            c.deckBoard -> AutoHiddenReason.BOARD_CALENDAR
            taskCopyCalendarUri.isNotEmpty() && c.uri == taskCopyCalendarUri -> AutoHiddenReason.TASK_COPY
            else -> AutoHiddenReason.NONE
        }

        fun fromJson(o: JsonObject): SourceSettings {
            val calendars = o.obj("calendars").entrySet().associate { (k, v) ->
                k to CalendarMode.of(if (v.isJsonPrimitive) v.asString else "", CalendarMode.PERSONAL)
            }
            val projects = o.obj("projects").entrySet().mapNotNull { (k, v) ->
                k.toIntOrNull()?.let { it to WorkMode.of(if (v.isJsonPrimitive) v.asString else "", WorkMode.HIDDEN) }
            }.toMap()
            val boards = o.obj("boards").entrySet().mapNotNull { (k, v) ->
                k.toIntOrNull()?.let { it to WorkMode.of(if (v.isJsonPrimitive) v.asString else "", WorkMode.HIDDEN) }
            }.toMap()
            val hours = if (o.has("workingHours")) {
                val h = o.obj("workingHours")
                val from = runCatching { LocalTime.parse(h.str("from")) }.getOrNull()
                val to = runCatching { LocalTime.parse(h.str("to")) }.getOrNull()
                if (from != null && to != null && from.isBefore(to)) WorkingHours(h.int("days", 31), from, to) else null
            } else {
                null
            }
            fun flag(key: String, default: Boolean) = if (o.has(key)) o.bool(key, default) else default
            return SourceSettings(
                calendars = calendars,
                projects = projects,
                boards = boards,
                personalAnswered = o.bool("personalAnswered"),
                workingHours = hours,
                lastView = o.str("view").ifEmpty { "day" },
                lastCategory = o.str("category").ifEmpty { "all" },
                chipMe = flag("chipMe", true),
                chipAgents = flag("chipAgents", true),
                chipTeam = flag("chipTeam", true),
                showAllTeam = flag("showAllTeam", false),
                everOpened = o.bool("everOpened")
            )
        }
    }
}
