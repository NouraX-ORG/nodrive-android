/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.edit.ConflictReason
import org.nourax.nodrive.agenda.edit.GanttAgentChange
import org.nourax.nodrive.agenda.edit.GanttAgentChange.ChoiceNote
import org.nourax.nodrive.agenda.edit.GanttAgentChange.Plan
import org.nourax.nodrive.agenda.edit.GanttAgentChange.Row
import org.nourax.nodrive.agenda.registry.AgentRegistry

/** "My agent" on a NoGantt task: the new list of people, and the cases where nothing is sent (plan 2.5, 2.8). */
class GanttAgentChangeTest {
    private val robot = AgentRegistry.robot

    private fun agent(id: String, name: String, person: String, status: String, ids: String) = """
        {"id":"$id","name":"$name","person":"$person","status":"$status","organisation":"demo-org","skills":[],
         "availability":{"timezone":"UTC","weekly":[{"days":["mon","tue","wed","thu","fri"],"from":"09:00","to":"17:00"}],"capacity_hours_per_week":40,"days_off":[]},
         "links":{"nogantt_resource_ids":[$ids],"noboard_label":"$robot $name","calendar_tag":"agent:$id"}}"""

    private val registry = AgentRegistry.parse(
        (
            """{"schema":"nourax-agent-registry","version":1,"agents":[""" +
                agent("AG-0001", "Writer-Bot", "ali", "active", "11") + "," +
                agent("AG-0003", "Research-Bot", "ali", "paused", "") + "," +
                agent("AG-0002", "Design-Bot", "sara", "active", "") + "]}"
            ).toByteArray()
    )
    private val ctx = GanttAgentChange.Context("ali", "Ali", registry, 8.0)

    private fun resource(id: Int, name: String, type: String = "person", uid: String = "", unit: String = "h"): JsonObject {
        val o = JsonObject()
        o.addProperty("id", id)
        o.addProperty("name", name)
        o.addProperty("type", type)
        o.addProperty("uid", uid)
        o.addProperty("uid_display_name", if (uid.isEmpty()) "" else name)
        o.addProperty("unit", unit)
        return o
    }

    // 1 Ali, 2 Sara, 11 Writer-Bot (by id), 12 Research-Bot (by name), 13 Designer (role), 14 Design-Bot (Sara's), 15 a temp worker
    private val resources = listOf(
        resource(1, "Ali", uid = "ali"),
        resource(2, "Sara", uid = "sara"),
        resource(11, "$robot Writer-Bot (Ali)"),
        resource(12, "$robot Research-Bot (Ali)"),
        resource(13, "Designer", type = "role", unit = "PT"),
        resource(14, "$robot Design-Bot (Sara)"),
        resource(15, "Temp worker")
    )

    private fun task(vararg rows: Pair<Int, Double?>): JsonObject {
        val t = JsonObject()
        t.addProperty("id", 5)
        val array = JsonArray()
        rows.forEachIndexed { i, (rid, work) ->
            val r = JsonObject()
            r.addProperty("resource_id", rid)
            if (work == null) r.add("work", com.google.gson.JsonNull.INSTANCE) else r.addProperty("work", work)
            r.addProperty("sort_order", i)
            array.add(r)
        }
        t.add("resources", array)
        return t
    }

    private fun project(rolesOnly: Boolean = false): JsonObject {
        val p = JsonObject()
        p.addProperty("id", 1)
        p.addProperty("roles_only_assignment", rolesOnly)
        return p
    }

    private val writer get() = registry.byId("AG-0001")
    private val researcher get() = registry.byId("AG-0003")

    private fun plan(
        task: JsonObject,
        target: org.nourax.nodrive.agenda.registry.Agent?,
        rolesOnly: Boolean = false,
        visible: List<JsonObject> = resources,
        nextToMe: Set<Int> = emptySet()
    ) = GanttAgentChange.plan(task, project(rolesOnly), visible, ctx, target, nextToMe = nextToMe)

    private fun rows(plan: Plan): List<Row> = (plan as Plan.Send).rows

    @Test
    fun selfToMyAgentAddsOnlyMyAgentAtTheEndAndKeepsEveryOtherRow() {
        val p = plan(task(1 to 6.0, 2 to 3.0, 15 to 2.0), writer)
        assertEquals(listOf(Row(1, 6.0), Row(2, 3.0), Row(15, 2.0), Row(11, null)), rows(p))
    }

    @Test
    fun myAgentToSelfTakesOnlyMyAgentOff() {
        val p = plan(task(1 to 6.0, 2 to 3.0, 11 to null), null)
        assertEquals(listOf(Row(1, 6.0), Row(2, 3.0)), rows(p))
    }

    @Test
    fun takingAnAgentOffThatHadWorkWouldChangeTheWorkOfTheTaskAndIsRefused() {
        val p = plan(task(1 to 2.0, 11 to 6.0), null)
        assertEquals(Plan.Refuse(ConflictReason.WORK_WOULD_CHANGE), p)
    }

    @Test
    fun workIsComparedInPersonDaysWhateverTheUnit() {
        // the agent's planning person counts in person days (PT): 0.75 PT = 6 h. Taking it off changes the total.
        val pt = resources.map { if (it.get("id").asInt == 11) resource(11, "$robot Writer-Bot (Ali)", unit = "PT") else it }
        assertEquals(Plan.Refuse(ConflictReason.WORK_WOULD_CHANGE), plan(task(1 to 2.0, 11 to 0.75), null, visible = pt))
        // a null work adds nothing, in any unit
        assertEquals(listOf(Row(1, 2.0)), rows(plan(task(1 to 2.0, 11 to null), null, visible = pt)))
    }

    @Test
    fun theSameAgentOrSelfWithoutAnAgentChangesNothing() {
        assertEquals(Plan.Same, plan(task(1 to 6.0, 11 to null), writer))
        assertEquals(Plan.Same, plan(task(1 to 6.0), null))
    }

    @Test
    fun twoAgentsOfMineAreReducedToTheChosenOne() {
        val p = plan(task(1 to 2.0, 11 to null, 12 to null), writer)
        assertEquals(listOf(Row(1, 2.0), Row(11, null)), rows(p))
    }

    @Test
    fun anAgentRecognisedByItsExactNameCanBeChosenWhenItSitsNextToMe() {
        // 12 is on another task of the project where I (1) am too: it is next to my own planning person (design 4.5 (b))
        val others = listOf(task(1 to 1.0, 12 to null))
        val near = GanttAgentChange.idsNextToMe(others, resources, ctx)
        assertEquals(setOf(1, 12), near)
        val p = plan(task(1 to 2.0), researcher, nextToMe = near)
        assertEquals(listOf(Row(1, 2.0), Row(12, null)), rows(p))
    }

    @Test
    fun anAgentRecognisedOnlyByNameThatSitsNextToNobodyOfMineIsNotOffered() {
        // the name matches, but this planning person is on no task next to me: "Ask your project manager"
        assertEquals(Plan.Refuse(ConflictReason.AGENT_NOT_IN_PROJECT, "Research-Bot"), plan(task(1 to 2.0), researcher))
        val others = listOf(task(2 to 1.0, 12 to null))
        assertEquals(setOf(2, 12), GanttAgentChange.idsInProject(others))
        assertEquals(emptySet<Int>(), GanttAgentChange.idsNextToMe(others, resources, ctx))
        val choice = GanttAgentChange.choices(task(1 to 2.0), project(), resources, ctx, GanttAgentChange.idsInProject(others))
            .first { it.agentId == "AG-0003" }
        assertFalse(choice.enabled)
    }

    @Test
    fun anAgentListedByItsIdNeedsNoNeighbour() {
        assertEquals(listOf(Row(1, 2.0), Row(11, null)), rows(plan(task(1 to 2.0), writer)))
    }

    @Test
    fun morethanFourPeopleAreRefusedAndNothingIsSent() {
        val p = plan(task(1 to 1.0, 2 to 1.0, 13 to 1.0, 15 to 1.0), writer)
        assertEquals(Plan.Refuse(ConflictReason.TOO_MANY_PEOPLE), p)
    }

    @Test
    fun aProjectThatAllowsRolesOnlyRefusesANewPersonRow() {
        assertEquals(Plan.Refuse(ConflictReason.ROLES_ONLY), plan(task(1 to 2.0), writer, rolesOnly = true))
        // an agent who is on the task already stays valid
        assertEquals(Plan.Same, plan(task(1 to 2.0, 11 to null), writer, rolesOnly = true))
    }

    @Test
    fun anAgentWhosePlanningPersonIsNotVisibleIsNotAddedAndNobodyIsCreated() {
        val hidden = resources.filter { it.get("id").asInt != 11 }
        val p = plan(task(1 to 2.0), writer, visible = hidden)
        assertEquals(Plan.Refuse(ConflictReason.AGENT_NOT_IN_PROJECT, "Writer-Bot"), p)
    }

    @Test
    fun aTaskWithoutMyOwnRowIsNotChanged() {
        assertEquals(Plan.Refuse(ConflictReason.CHECK_IN_HOME, "not_on_task"), plan(task(2 to 2.0), writer))
    }

    @Test
    fun aRowTheServerCouldNotSeeWouldBeDroppedSoNothingIsSent() {
        // resource 99 is on the task but not in the list of planning people that I can see
        assertEquals(Plan.Refuse(ConflictReason.CHECK_IN_HOME, "row_not_visible"), plan(task(1 to 2.0, 99 to 1.0), writer))
    }

    @Test
    fun thePrimaryRowNeverChanges() {
        assertEquals(Plan.Refuse(ConflictReason.CHECK_IN_HOME, "primary_changes"), plan(task(11 to null, 1 to 2.0), null))
    }

    @Test
    fun theChoicesAreSelfAndMyActiveAgentsWithTheirReasons() {
        val choices = GanttAgentChange.choices(task(1 to 2.0), project(), resources, ctx)
        // Design-Bot belongs to Sara: not in my list. Writer-Bot can be chosen; Research-Bot is paused.
        assertEquals(listOf("AG-0001", "AG-0003"), choices.map { it.agentId })
        assertTrue(choices.first { it.agentId == "AG-0001" }.enabled)
        val paused = choices.first { it.agentId == "AG-0003" }
        assertFalse(paused.enabled)
        assertEquals(ChoiceNote.PAUSED, paused.note)
    }

    @Test
    fun anAgentThatIsNotInTheProjectIsGreyedWithAReason() {
        val hidden = resources.filter { it.get("id").asInt != 11 }
        val choices = GanttAgentChange.choices(task(1 to 2.0), project(), hidden, ctx)
        val c = choices.first { it.agentId == "AG-0001" }
        assertFalse(c.enabled)
        assertEquals(ChoiceNote.NOT_IN_PROJECT, c.note)
    }

    @Test
    fun aPausedAgentThatIsAlreadyOnTheTaskStaysChoosable() {
        val choices = GanttAgentChange.choices(task(1 to 2.0, 12 to null), project(), resources, ctx)
        val c = choices.first { it.agentId == "AG-0003" }
        assertTrue(c.current)
        assertTrue(c.enabled)
    }

    @Test
    fun theAnswerMustHaveTheRowsThatWereSent() {
        val sent = listOf(Row(1, 6.0), Row(11, null))
        assertTrue(GanttAgentChange.answerMatches(sent, task(1 to 6.0, 11 to null)))
        assertFalse(GanttAgentChange.answerMatches(sent, task(1 to 6.0)))
        assertFalse(GanttAgentChange.answerMatches(sent, task(1 to 5.0, 11 to null)))
        assertNull(GanttAgentChange.plan(task(1 to 2.0), project(), resources, ctx, writer).let { if (it is Plan.Send) null else it })
    }
}
