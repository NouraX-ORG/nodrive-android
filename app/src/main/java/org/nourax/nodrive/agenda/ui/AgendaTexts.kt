/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui

import android.content.Context
import android.text.format.DateFormat
import com.owncloud.android.R
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.GroupKind
import org.nourax.nodrive.agenda.logic.TimeKind
import org.nourax.nodrive.agenda.logic.TimeLabel
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.AutoHiddenReason
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemFlag
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.SourceEntry
import org.nourax.nodrive.agenda.model.SourceNote
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Turns the structured rows and flags into the words of the phone's language (the logic layer has no texts). */
class AgendaTexts(private val context: Context) {
    private val locale: Locale get() = context.resources.configuration.locales[0]

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun title(item: AgendaItem): String {
        if (item.problemOnly || item.title.isEmpty()) {
            item.problems.firstOrNull()?.let { return problem(it, item) }
        }
        return item.title.ifEmpty { s(R.string.nodrive_agenda_no_title) }
    }

    /** The name of an agent for a row: "Self", the registry name, "AG-0001 (unknown agent)", "Name (retired)" or "Agent of Ali". */
    fun agent(ref: AgentRef): String = when {
        ref.isSelf -> s(R.string.nodrive_agenda_self)
        ref.otherOrganisation -> if (ref.owner.isEmpty()) s(R.string.nodrive_agenda_agent_of_someone) else s(R.string.nodrive_agenda_agent_of, ref.owner)
        ref.unknown -> s(R.string.nodrive_agenda_agent_unknown, ref.id)
        ref.retired -> s(R.string.nodrive_agenda_agent_retired, ref.name)
        else -> ref.name
    }

    /** One problem sentence (design 4.4, 6.3). For a row that only reports a problem, [item] supplies the title. */
    @Suppress("CyclomaticComplexMethod")
    fun problem(flag: ProblemFlag, item: AgendaItem? = null): String {
        val a = flag.args
        return when (flag.kind) {
            ProblemKind.LATE -> context.resources.getQuantityString(R.plurals.nodrive_agenda_days_late, a.firstOrNull()?.toIntOrNull() ?: 0, a.firstOrNull()?.toIntOrNull() ?: 0)
            ProblemKind.DUE_TODAY -> s(R.string.nodrive_agenda_group_due_today)
            ProblemKind.BLOCKED -> s(R.string.nodrive_agenda_blocked_by, a.getOrElse(0) { "" })
            ProblemKind.OVERBOOKED -> s(R.string.nodrive_agenda_overbooked, a.getOrElse(0) { "" })
            ProblemKind.NOT_ASSIGNED -> s(R.string.nodrive_agenda_not_assigned)
            ProblemKind.AGENT_NO_PERSON -> s(R.string.nodrive_agenda_agent_no_person)
            ProblemKind.AGENT_WITHOUT_YOU -> s(R.string.nodrive_agenda_agent_without_you, a.getOrElse(0) { "" }, a.getOrElse(1) { item?.title.orEmpty() })
            ProblemKind.AGENT_WRONG_PERSON -> {
                val agent = a.getOrElse(0) { "" }.ifEmpty { s(R.string.nodrive_agenda_agent_other_org) }
                val owner = a.getOrElse(1) { "" }
                val other = a.getOrElse(2) { "" }
                if (owner.isEmpty()) s(R.string.nodrive_agenda_agent_wrong_person_unknown, agent, other) else s(R.string.nodrive_agenda_agent_wrong_person, agent, owner, other)
            }
            ProblemKind.TWO_AGENTS -> s(R.string.nodrive_agenda_two_agents, a.getOrElse(0) { "" })
            ProblemKind.UNKNOWN_AGENT -> s(R.string.nodrive_agenda_agent_unknown, a.getOrElse(0) { "" })
            ProblemKind.RETIRED_AGENT -> s(R.string.nodrive_agenda_agent_retired, a.getOrElse(0) { "" })
            ProblemKind.NOT_LINKED -> s(R.string.nodrive_agenda_not_linked_account)
            ProblemKind.ROLE_ONLY -> s(R.string.nodrive_agenda_role_only)
            ProblemKind.NOT_SHARED -> {
                val count = a.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                context.resources.getQuantityString(R.plurals.nodrive_agenda_not_shared, count, a.getOrElse(0) { "" }, count)
            }
        }
    }

    fun group(kind: GroupKind): String = when (kind) {
        GroupKind.LATE -> s(R.string.nodrive_agenda_group_late)
        GroupKind.DUE -> s(R.string.nodrive_agenda_group_due)
        GroupKind.DUE_TODAY -> s(R.string.nodrive_agenda_group_due_today)
        GroupKind.IN_PROGRESS -> s(R.string.nodrive_agenda_group_in_progress)
        GroupKind.AGENT_PROBLEMS -> s(R.string.nodrive_agenda_group_agent_problems)
        GroupKind.MY_TEAM -> s(R.string.nodrive_agenda_group_my_team)
        GroupKind.NO_DATE -> s(R.string.nodrive_agenda_group_no_date)
    }

    fun kind(kind: ItemKind): String = when (kind) {
        ItemKind.EVENT -> s(R.string.nodrive_agenda_kind_event)
        ItemKind.TODO -> s(R.string.nodrive_agenda_kind_todo)
        ItemKind.GANTT_TASK -> s(R.string.nodrive_agenda_kind_gantt_task)
        ItemKind.MILESTONE -> s(R.string.nodrive_agenda_kind_milestone)
        ItemKind.CARD -> s(R.string.nodrive_agenda_kind_card)
    }

    fun readOnlyReason(reason: ReadOnlyReason): String = when (reason) {
        ReadOnlyReason.NONE -> ""
        ReadOnlyReason.SUBSCRIPTION -> s(R.string.nodrive_agenda_ro_subscription)
        ReadOnlyReason.CALENDAR_READ_ONLY -> s(R.string.nodrive_agenda_ro_calendar)
        ReadOnlyReason.VIEWER_PROJECT -> s(R.string.nodrive_agenda_ro_viewer)
        ReadOnlyReason.BOARD_VIEW_ONLY -> s(R.string.nodrive_agenda_ro_board)
        ReadOnlyReason.MILESTONE -> s(R.string.nodrive_agenda_ro_milestone)
        ReadOnlyReason.PROBLEM_ROW -> s(R.string.nodrive_agenda_ro_problem)
    }

    // ---- dates and times ------------------------------------------------------------------------------------------

    fun time(value: LocalTime): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(value)

    fun shortDate(value: LocalDate): String = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEE d MMM"), locale).format(value)

    fun longDate(value: LocalDate): String = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEE d MMMM"), locale).format(value)

    fun fullDate(value: LocalDate): String = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEE d MMMM yyyy"), locale).format(value)

    fun monthTitle(value: LocalDate): String = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMM yyyy"), locale).format(value)

    fun weekTitle(first: LocalDate, last: LocalDate): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "d MMM"), locale).format(first) + " - " +
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "d MMM yyyy"), locale).format(last)

    /** "10:42" for a moment (epoch milliseconds) in [zone]. */
    fun clock(ms: Long, zone: ZoneId): String = time(Instant.ofEpochMilli(ms).atZone(zone).toLocalTime())

    fun time(label: TimeLabel): String = when (label.kind) {
        TimeKind.NONE -> ""
        TimeKind.ALL_DAY -> s(R.string.nodrive_agenda_time_all_day)
        TimeKind.ALL_DAY_UNTIL -> s(R.string.nodrive_agenda_time_all_day_until, label.date?.let { shortDate(it) }.orEmpty())
        TimeKind.RANGE -> s(R.string.nodrive_agenda_time_range, label.time1?.let { time(it) }.orEmpty(), label.time2?.let { time(it) }.orEmpty())
        TimeKind.FROM -> s(R.string.nodrive_agenda_time_from, label.time1?.let { time(it) }.orEmpty())
        TimeKind.UNTIL -> s(R.string.nodrive_agenda_time_until, label.time1?.let { time(it) }.orEmpty())
        TimeKind.SINGLE -> label.time1?.let { time(it) }.orEmpty()
        TimeKind.DUE_AT -> s(R.string.nodrive_agenda_time_due, label.time1?.let { time(it) }.orEmpty())
        TimeKind.DUE_ON -> s(R.string.nodrive_agenda_time_due, label.date?.let { shortDate(it) }.orEmpty())
    }

    // ---- the Sources screen ------------------------------------------------------------------------------------------

    fun calendarMode(mode: CalendarMode): String = when (mode) {
        CalendarMode.PERSONAL -> s(R.string.nodrive_agenda_mode_personal)
        CalendarMode.WORK -> s(R.string.nodrive_agenda_mode_work)
        CalendarMode.TEAM -> s(R.string.nodrive_agenda_mode_team)
        CalendarMode.INFO -> s(R.string.nodrive_agenda_mode_info)
        CalendarMode.HIDDEN -> s(R.string.nodrive_agenda_mode_hidden)
    }

    fun workMode(mode: WorkMode): String = when (mode) {
        WorkMode.DO_OVERSEE -> s(R.string.nodrive_agenda_mode_do_oversee)
        WorkMode.DO -> s(R.string.nodrive_agenda_mode_do)
        WorkMode.HIDDEN -> s(R.string.nodrive_agenda_mode_hidden)
    }

    fun note(entry: SourceEntry): String = when (entry.note) {
        SourceNote.NONE -> ""
        SourceNote.SUBSCRIPTION -> s(R.string.nodrive_agenda_note_subscription)
        SourceNote.BIRTHDAYS -> s(R.string.nodrive_agenda_note_birthdays)
        SourceNote.SHARED_WITH_YOU -> s(R.string.nodrive_agenda_note_shared_with_you)
        SourceNote.SHARED_BY -> s(R.string.nodrive_agenda_note_shared_by, entry.noteArg)
        SourceNote.READ_ONLY -> s(R.string.nodrive_agenda_note_read_only)
        SourceNote.YOU_RUN_PROJECT -> s(R.string.nodrive_agenda_note_you_run_project)
        SourceNote.SERVER_ADMIN_ONLY -> s(R.string.nodrive_agenda_note_server_admin)
        SourceNote.YOU_RUN_BOARD -> s(R.string.nodrive_agenda_note_you_run_board)
    }

    fun autoHidden(reason: AutoHiddenReason): String = when (reason) {
        AutoHiddenReason.NONE -> ""
        AutoHiddenReason.BOARD_CALENDAR -> s(R.string.nodrive_agenda_reason_board)
        AutoHiddenReason.TASK_COPY -> s(R.string.nodrive_agenda_reason_copy)
    }

    fun agentStatus(status: String): String = when (status) {
        "paused" -> s(R.string.nodrive_agenda_agent_status_paused)
        "retired" -> s(R.string.nodrive_agenda_agent_status_retired)
        else -> s(R.string.nodrive_agenda_agent_status_active)
    }
}
