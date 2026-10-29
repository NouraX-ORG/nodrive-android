/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.gantt

import androidx.annotation.Keep
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.logic.ProjectRelation
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.getOcs
import org.nourax.nodrive.agenda.ocs.OcsReply
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str

@Keep
data class GanttProjectData(
    val tasks: JsonArray = JsonArray(),
    val milestones: JsonArray = JsonArray(),
    val subprojects: JsonArray = JsonArray(),
    val shares: JsonArray = JsonArray(),
    val sharesKnown: Boolean = false,
    val sharesFetchedAtMs: Long = 0,
    val loaded: Boolean = false
)

/** What is read from NoGantt 2.4.1, and its offline-cache form. */
@Keep
data class GanttSnapshot(
    val loaded: Boolean = false,
    /** The app is not on the server. */
    val unavailable: Boolean = false,
    /** Every response had the 2.4.1 keys and types. */
    val contractOk: Boolean = true,
    /** "endpoint: missing key x" (no values). */
    val contractProblem: String = "",
    val fetchedAtMs: Long = 0,
    val slowFetchedAtMs: Long = 0,
    val hasLinkedResource: Boolean = true,
    val myTasks: JsonArray = JsonArray(),
    val projects: JsonArray = JsonArray(),
    val resources: JsonArray = JsonArray(),
    val workSchedules: JsonArray = JsonArray(),
    val holidays: JsonArray = JsonArray(),
    val workCalendars: JsonArray = JsonArray(),
    val settings: JsonObject = JsonObject(),
    val taskSync: JsonObject = JsonObject(),
    val capacity: JsonObject = JsonObject(),
    val projectData: Map<Int, GanttProjectData> = emptyMap(),
    val failedProjects: Set<Int> = emptySet()
)

class GanttFetchOptions(
    val davUser: String,
    val serverAdmin: Boolean,
    val groups: List<String>,
    val nowMs: Long,
    /** Read the slow data (schedules, holidays, settings, task copy) now, not only once an hour. */
    val forceSlow: Boolean = false,
    /** Which projects' tasks are read (a Hidden project is not). */
    val wantProject: (ProjectRelation) -> Boolean = { true }
)

class GanttFetchResult(val ok: Boolean, val status: Int, val networkError: Boolean, val snapshot: GanttSnapshot)

/** The NoGantt OCS calls of plan 1.5. Every route is an OCS route; the header `OCS-APIRequest` is required. */
object GanttClient {
    private const val TAG = "AgendaGantt"
    const val API_BASE = "ocs/v2.php/apps/nc_gantt/api/v1/"
    private const val SLOW_SECONDS = 3600L
    private const val SHARES_SECONDS = 24 * 3600L

    private class Run(
        val http: AgendaHttp,
        val options: GanttFetchOptions,
        val previous: GanttSnapshot
    ) {
        var contractOk = true
        var contractProblem = ""

        fun get(path: String, endpoint: GanttContract.Endpoint, checkShape: Boolean): OcsReply {
            val full = API_BASE + path + (if (path.contains('?')) "&format=json" else "?format=json")
            val reply = OcsJson.parse(http.getOcs(full))
            if (checkShape && reply.ok) {
                val problem = GanttContract.check(endpoint, reply.data)
                if (problem.isNotEmpty() && contractOk) {
                    contractOk = false
                    contractProblem = problem
                    AgendaLog.w(TAG, "contract: $problem")
                }
            }
            return reply
        }
    }

    fun fetch(http: AgendaHttp, options: GanttFetchOptions, previous: GanttSnapshot): GanttFetchResult {
        val run = Run(http, options, previous)
        val mine = run.get("my-tasks?include_done=false&limit=500", GanttContract.Endpoint.MY_TASKS, true)
        if (mine.notFound) {
            val snapshot = GanttSnapshot(loaded = true, unavailable = true, fetchedAtMs = options.nowMs)
            return GanttFetchResult(true, mine.httpStatus, false, snapshot)
        }
        val mineObject = mine.data as? JsonObject
        if (!mine.ok || mineObject == null) return fail(mine, previous)

        val hasLinked = (mineObject.get("has_linked_resource")?.takeIf { it.isJsonPrimitive }?.asBoolean) ?: true
        val myTasks = mineObject.get("tasks") as? JsonArray ?: JsonArray()
        val myProjectIds = myTasks.objects().map { intOf(it.get("project_id")) }.toSet()

        val projectsReply = run.get("projects", GanttContract.Endpoint.PROJECTS, true)
        val projects = projectsReply.data as? JsonArray
        if (!projectsReply.ok || projects == null) return fail(projectsReply, previous)

        val resourcesReply = run.get("resources", GanttContract.Endpoint.RESOURCES, true)
        val resources = resourcesReply.data as? JsonArray
        if (!resourcesReply.ok || resources == null) return fail(resourcesReply, previous)

        // Slow data: once an hour.
        val fresh = !options.forceSlow && previous.slowFetchedAtMs > 0 && (options.nowMs - previous.slowFetchedAtMs) < SLOW_SECONDS * 1000
        var schedules = previous.workSchedules
        var holidays = previous.holidays
        var workCalendars = previous.workCalendars
        var settings = previous.settings
        var taskSync = previous.taskSync
        var slowAt = previous.slowFetchedAtMs
        if (!fresh) {
            slowAt = options.nowMs
            run.get("work-schedules", GanttContract.Endpoint.SUBPROJECTS, false).array()?.let { schedules = it }
            run.get("holidays", GanttContract.Endpoint.SUBPROJECTS, false).array()?.let { holidays = it }
            run.get("work-calendars", GanttContract.Endpoint.SUBPROJECTS, false).array()?.let { workCalendars = it }
            (run.get("settings", GanttContract.Endpoint.SUBPROJECTS, false).data as? JsonObject)?.let { settings = it }
            (run.get("task-sync", GanttContract.Endpoint.TASK_SYNC, true).data as? JsonObject)?.let { taskSync = it }
        }

        val projectData = LinkedHashMap<Int, GanttProjectData>()
        val failedProjects = HashSet<Int>()
        var anyRunProject = false
        for (project in projects.objects()) {
            val id = intOf(project.get("id"))
            val owned = project.str("owner_id").equals(options.davUser, ignoreCase = true)
            val adminPermission = project.str("permission") == "admin"
            val old = previous.projectData[id] ?: GanttProjectData()
            val needShares = owned || adminPermission
            val sharesFresh = old.sharesKnown && old.sharesFetchedAtMs > 0 && (options.nowMs - old.sharesFetchedAtMs) < SHARES_SECONDS * 1000

            var data = GanttProjectData(shares = old.shares, sharesKnown = old.sharesKnown, sharesFetchedAtMs = old.sharesFetchedAtMs)
            if (needShares && !sharesFresh) {
                val shares = run.get("projects/$id/shares", GanttContract.Endpoint.SHARES, true)
                val list = shares.data as? JsonArray
                if (shares.ok && list != null) {
                    data = data.copy(shares = list, sharesKnown = true, sharesFetchedAtMs = options.nowMs)
                }
            }
            val relation = ProjectRelation.of(project, options.davUser, options.serverAdmin, options.groups, myProjectIds, data.shares, data.sharesKnown)
            if (!options.wantProject(relation)) {
                // Hidden: its tasks are not read. The shares that were fetched stay (they are read once a day).
                if (data.sharesKnown) projectData[id] = data
                continue
            }
            if (relation.runByRealShare) anyRunProject = true

            val base = "projects/$id"
            val tasksReply = run.get("$base/tasks", GanttContract.Endpoint.PROJECT_TASKS, true)
            val tasks = tasksReply.data as? JsonArray
            if (!tasksReply.ok || tasks == null) {
                failedProjects += id
                projectData[id] = data.copy(tasks = old.tasks, milestones = old.milestones, subprojects = old.subprojects, loaded = old.loaded)
                continue
            }
            val milestones = run.get("$base/milestones", GanttContract.Endpoint.MILESTONES, true).array() ?: old.milestones
            val subprojects = run.get("$base/subprojects", GanttContract.Endpoint.SUBPROJECTS, true).array() ?: old.subprojects
            projectData[id] = data.copy(tasks = tasks, milestones = milestones, subprojects = subprojects, loaded = true)
        }

        var capacity = JsonObject()
        if (anyRunProject) {
            val reply = run.get("capacity-check", GanttContract.Endpoint.CAPACITY_CHECK, true)
            capacity = (reply.data as? JsonObject)?.takeIf { reply.ok } ?: previous.capacity
        }

        val snapshot = GanttSnapshot(
            loaded = true,
            unavailable = false,
            contractOk = run.contractOk,
            contractProblem = run.contractProblem,
            fetchedAtMs = options.nowMs,
            slowFetchedAtMs = slowAt,
            hasLinkedResource = hasLinked,
            myTasks = myTasks,
            projects = projects,
            resources = resources,
            workSchedules = schedules,
            holidays = holidays,
            workCalendars = workCalendars,
            settings = settings,
            taskSync = taskSync,
            capacity = capacity,
            projectData = projectData,
            failedProjects = failedProjects
        )
        return GanttFetchResult(true, 200, false, snapshot)
    }

    private fun fail(reply: OcsReply, previous: GanttSnapshot) =
        GanttFetchResult(false, reply.httpStatus, reply.networkError, previous)

    private fun OcsReply.array(): JsonArray? = (data as? JsonArray)?.takeIf { ok }
}
