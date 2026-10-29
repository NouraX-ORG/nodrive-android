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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.dav.CalendarInfo
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.BuildContext
import org.nourax.nodrive.agenda.logic.BuildOutput
import org.nourax.nodrive.agenda.logic.CalendarMode
import org.nourax.nodrive.agenda.logic.ProjectRelation
import org.nourax.nodrive.agenda.logic.SourceColours
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.logic.WorkingHours
import org.nourax.nodrive.agenda.model.AutoHiddenReason
import org.nourax.nodrive.agenda.model.Category
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.model.ItemKind
import org.nourax.nodrive.agenda.model.ProblemKind
import org.nourax.nodrive.agenda.model.SourceType
import org.nourax.nodrive.agenda.model.WorkKind
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The rules of design 4, 6 and 7 (the core step): defaults, event rules, agents, integrity flags, dedupe, problems of a team. */
class RulesTest {
    private fun modeOf(out: BuildOutput, key: String) = out.sources.first { it.key == key && it.type == SourceType.CALENDAR }.mode

    // ---- defaults (design 6.1) ----------------------------------------------------------------------------------------
    @Test
    fun calendarDefaults() {
        val input = loadScenario("ali")
        val on = input.copy(gantt = input.gantt.copy(taskSync = Fx.obj("gantt/task-sync-on.json")))
        val out = AgendaBuilder.build(on)
        assertEquals("personal", modeOf(out, "personal"))
        assertEquals("personal", modeOf(out, "work")) // own calendars are Personal until the question
        assertEquals("team", modeOf(out, "mara-team_shared_by_mara"))
        assertEquals("info", modeOf(out, "holidays"))
        assertEquals("info", modeOf(out, "contact_birthdays"))
        assertEquals("hidden", modeOf(out, "app-generated--deck--board-10"))
        // NoGantt's own copy of my tasks is hidden automatically, and the person is told why
        assertEquals("hidden", modeOf(out, "ali-task-copy"))
        assertEquals("ali-task-copy", out.taskCopyCalendarUri)
        for (s in out.sources.filter { it.key == "ali-task-copy" || it.key.startsWith("app-generated--") }) {
            assertTrue(s.autoHidden)
            assertTrue(s.autoHiddenReason != AutoHiddenReason.NONE)
        }
    }

    @Test
    fun theOneTimeQuestionListsMyOwnCalendars() {
        val out = AgendaBuilder.build(loadScenario("ali"))
        assertTrue(out.needsPersonalQuestion)
        assertEquals(listOf("personal", "work", "ali-task-copy"), out.ownCalendars.map { it.uri })
    }

    @Test
    fun answeringTheQuestionMakesTheOthersWork() {
        val out = AgendaBuilder.build(loadAliAnswered())
        assertFalse(out.needsPersonalQuestion)
        assertEquals("personal", modeOf(out, "personal"))
        assertEquals("work", modeOf(out, "work"))
        assertEquals(Category.WORK, out.items.byKey("cal:work/call.ics")!!.category)
        assertEquals(Category.PERSONAL, out.items.byKey("cal:personal/dentist.ics")!!.category)
    }

    @Test
    fun skippingKeepsEverythingPersonalAndIsNeverAskedAgain() {
        val settings = SourceSettings().answerPersonalQuestion(emptySet(), listOf("personal", "work"), true)
        val out = AgendaBuilder.build(loadScenario("ali").copy(settings = settings))
        assertFalse(out.needsPersonalQuestion)
        assertEquals("personal", modeOf(out, "work"))
        // the answer survives the settings file
        assertTrue(SourceSettings.fromJson(settings.toJson()).personalAnswered)
    }

    @Test
    fun projectAndBoardDefaults() {
        fun project(id: Int, owner: String, permission: String) = JsonObject().apply {
            addProperty("id", id)
            addProperty("title", "P")
            addProperty("color", "#e11d48")
            addProperty("owner_id", owner)
            addProperty("permission", permission)
        }
        val groups = listOf("org-a")
        val none = JsonArray()
        // I own it: Do + Oversee
        assertEquals(WorkMode.DO_OVERSEE, ProjectRelation.of(project(1, "ali", "admin"), "ali", false, groups, emptySet(), none, false).defaultMode())
        // admin through a real share: Do + Oversee
        assertEquals(WorkMode.DO_OVERSEE, ProjectRelation.of(project(2, "mara", "admin"), "ali", false, groups, emptySet(), none, false).defaultMode())
        // editor or viewer with tasks of mine: Do. Without: Hidden
        assertEquals(WorkMode.DO, ProjectRelation.of(project(1, "mara", "editor"), "ali", false, groups, setOf(1), none, false).defaultMode())
        assertEquals(WorkMode.HIDDEN, ProjectRelation.of(project(3, "mara", "viewer"), "ali", false, groups, setOf(1), none, false).defaultMode())
    }

    @Test
    fun aServerAdminSeesEveryProjectAsAdminButThatIsNotAShare() {
        val project = JsonObject().apply {
            addProperty("id", 7)
            addProperty("title", "Other")
            addProperty("owner_id", "mara")
            addProperty("permission", "admin")
            addProperty("color", "#e11d48")
        }
        val groups = listOf("admin", "org-a")
        fun share(type: String, principal: String) = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("principal_type", type)
                addProperty("principal_id", principal)
                addProperty("permission", "admin")
            })
        }
        // no share to me: seen only as server admin, Hidden (never guessed)
        val relation = ProjectRelation.of(project, "ali", true, groups, emptySet(), JsonArray(), true)
        assertTrue(relation.seenOnlyAsServerAdmin)
        assertFalse(relation.runByRealShare)
        assertEquals(WorkMode.HIDDEN, relation.defaultMode())
        // an admin share to me counts
        assertEquals(WorkMode.DO_OVERSEE, ProjectRelation.of(project, "ali", true, groups, emptySet(), share("user", "ali"), true).defaultMode())
        // a group share to a group of mine counts, to another group it does not
        assertEquals(WorkMode.DO_OVERSEE, ProjectRelation.of(project, "ali", true, groups, emptySet(), share("group", "org-a"), true).defaultMode())
        assertEquals(WorkMode.HIDDEN, ProjectRelation.of(project, "ali", true, groups, emptySet(), share("group", "org-b"), true).defaultMode())
        // a team share cannot be resolved: it counts as not mine
        assertEquals(WorkMode.HIDDEN, ProjectRelation.of(project, "ali", true, groups, emptySet(), share("team", "t1"), true).defaultMode())
    }

    @Test
    fun theStaffAndTheManagerSeeTheirOwnProjects() {
        val ali = AgendaBuilder.build(loadScenario("ali"))
        val projects = ali.sources.filter { it.type == SourceType.PROJECT }.associate { it.name to it.mode }
        assertEquals("do", projects["Website"])
        assertEquals("hidden", projects["Internal"])
        assertFalse(ali.anyOversee)
        val mara = AgendaBuilder.build(loadScenario("mara"))
        // two projects and the board
        assertEquals(3, mara.sources.count { it.type != SourceType.CALENDAR && it.mode == "doOversee" })
        assertTrue(mara.anyOversee)
    }

    @Test
    fun aChosenSettingBeatsTheDefault() {
        val settings = SourceSettings().answerPersonalQuestion(setOf("personal"), listOf("personal", "work", "ali-task-copy"), false)
            .withProject(1, WorkMode.HIDDEN).withBoard(10, WorkMode.HIDDEN).withCalendar("holidays", CalendarMode.HIDDEN)
        val out = AgendaBuilder.build(loadScenario("ali").copy(settings = settings))
        assertTrue(out.items.none { it.home == Home.GANTT || it.home == Home.BOARD })
        assertNull(out.items.byTitle("Local holiday"))
        assertNotNull(out.items.byTitle("Dentist"))
    }

    @Test
    fun theSettingsFileKeepsEveryChoice() {
        val s = SourceSettings(
            workingHours = WorkingHours(3, LocalTime.of(10, 0), LocalTime.of(14, 0)),
            lastView = "week",
            chipAgents = false,
            everOpened = true
        ).withCalendar("work", CalendarMode.WORK).withProject(4, WorkMode.DO_OVERSEE).withBoard(9, WorkMode.HIDDEN)
        val copy = SourceSettings.fromJson(s.toJson())
        assertEquals(s, copy)
        val work = CalendarInfo(uri = "work", mine = true)
        assertEquals(CalendarMode.WORK, copy.calendarMode(work, ""))
        assertEquals(3, copy.workingHours!!.daysMask)
        assertEquals("week", copy.lastView)
        assertTrue(!copy.chipAgents && copy.chipMe && copy.chipTeam)
        assertTrue(copy.everOpened)
    }

    // ---- event rules (design 6.1) ---------------------------------------------------------------------------------------
    @Test
    fun eventRules() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        // free events do not block time
        assertTrue(items.byKey("cal:work/free.ics")!!.transparent)
        assertFalse(items.byKey("cal:work/call.ics")!!.transparent)
        // declined invitations are hidden
        assertNull(items.byTitle("Budget talk"))
        // tentative ones are soft
        assertTrue(items.byKey("cal:mara-team_shared_by_mara/t2.ics")!!.tentative)
        // a shared team calendar: only what I organise or am invited to
        assertNull(items.byTitle("Mara and Olaf"))
        assertNotNull(items.byTitle("Ali's workshop"))
        assertTrue(items.byKey("cal:mara-team_shared_by_mara/t4.ics")!!.organizerIsMe)
        // an all-day event keeps its last day
        val dayOff = items.byKey("cal:work/dayoff.ics")!!
        assertTrue(dayOff.allDay)
        assertEquals(LocalDate.of(2026, 10, 30), dayOff.startDate)
        assertEquals(LocalDate.of(2026, 10, 30), dayOff.endDate)
        // repeating
        assertTrue(items.byKey("cal:work/standup.ics#20261028T093000Z")!!.repeating)
        // information calendars are shown but never block time
        val holiday = items.byTitle("Local holiday")!!
        assertTrue(holiday.info && holiday.transparent)
        assertEquals(Category.INFO, holiday.category)
        // the times are kept in UTC
        val dentist = items.byKey("cal:personal/dentist.ics")!!
        assertEquals(Instant.parse("2026-10-28T10:00:00Z"), dentist.start)
        assertEquals("Check-up, bring the card", dentist.description)
    }

    @Test
    fun everyHomeGivesItsRows() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        assertEquals(33, items.size) // 19 calendar and to-do rows, 10 NoGantt (one only reports a problem), 4 NoBoard
        assertEquals(14, items.count { it.kind == ItemKind.EVENT })
        assertEquals(5, items.count { it.kind == ItemKind.TODO })
        assertEquals(9, items.count { it.kind == ItemKind.GANTT_TASK }) // 8 tasks of mine and the row that only reports a problem
        assertEquals(1, items.count { it.kind == ItemKind.MILESTONE })
        assertEquals(4, items.count { it.kind == ItemKind.CARD })
    }

    @Test
    fun todos() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        assertEquals(LocalDate.of(2026, 10, 30), items.byKey("cal:personal/td1.ics")!!.due)
        val late = items.byKey("cal:personal/td2.ics")!!
        assertTrue(late.late)
        assertEquals(2, late.lateDays)
        assertFalse(items.byKey("cal:personal/td3.ics")!!.hasDate) // goes to "No date"
        val draft = items.byKey("cal:personal/td4.ics")!!
        assertEquals(LocalDate.of(2026, 10, 27), draft.startDate)
        assertEquals(LocalDate.of(2026, 10, 31), draft.due)
        assertEquals(30, draft.percentDone)
        assertNull(items.byKey("cal:personal/td5.ics")) // completed
        val callBack = items.byKey("cal:personal/td6.ics")!!
        assertTrue(callBack.dueToday)
        assertEquals(Home.TASKS, callBack.home)
    }

    // ---- agents (design 4.2) ---------------------------------------------------------------------------------------------
    @Test
    fun agentsOnEventsAndTodos() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        assertEquals("Writer-Bot", items.byKey("cal:work/agent.ics")!!.agent.name)
        assertEquals(WorkKind.MY_AGENT, items.byKey("cal:work/agent.ics")!!.workKind)
        // the category alone is enough when a client dropped the property
        assertEquals("Writer-Bot", items.byKey("cal:work/tagonly.ics")!!.agent.name)
        assertEquals("Writer-Bot", items.byKey("cal:personal/td4.ics")!!.agent.name)
        // an id that is not in the registry is shown as such and flagged, never guessed
        val unknown = items.byKey("cal:work/unknownagent.ics")!!
        assertTrue(unknown.agent.unknown)
        assertEquals("AG-0999", unknown.agent.id)
        assertTrue(unknown.has(ProblemKind.UNKNOWN_AGENT))
        // a retired agent
        val retired = items.byKey("cal:work/retiredagent.ics")!!
        assertTrue(retired.agent.retired)
        assertEquals("Invoice-Bot", retired.agent.name)
        assertTrue(retired.has(ProblemKind.RETIRED_AGENT))
        // a calendar event without an agent is Self
        assertTrue(items.byKey("cal:work/call.ics")!!.agent.isSelf)
    }

    @Test
    fun aMeetingSomeoneElseOrganisesIsAlwaysSelf() {
        val invite = AgendaBuilder.build(loadAliAnswered()).items.byKey("cal:work/invite.ics")!!
        assertTrue(invite.invitation)
        assertTrue(invite.agent.isSelf) // the tag in the file is ignored
        assertEquals(WorkKind.SELF, invite.workKind)
    }

    @Test
    fun theSameInvitationInTwoCalendarsIsOneRow() {
        val items = AgendaBuilder.build(loadAliAnswered()).items
        assertEquals(1, items.count { it.title == "Planning meeting" })
        assertNotNull(items.byKey("cal:work/invite.ics")) // the copy in my own calendar wins
    }

    @Test
    fun agentsWithoutARegistryAreNotGuessed() {
        val input = loadAliAnswered().copy(registry = AgentRegistry.MISSING)
        val item = AgendaBuilder.build(input).items.byKey("cal:work/agent.ics")!!
        assertTrue(item.agent.unknown)
        assertEquals("AG-0001", item.agent.id)
    }

    @Test
    fun anAgentOfAnotherPersonOnMyOwnItemIsNotMine() {
        val input = loadAliAnswered()
        // AG-0002 (Design-Bot) belongs to Sara.
        val records = input.calendar.records.map { if (it.href.endsWith("agent.ics")) it.copy(agentId = "AG-0002") else it }
        val item = AgendaBuilder.build(input.copy(calendar = input.calendar.copy(records = records))).items.byKey("cal:work/agent.ics")!!
        assertTrue(item.agent.unknown)
        assertTrue(item.has(ProblemKind.UNKNOWN_AGENT))
    }

    // ---- integrity (design 4.4, 4.5) -------------------------------------------------------------------------------------
    @Test
    fun theManagerSeesEveryIntegrityProblem() {
        val items = AgendaBuilder.build(loadScenario("mara")).items
        // agent with no person (and no person on the task)
        val orphan = items.byKey("gantt:task:106")!!
        assertTrue(orphan.has(ProblemKind.AGENT_NO_PERSON))
        assertTrue(orphan.has(ProblemKind.NOT_ASSIGNED))
        // agent paired with someone who is not its person
        val wrong = items.byKey("gantt:task:107:ali")!!
        assertTrue(wrong.has(ProblemKind.AGENT_WRONG_PERSON))
        assertEquals(listOf("Design-Bot", "Sara", "Ali"), wrong.problems.first { it.kind == ProblemKind.AGENT_WRONG_PERSON }.args)
        // two agents for the same person
        assertTrue(items.byKey("gantt:task:108:ali")!!.has(ProblemKind.TWO_AGENTS))
        // a planning person that is not linked to an account
        assertTrue(items.byKey("gantt:task:109:sara")!!.has(ProblemKind.NOT_LINKED))
        // a task assigned to a role only
        val role = items.byKey("gantt:task:105")!!
        assertTrue(role.has(ProblemKind.NOT_ASSIGNED))
        assertTrue(role.personName.isEmpty())
        // a person in a project that is not shared with them
        val shared = items.byKey("gantt:notshared:2:sara")!!
        assertEquals(listOf("Sara", "1"), shared.problems.single().args)
        assertNull(items.byKey("gantt:notshared:1:ali")) // shared in the other project
    }

    @Test
    fun theIntegrityAudiencesAreRespected() {
        // Ali does not run the project: the manager's flags are not shown to him ...
        val ali = AgendaBuilder.build(loadScenario("ali")).items
        assertTrue(ali.byKey("gantt:task:107")!!.problems.isEmpty()) // "belongs to Sara, not Ali" is for the manager
        assertNull(ali.byKey("gantt:task:106"))
        // ... but his own problems are: two agents, and an unknown agent
        assertTrue(ali.byKey("gantt:task:108")!!.has(ProblemKind.TWO_AGENTS))
        assertTrue(ali.byKey("gantt:task:113")!!.has(ProblemKind.UNKNOWN_AGENT))
        // the manager does not get "unknown agent": it is for the person
        val mara = AgendaBuilder.build(loadScenario("mara")).items
        assertFalse(mara.byKey("gantt:task:113:ali")!!.has(ProblemKind.UNKNOWN_AGENT))
    }

    @Test
    fun thePersonIsToldWhenHisAgentIsOnATaskWithoutHim() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        assertNull(items.byKey("gantt:task:106")) // it is not Ali's task
        val row = items.byKey("gantt:noperson:106:AG-0001")!!
        assertTrue(row.problemOnly)
        assertEquals(WorkKind.MY_AGENT, row.workKind)
        assertEquals("Writer-Bot", row.agent.name)
        assertEquals(listOf("Writer-Bot", "Orphan writing"), row.problems.single().args)
        assertEquals(1, row.projectId)
        assertEquals(106, row.taskId)
        // it is not his work: no date (so no workload, no clash), and not late
        assertFalse(row.hasDate)
        assertFalse(row.late || row.dueToday)
        // the manager of the project gets the problem on the team row, not this one
        val mara = AgendaBuilder.build(loadScenario("mara")).items
        assertNull(mara.byKey("gantt:noperson:106:AG-0001"))
        assertTrue(mara.byKey("gantt:task:106")!!.has(ProblemKind.AGENT_NO_PERSON))
        // only his own agents concern Ali
        assertTrue(items.filter { it.key.startsWith("gantt:noperson:") }.all { it.key == "gantt:noperson:106:AG-0001" })
    }

    @Test
    fun thePersonIsToldWhenHisAgentIsOnACardNobodyHas() {
        val input = loadScenario("ali")
        val boards = JsonArray()
        for (b in input.deck.boards) {
            val board = b.asJsonObject.deepCopy()
            for (st in board.getAsJsonArray("_stacks")) {
                for (c in st.asJsonObject.getAsJsonArray("cards")) {
                    val card = c.asJsonObject
                    if (card.get("id").asInt == 508) {
                        card.add("labels", JsonArray().apply { add(JsonObject().apply { addProperty("id", 9); addProperty("title", "$ROBOT Writer-Bot") }) })
                    }
                }
            }
            boards.add(board)
        }
        val items = AgendaBuilder.build(input.copy(deck = input.deck.copy(boards = boards))).items
        assertNull(items.byTitle("Unassigned backlog")) // the card is not his
        val row = items.byKey("deck:noperson:508:AG-0001")!!
        assertTrue(row.problemOnly)
        assertEquals(Home.BOARD, row.home)
        assertEquals(508, row.cardId)
        assertFalse(row.hasDate)
        assertEquals(listOf("Writer-Bot", "Unassigned backlog"), row.problems.single().args)
    }

    @Test
    fun aPairingSentenceNeverShowsAUserIdOrAnotherOrganisationsAgent() {
        val uuid = "00000000-0000-4000-8000-00000000000a"
        // Design-Bot (AG-0002, Sara's) paired with Ali, its owner is an id that no visible planning person has
        val registry = AgentRegistry.parse(
            Fx.text("agent-registry.json").replace("\"person\": \"sara\"", "\"person\": \"$uuid\"")
                .replace("\"nogantt_resource_ids\": []", "\"nogantt_resource_ids\": [12]").toByteArray()
        )
        val items = AgendaBuilder.build(loadScenario("mara").copy(registry = registry)).items
        for (item in items) {
            for (p in item.problems) assertTrue(p.args.none { it.contains(uuid) })
            assertFalse(item.agent.owner.contains(uuid))
        }
    }

    @Test
    fun anAgentOfAnotherOrganisationIsNotNamed() {
        val registry = AgentRegistry.parse(Fx.bytes("agent-registry.json"))
        val ctx = BuildContext(
            me = "ali", myName = "Ali", myAddresses = emptyList(), myGroups = emptyList(), serverAdmin = false, registry = registry,
            settings = SourceSettings(), today = TODAY, zone = UTC, now = NOW
        )
        // Design-Bot belongs to Sara, in my organisation: named
        assertEquals("Design-Bot", ctx.refFor(registry.byId("AG-0002")!!, "Sara").name)
        // Olaf-Bot belongs to another organisation: not named
        val hidden = ctx.refFor(registry.byId("AG-0005")!!, "Olaf")
        assertTrue(hidden.otherOrganisation)
        assertEquals("Olaf", hidden.owner)
        assertEquals("", ctx.agentNameInText(registry.byId("AG-0005")!!))
        assertEquals("Writer-Bot", ctx.agentNameInText(registry.byId("AG-0001")!!))
    }

    // ---- problems of a manager's team (design 6.3) ---------------------------------------------------------------------
    @Test
    fun problemsOfAManagersTeam() {
        val items = AgendaBuilder.build(loadScenario("mara")).items
        assertEquals(21, items.size)
        assertEquals(13, items.count { it.workKind == WorkKind.TEAM && it.problems.isNotEmpty() })
        // Mara has no work of her own in the fixtures
        assertTrue(items.all { it.workKind == WorkKind.TEAM })
        val late = items.byKey("gantt:task:103:sara")!!
        assertTrue(late.has(ProblemKind.LATE))
        assertEquals(listOf("2"), late.problems.first { it.kind == ProblemKind.LATE }.args)
        val blocked = items.first { it.has(ProblemKind.BLOCKED) }
        assertTrue(blocked.problems.first { it.kind == ProblemKind.BLOCKED }.args.contains("Banner design"))
        assertTrue(items.byKey("gantt:task:112:ali")!!.has(ProblemKind.DUE_TODAY))
        // someone overbooked (NoGantt's own capacity check)
        val overbooked = items.first { it.has(ProblemKind.OVERBOOKED) }
        assertEquals("Ali", overbooked.problems.single().args.single())
        // a task without a problem is not a problem row
        assertTrue(items.byKey("gantt:task:101:ali")!!.problems.isEmpty())
        assertEquals("Writer-Bot", items.byKey("gantt:task:101:ali")!!.agent.name)
        // not a team row at all: a finished task
        assertNull(items.byKey("gantt:task:110:ali"))
    }

    @Test
    fun blockedAndLateOnMyOwnRows() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        val blocked = items.first { it.blocked }
        assertEquals("Banner design", blocked.blockedBy)
        assertTrue(items.byKey("gantt:task:112")!!.dueToday)
        assertFalse(items.byKey("gantt:task:102")!!.late)
    }

    @Test
    fun aGanttTaskWithABoardCardIsOneRowWithABadge() {
        val items = AgendaBuilder.build(loadScenario("ali")).items
        val task = items.byKey("gantt:task:111")!!
        assertTrue(task.boardBadge)
        assertEquals(501, task.deckCardId)
        assertNull(items.byKey("deck:card:501"))
    }

    @Test
    fun aViewerCannotChangeEvenHisOwnTask() {
        val input = loadScenario("ali")
        val projects = JsonArray()
        for (p in input.gantt.projects) {
            val o = p.asJsonObject.deepCopy()
            if (o.get("id").asInt == 1) o.addProperty("permission", "viewer")
            projects.add(o)
        }
        val out = AgendaBuilder.build(input.copy(gantt = input.gantt.copy(projects = projects)))
        val task = out.items.byKey("gantt:task:102")!!
        assertTrue(task.readOnly)
        assertTrue(task.readOnlyReason != org.nourax.nodrive.agenda.model.ReadOnlyReason.NONE)
    }

    @Test
    fun theWorkingPatternComesFromNoGanttOrFromTheDefault() {
        val input = loadScenario("ali")
        val hours = AgendaBuilder.workingHours(input)
        assertEquals(31, hours.daysMask)
        assertEquals(LocalTime.of(9, 0), hours.from)
        // a chosen pattern beats NoGantt's
        val own = WorkingHours(3, LocalTime.of(10, 0), LocalTime.of(14, 0))
        assertEquals(own, AgendaBuilder.workingHours(input.copy(settings = SourceSettings(workingHours = own))))
        // without a linked planning person: Monday to Friday, 09:00 - 17:00
        val none = input.copy(gantt = input.gantt.copy(resources = JsonArray()))
        assertEquals(WorkingHours(), AgendaBuilder.workingHours(none))
    }

    @Test
    fun serverColoursNeverReachTheScreen() {
        assertEquals("#2563EB", SourceColours.display("#" + "00" + "82c9"))
        assertEquals("#2563EB", SourceColours.display("#" + "00" + "679E"))
        assertEquals("#2563EB", SourceColours.display("#" + "00" + "82c9FF"))
        assertEquals("#795AAF", SourceColours.display("#795AAFFF"))
        assertEquals("#336699", SourceColours.display("369"))
        assertEquals("#2563EB", SourceColours.display(""))
        assertEquals("#2563EB", SourceColours.display("not a colour"))
    }
}
