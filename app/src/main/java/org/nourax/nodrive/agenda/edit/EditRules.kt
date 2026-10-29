/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ReadOnlyReason
import org.nourax.nodrive.agenda.model.WorkKind

/** Why a row can not be changed here. The screen turns it into a plain sentence. */
enum class EditBlock {
    NONE,

    /** The calendar, list, project or board does not allow it (see [AgendaItem.readOnlyReason]). */
    READ_ONLY,

    /** A repeating event or to-do: changed in the signed-in Calendar or Tasks page (design 9). */
    REPEATING_EVENT,
    REPEATING_TODO,

    /** An invitation from someone else. Only the organiser changes it. */
    INVITATION,

    /** NoGantt or NoBoard is a version that was not tested: shown, never written (design 5). */
    VERSION_GUARD,

    /** Other people's work, a milestone, a row that only reports a problem, information. */
    NOT_MINE,

    /** A change of this row has not reached the server yet. */
    WAITING
}

/** What the person may do with one row (design 9). Nothing is allowed that the table of section 9 says "not in NoAgenda". */
data class Capabilities(
    val block: EditBlock,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canTickDone: Boolean,
    /** The % done of a task that gets its progress from the board is read only. */
    val percentFromBoard: Boolean,
    /** Only a row of mine can have its agent changed (and not an invitation). */
    val canChangeAgent: Boolean
)

object EditRules {
    private val NONE = Capabilities(EditBlock.NOT_MINE, canEdit = false, canDelete = false, canTickDone = false, percentFromBoard = false, canChangeAgent = false)

    fun of(item: AgendaItem): Capabilities {
        if (item.problemOnly || item.info || item.workKind == WorkKind.TEAM || item.kind == ItemKind.MILESTONE) return NONE
        if (item.versionReadOnly) return NONE.copy(block = EditBlock.VERSION_GUARD)
        if (item.pending) return NONE.copy(block = EditBlock.WAITING)
        if (item.readOnly || item.readOnlyReason != ReadOnlyReason.NONE) return NONE.copy(block = EditBlock.READ_ONLY)
        return when (item.home) {
            Home.CALENDAR -> when {
                item.repeating -> NONE.copy(block = EditBlock.REPEATING_EVENT)
                item.invitation -> NONE.copy(block = EditBlock.INVITATION)
                else -> Capabilities(EditBlock.NONE, canEdit = true, canDelete = true, canTickDone = false, percentFromBoard = false, canChangeAgent = true)
            }
            Home.TASKS -> when {
                item.repeating -> NONE.copy(block = EditBlock.REPEATING_TODO)
                else -> Capabilities(EditBlock.NONE, canEdit = true, canDelete = true, canTickDone = true, percentFromBoard = false, canChangeAgent = true)
            }
            // A Gantt task or a card is never deleted here (design 9).
            Home.GANTT -> Capabilities(
                EditBlock.NONE, canEdit = true, canDelete = false, canTickDone = true,
                percentFromBoard = item.progressMode == "deck", canChangeAgent = true
            )
            Home.BOARD -> Capabilities(EditBlock.NONE, canEdit = true, canDelete = false, canTickDone = true, percentFromBoard = false, canChangeAgent = true)
        }
    }
}
