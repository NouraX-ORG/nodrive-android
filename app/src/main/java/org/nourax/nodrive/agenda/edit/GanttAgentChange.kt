/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import androidx.annotation.Keep
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.ocs.arr
import org.nourax.nodrive.agenda.ocs.bool
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.num
import org.nourax.nodrive.agenda.ocs.numberOf
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import kotlin.math.abs

/**
 * "My agent" on a NoGantt task (design 4.5 and 9; plan 2.5). The person comes first, then the agent: only the agent rows of the
 * signed-in person are added or taken off. NoAgenda never creates a planning person, never touches the row of another person, and
 * sends nothing when the server would answer something different from what was meant, because NoGantt REPLACES the whole list of
 * people of a task (at most four), drops a row it can not see without a word and recomputes the work of the task from the rows.
 */
object GanttAgentChange {
    const val MAX_ROWS = 4
    private const val WORK_TOLERANCE = 0.005

    class Context(
        /** The user id of the signed-in person. */
        val me: String,
        val myName: String,
        val registry: AgentRegistry,
        val hoursPerPt: Double = 8.0
    ) {
        fun isMe(uid: String): Boolean = uid.isNotEmpty() && uid.equals(me, ignoreCase = true)
    }

    data class Row(val resourceId: Int, val work: Double?)

    sealed interface Plan {
        /** Send exactly these rows. */
        data class Send(val rows: List<Row>) : Plan

        data class Refuse(val reason: ConflictReason, val detail: String = "") : Plan

        /** Nothing to change: the task already has the wanted agent. */
        data object Same : Plan
    }

    /** What the choice list of the detail sheet shows for one of my agents. */
    enum class ChoiceNote { NONE, PAUSED, NOT_IN_PROJECT }

    @Keep
    data class Choice(val agentId: String, val name: String, val enabled: Boolean, val note: ChoiceNote, val current: Boolean = false)

    private fun rowsOf(task: JsonObject): List<Row> = task.arr("resources").objects().map { r ->
        val w = r.get("work")
        Row(intOf(r.get("resource_id")), if (w == null || w.isJsonNull) null else numberOf(w, 0.0))
    }

    private fun namesOfUid(resources: List<JsonObject>, uid: String): List<String> {
        val names = ArrayList<String>()
        for (r in resources) {
            if (r.str("type") == "person" && r.str("uid").equals(uid, ignoreCase = true)) {
                names += r.str("uid_display_name")
                names += r.str("name")
            }
        }
        return names.filter { it.isNotEmpty() }
    }

    /** The registry agent a planning person stands for (design 4.5: its NoGantt id, or during the placeholder phase its exact name). */
    fun agentOf(resource: JsonObject, resources: List<JsonObject>, ctx: Context): Agent? {
        ctx.registry.byResourceId(intOf(resource.get("id")))?.let { return it }
        if (resource.str("type") != "person" || resource.str("uid").isNotEmpty()) return null
        val name = AgentRegistry.normalize(resource.str("name"))
        for (agent in ctx.registry.agents) {
            for (owner in namesOfUid(resources, agent.person) + (if (ctx.isMe(agent.person)) listOf(ctx.myName) else emptyList())) {
                if (owner.isEmpty()) continue
                if (name == AgentRegistry.normalize(AgentRegistry.planningPersonName(agent.name, owner))) return agent
            }
        }
        return null
    }

    private fun totalPt(rows: List<Row>, byId: Map<Int, JsonObject>, hoursPerPt: Double): Double? {
        var total = 0.0
        var any = false
        for (row in rows) {
            val work = row.work ?: continue
            any = true
            total += if (byId[row.resourceId]?.str("unit") == "h") work / hoursPerPt else work
        }
        return if (any) total else null
    }

    private fun samePt(a: Double?, b: Double?): Boolean = (a == null && b == null) || (a != null && b != null && abs(a - b) <= WORK_TOLERANCE)

    /** The ids of the people on a task, in order (for the log: ids only). */
    fun idsOf(task: JsonObject): List<Int> = rowsOf(task).map { it.resourceId }

    /** The ids of the people that are on any task of a project (design 4.5: an agent's planning person "in that project"). */
    fun idsInProject(tasks: Iterable<JsonObject>): Set<Int> = tasks.flatMap { rowsOf(it) }.map { it.resourceId }.toSet()

    /**
     * The ids of the planning people that sit on a task of this project next to my own planning person (design 4.5 (b)): the people
     * on the tasks where I am a person too.
     */
    fun idsNextToMe(tasks: Iterable<JsonObject>, resources: List<JsonObject>, ctx: Context): Set<Int> {
        val mine = resources.filter { it.str("type") == "person" && ctx.isMe(it.str("uid")) }.map { intOf(it.get("id")) }.toSet()
        val out = HashSet<Int>()
        for (task in tasks) {
            val ids = rowsOf(task).map { it.resourceId }
            if (ids.any { it in mine }) out += ids
        }
        return out
    }

    // The people next to me, with those of the task being changed (a task of mine is next to me by itself).
    private fun nextToMeWith(task: JsonObject, resources: List<JsonObject>, ctx: Context, known: Set<Int>): Set<Int> = known + idsNextToMe(listOf(task), resources, ctx)

    /**
     * The planning person of [agent] that I can see, or null. A person listed in the registry by its NoGantt id counts at once; when
     * the agent has more than one, the one already on a task of this project ([inProject]) is preferred. A person recognised only by
     * its exact name (design 4.5 (b)) counts only when it sits on a task next to my own planning person ([nextToMe]); otherwise it is
     * "unknown" and the person is told to ask the project manager.
     */
    private fun resourceOf(agent: Agent, resources: List<JsonObject>, ctx: Context, inProject: Set<Int>, nextToMe: Set<Int>): JsonObject? {
        val candidates = resources.filter { agentOf(it, resources, ctx)?.id == agent.id }
        val byId = candidates.filter { ctx.registry.byResourceId(intOf(it.get("id")))?.id == agent.id }
        byId.firstOrNull { intOf(it.get("id")) in inProject }?.let { return it }
        byId.firstOrNull()?.let { return it }
        return candidates.firstOrNull { intOf(it.get("id")) in nextToMe }
    }

    /**
     * The new list of people for the task (a fresh read of it), or the reason why nothing is sent. [target] is one of my agents, or
     * null for Self. All reads are the caller's: the fresh task, the project, and the planning people that I can see.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun plan(
        task: JsonObject,
        project: JsonObject?,
        resources: List<JsonObject>,
        ctx: Context,
        target: Agent?,
        inProject: Set<Int> = emptySet(),
        nextToMe: Set<Int> = emptySet()
    ): Plan {
        val byId = resources.associateBy { intOf(it.get("id")) }
        val rows = rowsOf(task)
        // My own row has to be there: the task is mine.
        if (rows.none { row -> byId[row.resourceId]?.let { it.str("type") == "person" && ctx.isMe(it.str("uid")) } == true }) {
            return Plan.Refuse(ConflictReason.CHECK_IN_HOME, "not_on_task")
        }
        // Rows that are my agents.
        val mine = rows.filter { row -> byId[row.resourceId]?.let { agentOf(it, resources, ctx)?.let { a -> ctx.isMe(a.person) } } == true }
        var targetRow: Row? = null
        if (target != null) {
            val resource = resourceOf(target, resources, ctx, inProject, nextToMeWith(task, resources, ctx, nextToMe))
                ?: return Plan.Refuse(ConflictReason.AGENT_NOT_IN_PROJECT, target.name)
            val rid = intOf(resource.get("id"))
            targetRow = rows.firstOrNull { it.resourceId == rid } ?: Row(rid, null)
        }
        val keep = rows.filter { row -> mine.none { it.resourceId == row.resourceId } || row.resourceId == targetRow?.resourceId }
        val newRows = if (targetRow != null && keep.none { it.resourceId == targetRow.resourceId }) keep + targetRow else keep
        if (newRows.map { it.resourceId } == rows.map { it.resourceId } && newRows.map { it.work } == rows.map { it.work }) return Plan.Same
        if (newRows.size > MAX_ROWS) return Plan.Refuse(ConflictReason.TOO_MANY_PEOPLE)
        if (project != null && project.bool("roles_only_assignment") && targetRow != null && rows.none { it.resourceId == targetRow.resourceId }) {
            return Plan.Refuse(ConflictReason.ROLES_ONLY)
        }
        // The first row is the primary person of the task and never changes.
        if (rows.isNotEmpty() && newRows.firstOrNull()?.resourceId != rows.first().resourceId) {
            return Plan.Refuse(ConflictReason.CHECK_IN_HOME, "primary_changes")
        }
        // A row the server could not see would be dropped without a word.
        if (rows.any { it.resourceId !in byId && newRows.any { n -> n.resourceId == it.resourceId } }) {
            return Plan.Refuse(ConflictReason.CHECK_IN_HOME, "row_not_visible")
        }
        val hours = if (ctx.hoursPerPt > 0) ctx.hoursPerPt else 8.0
        if (!samePt(totalPt(rows, byId, hours), totalPt(newRows, byId, hours))) return Plan.Refuse(ConflictReason.WORK_WOULD_CHANGE)
        return Plan.Send(newRows)
    }

    /** The choices of my agents for a task: Self is always there; the others are agents whose planning person I can see. */
    fun choices(
        task: JsonObject,
        project: JsonObject?,
        resources: List<JsonObject>,
        ctx: Context,
        inProject: Set<Int> = emptySet(),
        nextToMe: Set<Int> = emptySet()
    ): List<Choice> {
        val near = nextToMeWith(task, resources, ctx, nextToMe)
        val byId = resources.associateBy { intOf(it.get("id")) }
        val onTask = rowsOf(task).mapNotNull { row -> byId[row.resourceId]?.let { agentOf(it, resources, ctx) } }.filter { ctx.isMe(it.person) }
        val out = ArrayList<Choice>()
        for (agent in ctx.registry.ofPerson(ctx.me)) {
            if (agent.isRetired) continue
            val current = onTask.any { it.id == agent.id }
            val note = when {
                agent.isPaused && !current -> ChoiceNote.PAUSED
                resourceOf(agent, resources, ctx, inProject, near) == null -> ChoiceNote.NOT_IN_PROJECT
                else -> ChoiceNote.NONE
            }
            out += Choice(agent.id, agent.name, enabled = note == ChoiceNote.NONE, note = note, current = current)
        }
        return out
    }

    /** True when the answer of the server has the rows that were sent (same people, same work). */
    fun answerMatches(sent: List<Row>, answer: JsonObject): Boolean {
        val got = rowsOf(answer)
        if (got.size != sent.size) return false
        return sent.zip(got).all { (a, b) -> a.resourceId == b.resourceId && samePt(a.work, b.work) }
    }

    fun hoursPerPtOf(settings: JsonObject): Double = settings.num("hours_per_pt", 8.0).let { if (it > 0) it else 8.0 }
}
