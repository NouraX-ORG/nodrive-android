/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.model.AgendaItem
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.registry.Agent

enum class AgentNote { NONE, SELF, PAUSED, NOT_IN_PROJECT }

/** One line of the agent choice: Self, or one of the person's agents (design 4.3: the person first, then the agent). */
data class AgentOption(val id: String, val name: String, val enabled: Boolean, val note: AgentNote)

/** The choices of "who does it" for one row, or for a new event or to-do. A retired agent is never listed. */
object AgentOptions {
    private fun self() = AgentOption(EditKeys.SELF, "", enabled = true, note = AgentNote.SELF)

    /** For an item of the calendar, a to-do, a Gantt task ([ganttChoices] from the analysis) or a card. */
    fun forItem(item: AgendaItem, myAgents: List<Agent>, ganttChoices: List<GanttAgentChange.Choice>?): List<AgentOption> {
        val current = if (item.agent.isSelf) EditKeys.SELF else item.agent.id
        if (item.home == Home.GANTT) {
            val out = arrayListOf(self())
            for (choice in ganttChoices.orEmpty()) {
                val note = when (choice.note) {
                    GanttAgentChange.ChoiceNote.NONE -> AgentNote.NONE
                    GanttAgentChange.ChoiceNote.PAUSED -> AgentNote.PAUSED
                    GanttAgentChange.ChoiceNote.NOT_IN_PROJECT -> AgentNote.NOT_IN_PROJECT
                }
                out += AgentOption(choice.agentId, choice.name, choice.enabled || choice.agentId == current, note)
            }
            return out
        }
        return forNew(myAgents, current, needsLabel = item.home == Home.BOARD)
    }

    /** For a new event or to-do. */
    fun forNew(myAgents: List<Agent>, current: String = EditKeys.SELF, needsLabel: Boolean = false): List<AgentOption> {
        val out = arrayListOf(self())
        for (agent in myAgents) {
            if (agent.isRetired) continue
            if (needsLabel && agent.boardLabel.isEmpty()) continue
            val paused = agent.isPaused && agent.id != current
            out += AgentOption(agent.id, agent.name, enabled = !paused, note = if (paused) AgentNote.PAUSED else AgentNote.NONE)
        }
        return out
    }
}
