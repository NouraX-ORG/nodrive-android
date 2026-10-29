/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.gantt

import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.logic.BuildContext
import org.nourax.nodrive.agenda.logic.ProjectRelation
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
import org.nourax.nodrive.agenda.ocs.dateOfText
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.numberOf
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.Agent
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * NoGantt tasks, milestones and capacity findings -> rows (design 4.2, 4.4, 4.5, 6.2, 6.3).
 * NoAgenda never creates planning people and never guesses who a planning person or an agent is.
 */
object GanttMapper {
    class Result {
        val items = ArrayList<AgendaItem>()

        /**
         * Cards that a Gantt task is linked to, and for whom (lower-case user ids; "" = a row without a person).
         * Only a task that gives a row in this build is listed, and only for the person that row is for: the card
         * of that person is not shown a second time. A card of anyone else stays (one row, never zero).
         */
        val linkedCardRows = HashMap<Int, MutableSet<String>>()
    }

    private class Person(
        val uid: String,
        val name: String,
        val resourceId: Int,
        val workHours: Double
    ) {
        val agents = ArrayList<Agent>()

        /** The work of the first agent's own planning person on the task (hours). */
        var agentWorkHours = -1.0
        val unknownAgentLabels = ArrayList<String>()
    }

    private class TaskFlag(val flag: ProblemFlag, val personUid: String, val agent: Agent? = null)

    private class Candidate(val id: Int, val name: String, val work: Double)

    private fun personName(resource: JsonObject): String {
        val display = resource.str("uid_display_name")
        return display.ifEmpty { resource.str("name") }
    }

    private fun isLate(end: LocalDate?, status: String, today: LocalDate) = end != null && end.isBefore(today) && status != "done"

    fun taskCopyCalendarUri(snapshot: GanttSnapshot): String {
        if (!snapshot.taskSync.bool("enabled")) return ""
        return snapshot.taskSync.str("calendar_uri")
    }

    /** A planning person's `work` on a task in hours (person days times the instance's hours per person day); -1 = none. */
    fun workHours(row: JsonObject, resource: JsonObject, hoursPerPt: Double): Double {
        val work = row.get("work")
        if (work == null || work.isJsonNull) return -1.0
        val value = numberOf(work, -1.0)
        if (value < 0) return -1.0
        return if (resource.str("unit") == "PT") value * hoursPerPt else value
    }

    fun relationOf(ctx: BuildContext, snapshot: GanttSnapshot, project: JsonObject): ProjectRelation {
        val myProjects = snapshot.myTasks.objects().map { intOf(it.get("project_id")) }.toSet()
        val id = intOf(project.get("id"))
        val data = snapshot.projectData[id]
        return ProjectRelation.of(
            project, ctx.me, ctx.serverAdmin, ctx.myGroups, myProjects,
            data?.shares ?: com.google.gson.JsonArray(), data?.sharesKnown ?: false
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    fun map(ctx: BuildContext, snapshot: GanttSnapshot, out: Result) {
        if (!snapshot.loaded || snapshot.unavailable) return
        val resources = HashMap<Int, JsonObject>()
        for (r in snapshot.resources.objects()) resources[intOf(r.get("id"))] = r
        val hoursPerPt = ctx.hoursPerPt
        val readOnlyByGuard = !snapshot.contractOk

        for (project in snapshot.projects.objects()) {
            val projectId = intOf(project.get("id"))
            val data = snapshot.projectData[projectId] ?: GanttProjectData()
            val relation = relationOf(ctx, snapshot, project)
            val mode = ctx.settings.projectMode(relation)
            if (mode == WorkMode.HIDDEN || !data.loaded) continue
            val oversee = mode == WorkMode.DO_OVERSEE
            val projectTitle = project.str("title")
            val color = SourceColours.display(project.str("color"))
            val viewerOnly = project.str("permission") == "viewer"

            val tasksById = HashMap<Int, JsonObject>()
            for (t in data.tasks.objects()) tasksById[intOf(t.get("id"))] = t
            val phaseNames = HashMap<Int, String>()
            for (sp in data.subprojects.objects()) phaseNames[intOf(sp.get("id"))] = sp.str("title")

            var haveMyTask = false
            val notSharedCounts = LinkedHashMap<String, Int>()
            val notSharedNames = HashMap<String, String>()

            for (task in data.tasks.objects()) {
                val taskId = intOf(task.get("id"))
                val status = task.str("status")
                val done = status == "done"
                val start = dateOfText(task.get("start_date"))
                val end = dateOfText(task.get("end_date"))

                // ---- who is on the task ------------------------------------------------------------------
                val persons = ArrayList<Person>()
                val candidates = ArrayList<Candidate>()
                for (row in task.arr("resources").objects()) {
                    val resourceId = intOf(row.get("resource_id"))
                    val resource = resources[resourceId] ?: continue // not visible to me
                    val type = resource.str("type")
                    val uid = resource.str("uid")
                    if (type == "person" && uid.isNotEmpty()) {
                        persons += Person(uid, personName(resource), resourceId, workHours(row, resource, hoursPerPt))
                    } else if (type == "person") {
                        candidates += Candidate(resourceId, resource.str("name"), workHours(row, resource, hoursPerPt))
                    }
                    // A role row only matters when nobody else is on the task (see NOT_ASSIGNED below).
                }

                fun namesOfUid(uid: String): List<String> {
                    val names = ArrayList<String>()
                    for (r in resources.values) {
                        if (r.str("type") == "person" && r.str("uid").equals(uid, ignoreCase = true)) {
                            names += r.str("uid_display_name")
                            names += r.str("name")
                        }
                    }
                    return names.filter { it.isNotEmpty() }
                }

                val flags = ArrayList<TaskFlag>()
                // ---- agents (design 4.5) -----------------------------------------------------------------
                for (cand in candidates) {
                    val agent: Agent? = ctx.registry.byResourceId(cand.id) ?: agentByPlanningName(ctx, cand.name, ::namesOfUid)
                    if (agent != null) {
                        if (persons.isEmpty()) {
                            flags += TaskFlag(problem(ProblemKind.AGENT_NO_PERSON, Audience.BOTH), agent.person, agent)
                            continue
                        }
                        val ownerIndex = persons.indexOfFirst { it.uid.equals(agent.person, ignoreCase = true) }
                        if (ownerIndex < 0) {
                            // The owner's display name comes from the planning people I can see; when it is not there,
                            // the sentence leaves it out (a user id is never written on screen).
                            var ownerName = if (ctx.isMe(agent.person)) ctx.myName else ""
                            if (ownerName.isEmpty()) {
                                ownerName = resources.values.firstOrNull { it.str("uid").equals(agent.person, ignoreCase = true) }
                                    ?.let { personName(it) }.orEmpty()
                            }
                            flags += TaskFlag(
                                problem(ProblemKind.AGENT_WRONG_PERSON, Audience.MANAGER, ctx.agentNameInText(agent), ownerName, persons.first().name),
                                ""
                            )
                            continue
                        }
                        val owner = persons[ownerIndex]
                        owner.agents += agent
                        if (owner.agents.size == 1) owner.agentWorkHours = cand.work
                        if (agent.isRetired) {
                            flags += TaskFlag(problem(ProblemKind.RETIRED_AGENT, Audience.PERSON, agent.name), owner.uid)
                        }
                    } else if (cand.name.startsWith(AgentRegistry.robot)) {
                        // Looks like an agent but is not in the registry: never guessed.
                        val who = if (persons.size == 1) persons[0].uid else ""
                        if (persons.size == 1) persons[0].unknownAgentLabels += cand.name
                        flags += TaskFlag(problem(ProblemKind.UNKNOWN_AGENT, Audience.PERSON, cand.name), who)
                    } else {
                        flags += TaskFlag(problem(ProblemKind.NOT_LINKED, Audience.MANAGER), "")
                    }
                }
                for (p in persons) {
                    if (p.agents.size > 1) flags += TaskFlag(problem(ProblemKind.TWO_AGENTS, Audience.BOTH, p.name), p.uid)
                }
                if (persons.isEmpty() && !done) flags += TaskFlag(problem(ProblemKind.NOT_ASSIGNED, Audience.MANAGER), "")

                // ---- lateness and blocking ---------------------------------------------------------------
                val late = isLate(end, status, ctx.today)
                val lateDays = if (late && end != null) ChronoUnit.DAYS.between(end, ctx.today).toInt() else 0
                val dueToday = end != null && end == ctx.today && !done
                var blocked = false
                var blockedBy = ""
                for (dep in task.arr("dependencies").objects()) {
                    // NoGantt writes the predecessor as "t-<task id>" (or "m-<milestone id>").
                    val text = dep.str("predecessor_id")
                    val predecessor = if (text.startsWith("t-")) text.substring(2).toIntOrNull() ?: 0 else 0
                    val pred = tasksById[predecessor] ?: continue
                    if (isLate(dateOfText(pred.get("end_date")), pred.str("status"), ctx.today)) {
                        blocked = true
                        blockedBy = pred.str("title")
                        break
                    }
                }

                val cardId = task.get("deck_card_id")
                val deckCardId = if (cardId != null && cardId.isJsonPrimitive && cardId.asJsonPrimitive.isNumber) intOf(cardId) else 0
                fun cardIsThisRow(uid: String) {
                    if (deckCardId > 0) out.linkedCardRows.getOrPut(deckCardId) { HashSet() } += uid.lowercase()
                }
                val phase = phaseNames[intOf(task.get("subproject_id"))].orEmpty()

                fun common(key: String): AgendaItem = AgendaItem(
                    key = key,
                    kind = ItemKind.GANTT_TASK,
                    home = Home.GANTT,
                    title = task.str("title"),
                    startDate = start,
                    endDate = end,
                    due = end,
                    sourceKey = "gantt:$projectId",
                    sourceName = if (phase.isNotEmpty()) "$projectTitle / $phase" else projectTitle,
                    sourceColor = color,
                    category = Category.WORK,
                    status = status,
                    done = done,
                    percentDone = intOf(task.get("manual_progress"), -1),
                    late = late,
                    lateDays = lateDays,
                    dueToday = dueToday,
                    blocked = blocked,
                    blockedBy = blockedBy,
                    projectId = projectId,
                    projectName = projectTitle,
                    taskId = taskId,
                    version = task.str("updated_at"),
                    progressMode = task.str("progress_mode"),
                    versionReadOnly = readOnlyByGuard,
                    description = task.str("description").take(DESCRIPTION_LIMIT),
                    deckCardId = deckCardId,
                    boardBadge = deckCardId > 0,
                    readOnly = viewerOnly,
                    readOnlyReason = if (viewerOnly) ReadOnlyReason.VIEWER_PROJECT else ReadOnlyReason.NONE
                )

                fun agentFor(p: Person): AgentRef = when {
                    p.agents.isNotEmpty() -> ctx.refFor(p.agents.first(), p.name)
                    p.unknownAgentLabels.isNotEmpty() -> AgentRegistry.unknownRef(p.unknownAgentLabels.first())
                    else -> AgentRef.SELF
                }

                // ---- my row -------------------------------------------------------------------------------
                val mine = persons.firstOrNull { ctx.isMe(it.uid) }
                if (mine != null && !done) {
                    haveMyTask = true
                    cardIsThisRow(mine.uid)
                    val agent = agentFor(mine)
                    val problems = ArrayList<ProblemFlag>()
                    for (f in flags) {
                        val forMe = f.personUid.isEmpty() || ctx.isMe(f.personUid)
                        val personAudience = f.flag.audience == Audience.PERSON || f.flag.audience == Audience.BOTH
                        val managerAudience = f.flag.audience == Audience.MANAGER || f.flag.audience == Audience.BOTH
                        if ((forMe && personAudience) || (oversee && managerAudience)) problems += f.flag
                    }
                    out.items += common("gantt:task:$taskId").copy(
                        agent = agent,
                        workKind = if (agent.isSelf) WorkKind.SELF else WorkKind.MY_AGENT,
                        myWorkHours = mine.workHours,
                        problems = problems
                    )
                }

                // ---- my agent is on this task and its person (me) is not (design 4.4, row 1: the person is told) ----
                // A row for me exists only when my own planning person is on the task, so this one only reports the
                // problem. It is not my work. When I run the project, the manager row below reports it.
                if (mine == null && !oversee && !done) {
                    val told = HashSet<String>()
                    for (f in flags) {
                        val agent = f.agent
                        if (f.flag.kind != ProblemKind.AGENT_NO_PERSON || !ctx.isMe(f.personUid) || agent == null || !told.add(agent.id)) continue
                        out.items += AgendaItem(
                            key = "gantt:noperson:$taskId:${agent.id}",
                            kind = ItemKind.GANTT_TASK,
                            home = Home.GANTT,
                            title = task.str("title"),
                            sourceKey = "gantt:$projectId",
                            sourceName = projectTitle,
                            sourceColor = color,
                            category = Category.WORK,
                            problemOnly = true, // not my work: no date, so no workload, no clash, no "No date" entry
                            workKind = WorkKind.MY_AGENT,
                            agent = AgentRegistry.refFor(agent),
                            projectId = projectId,
                            projectName = projectTitle,
                            taskId = taskId,
                            versionReadOnly = readOnlyByGuard,
                            readOnly = true,
                            readOnlyReason = ReadOnlyReason.PROBLEM_ROW,
                            problems = listOf(problem(ProblemKind.AGENT_WITHOUT_YOU, Audience.PERSON, agent.name, task.str("title")))
                        )
                    }
                }

                // ---- other people's rows (what I run) --------------------------------------------------------
                if (!oversee || done) continue
                var anyOther = false
                for (p in persons) {
                    if (ctx.isMe(p.uid)) continue
                    anyOther = true
                    cardIsThisRow(p.uid)
                    val problems = ArrayList<ProblemFlag>()
                    for (f in flags) {
                        val forThem = f.personUid.isEmpty() || f.personUid.equals(p.uid, ignoreCase = true)
                        val managerAudience = f.flag.audience == Audience.MANAGER || f.flag.audience == Audience.BOTH
                        if (forThem && managerAudience) problems += f.flag
                    }
                    problems.addAll(0, lateBlockedFlags(late, lateDays, dueToday, blocked, blockedBy))
                    out.items += common("gantt:task:$taskId:${p.uid}").copy(
                        workKind = WorkKind.TEAM,
                        personName = p.name,
                        personUid = p.uid,
                        agent = agentFor(p),
                        problems = problems
                    )
                    // A person on a run project that is not shared with them.
                    if (data.sharesKnown && !relation.seenOnlyAsServerAdmin) {
                        notSharedCounts[p.uid] = (notSharedCounts[p.uid] ?: 0) + 1
                        notSharedNames[p.uid] = p.name
                    }
                }
                if (!anyOther && mine == null) {
                    // Nobody (or only roles, or only unlinked people): a task row without a person.
                    cardIsThisRow("")
                    val problems = ArrayList<ProblemFlag>()
                    for (f in flags) {
                        if (f.flag.audience == Audience.MANAGER || f.flag.audience == Audience.BOTH) problems += f.flag
                    }
                    problems.addAll(0, lateBlockedFlags(late, lateDays, dueToday, false, ""))
                    out.items += common("gantt:task:$taskId").copy(workKind = WorkKind.TEAM, agent = AgentRef.NONE, problems = problems)
                }
            }

            // ---- people in a project that is not shared with them ------------------------------------------------
            if (oversee && data.sharesKnown && !relation.seenOnlyAsServerAdmin) {
                var groupOrTeamShares = false
                val sharedUsers = HashSet<String>()
                for (share in data.shares.objects()) {
                    if (share.str("principal_type") == "user") sharedUsers += share.str("principal_id").lowercase() else groupOrTeamShares = true
                }
                if (!groupOrTeamShares) {
                    for ((uid, count) in notSharedCounts) {
                        if (uid.lowercase() in sharedUsers || uid.equals(relation.ownerId, ignoreCase = true)) continue
                        val name = notSharedNames[uid].orEmpty()
                        out.items += AgendaItem(
                            key = "gantt:notshared:$projectId:$uid",
                            kind = ItemKind.GANTT_TASK,
                            home = Home.GANTT,
                            title = "",
                            sourceKey = "gantt:$projectId",
                            sourceName = projectTitle,
                            sourceColor = color,
                            workKind = WorkKind.TEAM,
                            personName = name,
                            personUid = uid,
                            projectId = projectId,
                            projectName = projectTitle,
                            problems = listOf(problem(ProblemKind.NOT_SHARED, Audience.MANAGER, name, count.toString()))
                        )
                    }
                }
            }

            // ---- milestones of projects where I have tasks -------------------------------------------------------
            if (haveMyTask) {
                for (m in data.milestones.objects()) {
                    val date = dateOfText(m.get("milestone_date")) ?: continue
                    out.items += AgendaItem(
                        key = "gantt:ms:${intOf(m.get("id"))}",
                        kind = ItemKind.MILESTONE,
                        home = Home.GANTT,
                        title = m.str("title"),
                        startDate = date,
                        endDate = date,
                        due = date,
                        dueToday = date == ctx.today,
                        sourceKey = "gantt:$projectId",
                        sourceName = projectTitle,
                        sourceColor = color,
                        category = Category.WORK,
                        workKind = WorkKind.SELF,
                        projectId = projectId,
                        projectName = projectTitle,
                        taskId = intOf(m.get("id")),
                        version = m.str("updated_at"),
                        readOnly = true,
                        readOnlyReason = ReadOnlyReason.MILESTONE,
                        versionReadOnly = readOnlyByGuard
                    )
                }
            }
        }

        capacityFindings(ctx, snapshot, resources, out)
    }


    /**
     * Placeholder phase (design 4.5 b): the exact name "robot agent name (person's display name)" of an agent of the
     * registry. The person's display name is read from the visible planning people. Anything else is not guessed.
     */
    private fun agentByPlanningName(ctx: BuildContext, name: String, namesOfUid: (String) -> List<String>): Agent? {
        for (candidateAgent in ctx.registry.agents) {
            for (ownerName in namesOfUid(candidateAgent.person)) {
                val wanted = AgentRegistry.planningPersonName(candidateAgent.name, ownerName)
                if (AgentRegistry.normalize(name) == AgentRegistry.normalize(wanted)) return candidateAgent
            }
        }
        return null
    }

    private fun lateBlockedFlags(late: Boolean, lateDays: Int, dueToday: Boolean, blocked: Boolean, blockedBy: String): List<ProblemFlag> {
        val list = ArrayList<ProblemFlag>()
        if (blocked) list += problem(ProblemKind.BLOCKED, Audience.MANAGER, blockedBy)
        if (dueToday) list += problem(ProblemKind.DUE_TODAY, Audience.MANAGER)
        if (late) list += problem(ProblemKind.LATE, Audience.MANAGER, lateDays.toString())
        return list
    }

    /** Someone overbooked (NoGantt's own capacity check) in what I run. */
    private fun capacityFindings(ctx: BuildContext, snapshot: GanttSnapshot, resources: Map<Int, JsonObject>, out: Result) {
        var index = 0
        for (finding in snapshot.capacity.arr("findings").objects()) {
            index++
            val taskIds = finding.arr("task_ids").map { intOf(it) }.toSet()
            var projectId = 0
            var projectTitle = ""
            var color = ""
            for (project in snapshot.projects.objects()) {
                val id = intOf(project.get("id"))
                if (ctx.settings.projectMode(relationOf(ctx, snapshot, project)) != WorkMode.DO_OVERSEE) continue
                val data = snapshot.projectData[id] ?: continue
                if (data.tasks.objects().any { intOf(it.get("id")) in taskIds }) {
                    projectId = id
                    projectTitle = project.str("title")
                    color = SourceColours.display(project.str("color"))
                    break
                }
            }
            if (projectId == 0) continue
            val names = finding.arr("person_ids").mapNotNull { resources[intOf(it)]?.let { r -> personName(r) } }
            if (names.isEmpty()) continue
            val joined = names.joinToString(", ")
            out.items += AgendaItem(
                key = "gantt:capacity:$index",
                kind = ItemKind.GANTT_TASK,
                home = Home.GANTT,
                workKind = WorkKind.TEAM,
                personName = joined,
                startDate = dateOfText(finding.get("start")),
                endDate = dateOfText(finding.get("end")),
                sourceKey = "gantt:$projectId",
                sourceName = projectTitle,
                sourceColor = color,
                projectId = projectId,
                projectName = projectTitle,
                problems = listOf(problem(ProblemKind.OVERBOOKED, Audience.MANAGER, joined))
            )
        }
    }

    private const val DESCRIPTION_LIMIT = 2000
}
