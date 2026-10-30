/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.dav.CalRecord
import org.nourax.nodrive.agenda.dav.CalendarSnapshot
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Audience
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemFlag
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** NoCalendar events and NoTasks to-dos -> rows (design 6.1, 6.2, 4.2). */
object CalendarMapper {
    private fun objectName(href: String): String {
        val parts = href.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return try {
            URLDecoder.decode(parts.last().replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            parts.last()
        }
    }

    /** The agent of an event or to-do: `X-NOURAX-AGENT-ID`, else the registry's calendar tag in CATEGORIES. */
    fun agentOf(ctx: BuildContext, record: CalRecord, invitation: Boolean, problems: MutableList<ProblemFlag>): AgentRef {
        // Meeting invitations are always Self (design 4.2).
        if (invitation) return AgentRef.SELF
        if (record.agentId.isNotEmpty()) {
            val agent = ctx.registry.byId(record.agentId)
            if (agent == null) {
                problems += problem(ProblemKind.UNKNOWN_AGENT, Audience.PERSON, record.agentId)
                return AgentRegistry.unknownRef(record.agentId)
            }
            if (!ctx.isMe(agent.person)) {
                // An agent of someone else on my own item is never guessed to be mine.
                problems += problem(ProblemKind.UNKNOWN_AGENT, Audience.PERSON, record.agentId)
                return AgentRegistry.unknownRef(record.agentId)
            }
            if (agent.isRetired) problems += problem(ProblemKind.RETIRED_AGENT, Audience.PERSON, agent.name)
            return AgentRegistry.refFor(agent)
        }
        for (category in record.categories) {
            val agent = ctx.registry.byTag(category) ?: continue
            if (ctx.isMe(agent.person)) {
                if (agent.isRetired) problems += problem(ProblemKind.RETIRED_AGENT, Audience.PERSON, agent.name)
                return AgentRegistry.refFor(agent)
            }
        }
        return AgentRef.SELF
    }

    fun map(ctx: BuildContext, snapshot: CalendarSnapshot, out: MutableList<AgendaItem>) {
        // The same UID + recurrence in several visible calendars: remember which one wins.
        val chosen = HashMap<String, Int>()
        val rows = ArrayList<AgendaItem>()
        val fromOwnCalendar = ArrayList<Boolean>()

        for (rec in snapshot.records) {
            val cal = snapshot.calendar(rec.calendarUri) ?: continue
            val mode = ctx.settings.calendarMode(cal, ctx.taskCopyCalendarUri)
            if (mode == CalendarMode.HIDDEN) continue
            if (rec.status == "CANCELLED") continue

            val objectName = objectName(rec.href)
            var key = "cal:${cal.uri}/$objectName"
            if (rec.recurrenceId.isNotEmpty()) key += "#${rec.recurrenceId}"

            var amAttendee = false
            var myPartstat = ""
            for (a in rec.attendees) {
                if (a.address in ctx.myAddresses) {
                    amAttendee = true
                    myPartstat = a.partstat
                }
            }
            val organizerIsMe = rec.organizer.isNotEmpty() && rec.organizer in ctx.myAddresses
            val invitation = !rec.isTodo && rec.organizer.isNotEmpty() && !organizerIsMe && amAttendee
            val info = mode == CalendarMode.INFO

            var item = AgendaItem(
                key = key,
                kind = if (rec.isTodo) ItemKind.TODO else ItemKind.EVENT,
                home = if (rec.isTodo) Home.TASKS else Home.CALENDAR,
                title = rec.summary,
                description = rec.description,
                location = rec.location,
                sourceKey = "cal:${cal.uri}",
                sourceName = cal.name,
                sourceColor = cal.color.ifEmpty { SourceColours.COBALT },
                category = when (mode) {
                    CalendarMode.PERSONAL -> Category.PERSONAL
                    CalendarMode.INFO -> Category.INFO
                    else -> Category.WORK
                },
                info = info,
                calendarUri = cal.uri,
                calendarHref = cal.href,
                objectUri = objectName,
                objectHref = rec.href,
                uid = rec.uid,
                recurrenceId = rec.recurrenceId,
                version = rec.etag,
                repeating = rec.repeating,
                timeApproximated = rec.timeApproximated,
                readOnly = cal.readOnly,
                readOnlyReason = when {
                    !cal.readOnly -> ReadOnlyReason.NONE
                    cal.subscribed -> ReadOnlyReason.SUBSCRIPTION
                    else -> ReadOnlyReason.CALENDAR_READ_ONLY
                },
                organizerIsMe = organizerIsMe,
                hasOrganizer = rec.organizer.isNotEmpty(),
                myPartstat = myPartstat,
                invitation = invitation
            )

            if (!rec.isTodo) {
                if (myPartstat == "DECLINED") continue // declined invitations are hidden
                if (mode == CalendarMode.TEAM && !(organizerIsMe || amAttendee)) continue // a shared team calendar: only what I organise or attend
                item = item.copy(
                    transparent = rec.transp == "TRANSPARENT" || cal.transparent || info,
                    tentative = rec.status == "TENTATIVE" || myPartstat == "TENTATIVE"
                )
                item = if (rec.allDay) {
                    val first = rec.startDate?.let { LocalDate.parse(it) }
                    val last = rec.endDate?.let { LocalDate.parse(it) } ?: first
                    item.copy(allDay = true, startDate = first, endDate = last)
                } else {
                    val start = rec.startMs?.let { Instant.ofEpochMilli(it) }
                    val end = rec.endMs?.let { Instant.ofEpochMilli(it) } ?: start
                    item.copy(timed = true, start = start, end = end)
                }
            } else {
                if (rec.completed || rec.status == "COMPLETED") continue
                // A to-do in a shared list that someone else organises and that I am not invited to is not mine.
                if (mode == CalendarMode.TEAM && !(amAttendee || organizerIsMe) && rec.organizer.isNotEmpty()) continue
                var due: LocalDate? = null
                var dueInstant: Instant? = null
                if (rec.dueDate != null) {
                    due = LocalDate.parse(rec.dueDate)
                } else if (rec.dueMs != null) {
                    dueInstant = Instant.ofEpochMilli(rec.dueMs)
                    due = dueInstant.atZone(ctx.zone).toLocalDate()
                }
                val startDate = when {
                    rec.startMs != null -> Instant.ofEpochMilli(rec.startMs).atZone(ctx.zone).toLocalDate()
                    rec.startDate != null -> LocalDate.parse(rec.startDate)
                    else -> null
                }
                val late = due != null && due.isBefore(ctx.today)
                item = item.copy(
                    percentDone = rec.percent,
                    status = rec.status,
                    due = due,
                    end = dueInstant,
                    startDate = startDate,
                    todoStartTime = rec.startMs?.let { Instant.ofEpochMilli(it).atZone(ctx.zone).toLocalTime().withSecond(0).withNano(0) },
                    endDate = due,
                    late = late,
                    dueToday = due != null && due == ctx.today,
                    lateDays = if (late && due != null) ChronoUnit.DAYS.between(due, ctx.today).toInt() else 0
                )
            }

            val problems = ArrayList<ProblemFlag>()
            val agent = agentOf(ctx, rec, item.invitation, problems)
            item = item.copy(
                agent = agent,
                workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT,
                problems = problems
            )

            // Dedupe: the invitation sits in the team calendar and in my own calendar; it is shown once.
            val dedupeKey = (rec.uid.ifEmpty { key }) + "|" + rec.recurrenceId + "|" + (if (rec.isTodo) "t" else "e")
            val own = cal.mine
            val found = chosen[dedupeKey]
            if (found != null && rec.uid.isNotEmpty()) {
                if (own && !fromOwnCalendar[found]) {
                    rows[found] = item
                    fromOwnCalendar[found] = true
                }
                continue
            }
            chosen[dedupeKey] = rows.size
            rows += item
            fromOwnCalendar += own
        }
        out += rows
    }
}
