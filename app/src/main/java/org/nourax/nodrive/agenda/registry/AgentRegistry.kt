/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.registry

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.arr
import org.nourax.nodrive.agenda.ocs.isString
import org.nourax.nodrive.agenda.ocs.obj
import org.nourax.nodrive.agenda.ocs.str
import java.text.Normalizer

data class AgentWeekly(val days: List<String>, val from: String, val to: String)

data class Agent(
    val id: String,
    val name: String,
    /** The owner's user id. */
    val person: String,
    /** active | paused | retired. */
    val status: String,
    val organisation: String,
    val skills: List<String> = emptyList(),
    val timezone: String = "",
    val weekly: List<AgentWeekly> = emptyList(),
    val capacityHoursPerWeek: Double = 0.0,
    val daysOff: List<String> = emptyList(),
    /** links.nogantt_resource_ids */
    val ganttResourceIds: List<Int> = emptyList(),
    /** links.noboard_label */
    val boardLabel: String = "",
    /** links.calendar_tag */
    val calendarTag: String = ""
) {
    val isPaused: Boolean get() = status == "paused"
    val isRetired: Boolean get() = status == "retired"
}

enum class RegistryStatus { MISSING, INVALID, OK }

/**
 * The agent registry (design 4.1; format `plans/agent-registry.placeholder.json`, schema `nourax-agent-registry`,
 * version 1).
 */
class AgentRegistry private constructor(
    val status: RegistryStatus,
    val agents: List<Agent>,
    val skipped: Int
) {
    fun byId(id: String): Agent? = agents.firstOrNull { it.id == id }

    /** The board label title, compared after Unicode normalisation (NFC). */
    fun byLabel(label: String): Agent? = allByLabel(label).firstOrNull()

    /** Names are unique only within an organisation, so several agents can have the same label. */
    fun allByLabel(label: String): List<Agent> {
        val wanted = normalize(label)
        return agents.filter { it.boardLabel.isNotEmpty() && normalize(it.boardLabel) == wanted }
    }

    fun byTag(tag: String): Agent? = agents.firstOrNull { it.calendarTag.isNotEmpty() && it.calendarTag == tag }

    fun byResourceId(resourceId: Int): Agent? = agents.firstOrNull { resourceId in it.ganttResourceIds }

    fun ofPerson(uid: String): List<Agent> = agents.filter { it.person.equals(uid, ignoreCase = true) }

    /** The "organisation" values of the agents of this person. */
    fun organisationsOf(uid: String): Set<String> = ofPerson(uid).map { it.organisation }.toSet()

    companion object {
        const val SCHEMA = "nourax-agent-registry"
        const val MAX_BYTES = 1024 * 1024
        private const val ROBOT = "🤖"

        val MISSING = AgentRegistry(RegistryStatus.MISSING, emptyList(), 0)
        private val INVALID = AgentRegistry(RegistryStatus.INVALID, emptyList(), 0)

        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

        /** The exact planning-person name of the placeholder phase: "robot agent name (person's display name)". */
        fun planningPersonName(agentName: String, personDisplayName: String): String =
            "$ROBOT $agentName ($personDisplayName)"

        /** The robot face that starts the title of an agent's board label or planning person. */
        val robot: String get() = ROBOT

        fun refFor(agent: Agent): AgentRef = AgentRef(
            isSelf = false,
            id = agent.id,
            name = agent.name,
            status = agent.status,
            paused = agent.isPaused,
            retired = agent.isRetired
        )

        fun unknownRef(idOrLabel: String): AgentRef = AgentRef(isSelf = false, id = idOrLabel, unknown = true)

        /**
         * Parses the file. Anything that is not schema "nourax-agent-registry" version 1 is Invalid. An agent with an
         * empty or invalid id, name, person, status or organisation is skipped (and counted); a repeated id skips
         * the later agent.
         */
        fun parse(bytes: ByteArray): AgentRegistry {
            if (bytes.size > MAX_BYTES) return INVALID
            val root = OcsJson.parseElement(bytes) as? JsonObject ?: return INVALID
            if (root.str("schema") != SCHEMA) return INVALID
            val version = root.get("version")
            if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber || version.asInt != 1) return INVALID
            val list = root.get("agents") as? JsonArray ?: return INVALID

            val seen = HashSet<String>()
            val agents = ArrayList<Agent>()
            var skipped = 0
            for (entry in list) {
                val o = entry as? JsonObject
                if (o == null) {
                    skipped++
                    continue
                }
                val id = stringOf(o, "id")
                val name = stringOf(o, "name")
                val person = stringOf(o, "person")
                val status = stringOf(o, "status")
                val organisation = stringOf(o, "organisation")
                val statusOk = status == "active" || status == "paused" || status == "retired"
                if (id.isEmpty() || name.isEmpty() || person.isEmpty() || !statusOk || organisation.isEmpty() || !seen.add(id)) {
                    skipped++
                    continue
                }
                val availability = o.obj("availability")
                val links = o.obj("links")
                agents += Agent(
                    id = id,
                    name = name,
                    person = person,
                    status = status,
                    organisation = organisation,
                    skills = stringList(o.get("skills")),
                    timezone = stringOf(availability, "timezone"),
                    weekly = availability.arr("weekly").mapNotNull { w ->
                        (w as? JsonObject)?.let { AgentWeekly(stringList(it.get("days")), stringOf(it, "from"), stringOf(it, "to")) }
                    },
                    capacityHoursPerWeek = availability.get("capacity_hours_per_week")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 0.0,
                    daysOff = stringList(availability.get("days_off")),
                    ganttResourceIds = links.arr("nogantt_resource_ids").mapNotNull {
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt else null
                    },
                    boardLabel = stringOf(links, "noboard_label"),
                    calendarTag = stringOf(links, "calendar_tag")
                )
            }
            return AgentRegistry(RegistryStatus.OK, agents, skipped)
        }

        private fun stringOf(o: JsonObject, key: String): String {
            val v = o.get(key)
            return if (v.isString()) v!!.asString.trim() else ""
        }

        private fun stringList(v: com.google.gson.JsonElement?): List<String> =
            (v as? JsonArray)?.mapNotNull { if (it.isString()) it.asString else null }.orEmpty()
    }
}
