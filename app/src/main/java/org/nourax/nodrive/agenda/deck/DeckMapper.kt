/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.deck

import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.logic.BoardRelation
import org.nourax.nodrive.agenda.logic.BuildContext
import org.nourax.nodrive.agenda.logic.SourceColours
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.logic.problem
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
import org.nourax.nodrive.agenda.ocs.arr
import org.nourax.nodrive.agenda.ocs.bool
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.isString
import org.nourax.nodrive.agenda.ocs.localDateOfIso
import org.nourax.nodrive.agenda.ocs.obj
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.temporal.ChronoUnit

/** NoBoard (Deck) cards -> rows (design 4.2, 6.2, 6.3). */
object DeckMapper {
    private const val DESCRIPTION_LIMIT = 2000

    fun relationOf(ctx: BuildContext, board: JsonObject): BoardRelation = BoardRelation.of(board, ctx.me)

    /**
     * The registry agent a board label stands for (design 4.2: matched by title among the agents of the people the
     * card is assigned to). Names are unique only within an organisation, so the same label can belong to agents of
     * different people. When none of the assigned people has such an agent, the label may still name an agent of
     * someone else (the "belongs to" problem); one I may see is preferred then.
     */
    private fun agentOfLabel(ctx: BuildContext, title: String, assignedUids: List<String>): Agent? {
        val candidates = ctx.registry.allByLabel(title)
        for (agent in candidates) {
            if (assignedUids.any { it.equals(agent.person, ignoreCase = true) }) return agent
        }
        return candidates.firstOrNull { ctx.mayNameAgent(it) } ?: candidates.firstOrNull()
    }

    /** Display names of the people the board shows (owner, members, assigned users), by lower case uid. */
    private fun displayNamesOf(ctx: BuildContext, board: JsonObject): Map<String, String> {
        val names = HashMap<String, String>()
        fun add(person: JsonObject) {
            val uid = person.str("uid").lowercase()
            val display = person.str("displayname")
            if (uid.isNotEmpty() && display.isNotEmpty() && uid !in names) names[uid] = display
        }
        if (ctx.me.isNotEmpty() && ctx.myName.isNotEmpty()) names[ctx.me.lowercase()] = ctx.myName
        add(board.obj("owner"))
        for (entry in board.arr("acl").objects()) add(entry.obj("participant"))
        for (stack in board.arr("_stacks").objects()) {
            for (card in stack.arr("cards").objects()) {
                for (a in card.arr("assignedUsers").objects()) add(a.obj("participant"))
            }
        }
        return names
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun map(ctx: BuildContext, snapshot: DeckSnapshot, linkedCardIds: Set<Int>, out: MutableList<AgendaItem>) {
        if (!snapshot.loaded || snapshot.unavailable) return
        val readOnlyByGuard = !DeckClient.versionIsTested(snapshot.version)
        val robot = AgentRegistry.robot

        for (board in snapshot.boards.objects()) {
            val relation = relationOf(ctx, board)
            val mode = ctx.settings.boardMode(relation)
            if (mode == WorkMode.HIDDEN) continue
            val oversee = mode == WorkMode.DO_OVERSEE
            val boardTitle = board.str("title")
            val boardColor = SourceColours.display(board.str("color"))
            val permissions = board.obj("permissions")
            val canEdit = if (permissions.has("PERMISSION_EDIT")) permissions.bool("PERMISSION_EDIT") else true
            val boardId = relation.id
            val personNames = displayNamesOf(ctx, board)

            for (stack in board.arr("_stacks").objects()) {
                for (card in stack.arr("cards").objects()) {
                    val cardId = intOf(card.get("id"))
                    if (card.bool("archived")) continue
                    val doneValue = card.get("done")
                    val done = (doneValue.isString() && doneValue!!.asString.isNotEmpty()) ||
                        (doneValue != null && doneValue.isJsonPrimitive && doneValue.asJsonPrimitive.isBoolean && doneValue.asBoolean)
                    // A card that a Gantt task is linked to is the Gantt row, never two.
                    if (done || cardId in linkedCardIds) continue

                    // People
                    val assignedUids = ArrayList<String>()
                    val assignedNames = ArrayList<String>()
                    for (a in card.arr("assignedUsers").objects()) {
                        val participant = a.obj("participant")
                        val uid = participant.str("uid")
                        if (uid.isNotEmpty()) {
                            assignedUids += uid
                            assignedNames += participant.str("displayname").ifEmpty { uid }
                        }
                    }
                    val mine = assignedUids.any { ctx.isMe(it) }

                    // Agent labels
                    val labelAgents = ArrayList<Agent>()
                    val unknownLabels = ArrayList<String>()
                    for (label in card.arr("labels").objects()) {
                        val title = label.str("title")
                        val agent = agentOfLabel(ctx, title, assignedUids)
                        if (agent != null) labelAgents += agent else if (title.startsWith(robot)) unknownLabels += title
                    }

                    val due = localDateOfIso(card.str("duedate"), ctx.zone)
                    val late = due != null && due.isBefore(ctx.today)
                    val lateDays = if (late && due != null) ChronoUnit.DAYS.between(due, ctx.today).toInt() else 0
                    val dueToday = due != null && due == ctx.today

                    val etag = card.str("ETag")
                    var item = AgendaItem(
                        key = "deck:card:$cardId",
                        kind = ItemKind.CARD,
                        home = Home.BOARD,
                        title = card.str("title"),
                        description = card.str("description").take(DESCRIPTION_LIMIT),
                        due = due,
                        endDate = due,
                        sourceKey = "board:$boardId",
                        sourceName = boardTitle,
                        sourceColor = boardColor,
                        category = Category.WORK,
                        late = late,
                        lateDays = lateDays,
                        dueToday = dueToday,
                        boardId = boardId,
                        stackId = intOf(stack.get("id")),
                        cardId = cardId,
                        projectName = boardTitle,
                        version = etag.ifEmpty { card.int("lastModified").toString() },
                        versionReadOnly = readOnlyByGuard,
                        readOnly = !canEdit,
                        readOnlyReason = if (canEdit) ReadOnlyReason.NONE else ReadOnlyReason.BOARD_VIEW_ONLY
                    )

                    if (mine) {
                        val mineAgents = ArrayList<Agent>()
                        val problems = ArrayList<ProblemFlag>()
                        for (agent in labelAgents) {
                            if (ctx.isMe(agent.person)) {
                                mineAgents += agent
                                if (agent.isRetired) problems += problem(ProblemKind.RETIRED_AGENT, Audience.PERSON, agent.name)
                            } else {
                                val onCard = assignedUids.any { it.equals(agent.person, ignoreCase = true) }
                                if (!onCard && oversee) {
                                    problems += problem(
                                        ProblemKind.AGENT_WRONG_PERSON, Audience.MANAGER,
                                        ctx.agentNameInText(agent), personNames[agent.person.lowercase()].orEmpty(), ctx.myName
                                    )
                                }
                            }
                        }
                        for (label in unknownLabels) problems += problem(ProblemKind.UNKNOWN_AGENT, Audience.PERSON, label)
                        if (mineAgents.size > 1) problems += problem(ProblemKind.TWO_AGENTS, Audience.BOTH, ctx.myName)
                        val agent = when {
                            mineAgents.isNotEmpty() -> AgentRegistry.refFor(mineAgents.first())
                            unknownLabels.isNotEmpty() -> AgentRegistry.unknownRef(unknownLabels.first())
                            else -> AgentRef.SELF
                        }
                        out += item.copy(
                            agent = agent,
                            workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT,
                            problems = problems
                        )
                        continue
                    }

                    if (!oversee) {
                        // One of my agents is on a card that has nobody assigned (design 4.4, row 1: the person is told).
                        // The card is not mine, so this row only reports the problem; it is not my work. When I run
                        // the board, the team row below reports it.
                        if (assignedUids.isEmpty()) {
                            val told = HashSet<String>()
                            for (agent in labelAgents) {
                                if (!ctx.isMe(agent.person) || !told.add(agent.id)) continue
                                out += AgendaItem(
                                    key = "deck:noperson:$cardId:${agent.id}",
                                    kind = ItemKind.CARD,
                                    home = Home.BOARD,
                                    title = item.title,
                                    sourceKey = item.sourceKey,
                                    sourceName = boardTitle,
                                    sourceColor = boardColor,
                                    category = Category.WORK,
                                    problemOnly = true, // not my work: no date, so no workload, no clash, no "No date" entry
                                    workKind = WorkKind.MY_AGENT,
                                    agent = AgentRegistry.refFor(agent),
                                    boardId = boardId,
                                    cardId = cardId,
                                    projectName = boardTitle,
                                    versionReadOnly = readOnlyByGuard,
                                    readOnly = true,
                                    readOnlyReason = ReadOnlyReason.PROBLEM_ROW,
                                    problems = listOf(problem(ProblemKind.AGENT_WITHOUT_YOU, Audience.PERSON, agent.name, item.title))
                                )
                            }
                        }
                        continue
                    }

                    // A card of someone else on a board I run.
                    val problems = ArrayList<ProblemFlag>()
                    var shown: Agent? = null
                    for (agent in labelAgents) {
                        val onCard = assignedUids.any { it.equals(agent.person, ignoreCase = true) }
                        if (assignedUids.isEmpty()) {
                            problems += problem(ProblemKind.AGENT_NO_PERSON, Audience.MANAGER)
                        } else if (!onCard) {
                            problems += problem(
                                ProblemKind.AGENT_WRONG_PERSON, Audience.MANAGER,
                                ctx.agentNameInText(agent), personNames[agent.person.lowercase()].orEmpty(), assignedNames.first()
                            )
                        } else if (shown == null) {
                            shown = agent
                        } else if (shown.person.equals(agent.person, ignoreCase = true)) {
                            problems += problem(ProblemKind.TWO_AGENTS, Audience.BOTH, assignedNames.first())
                        }
                    }
                    if (assignedUids.isEmpty() && due != null) problems += problem(ProblemKind.NOT_ASSIGNED, Audience.MANAGER)
                    val front = ArrayList<ProblemFlag>()
                    if (dueToday) front += problem(ProblemKind.DUE_TODAY, Audience.MANAGER)
                    if (late) front += problem(ProblemKind.LATE, Audience.MANAGER, lateDays.toString())
                    problems.addAll(0, front)
                    item = item.copy(
                        workKind = WorkKind.TEAM,
                        personName = assignedNames.joinToString(", "),
                        personUid = assignedUids.firstOrNull().orEmpty(),
                        agent = shown?.let { ctx.refFor(it, assignedNames.firstOrNull().orEmpty()) } ?: AgentRef.SELF,
                        problems = problems
                    )
                    out += item
                }
            }
        }
    }
}
