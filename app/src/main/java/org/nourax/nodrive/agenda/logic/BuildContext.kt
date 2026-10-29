/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.logic

import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.model.Audience
import org.nourax.nodrive.agenda.model.ProblemFlag
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the mappers (calendar, NoGantt, NoBoard) need to turn raw data into rows. */
class BuildContext(
    /** The user id of the signed-in person. */
    val me: String,
    val myName: String,
    /** Lower case e-mail addresses (calendar-user-address-set, the account's e-mail). */
    val myAddresses: List<String>,
    val myGroups: List<String>,
    val serverAdmin: Boolean,
    val registry: AgentRegistry,
    val settings: SourceSettings,
    val today: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    val hoursPerPt: Double = 8.0,
    /** NoGantt's copy calendar (hidden). */
    val taskCopyCalendarUri: String = ""
) {
    fun isMe(uid: String): Boolean = uid.isNotEmpty() && uid.equals(me, ignoreCase = true)

    val myOrganisations: Set<String> by lazy { registry.organisationsOf(me) }

    /**
     * Whether the name of this agent may be shown to me: my own agents and the agents of my organisations
     * (design 4.1: a person is only ever shown agents of their own organisation).
     */
    fun mayNameAgent(agent: Agent): Boolean = isMe(agent.person) || agent.organisation in myOrganisations

    /** The name of an agent inside a problem sentence: the registry name when I may see it, otherwise empty. */
    fun agentNameInText(agent: Agent): String = if (mayNameAgent(agent)) agent.name else ""

    /**
     * The reference shown for a registry agent. The name of an agent of another person is shown only when its
     * organisation is one of mine; otherwise "Agent of <person>". [ownerDisplayName] is a display name, never a user id.
     */
    fun refFor(agent: Agent, ownerDisplayName: String): AgentRef {
        val ref = AgentRegistry.refFor(agent)
        return if (mayNameAgent(agent)) ref else ref.copy(otherOrganisation = true, owner = ownerDisplayName)
    }
}

fun problem(kind: ProblemKind, audience: Audience, vararg args: String) = ProblemFlag(kind, audience, args.toList())
