/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nourax.nodrive.agenda.model.AgentRef
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.registry.DriveRegistrySource
import org.nourax.nodrive.agenda.registry.RegistrySources
import org.nourax.nodrive.agenda.registry.RegistryStatus

class AgentRegistryTest {
    private fun fixture() = AgentRegistry.parse(Fx.bytes("agent-registry.json"))

    @Test
    fun theFixtureParsesWithSixAgents() {
        val registry = fixture()
        assertEquals(RegistryStatus.OK, registry.status)
        assertEquals(6, registry.agents.size)
        assertEquals(0, registry.skipped)
        assertEquals("Writer-Bot", registry.byId("AG-0001")?.name)
        assertTrue(registry.byId("AG-0003")!!.isPaused)
        assertTrue(registry.byId("AG-0004")!!.isRetired)
        assertNull(registry.byId("AG-9999"))
    }

    @Test
    fun theBundledPlaceholderFileParsesToo() {
        val registry = AgentRegistry.parse(Fx.bytes("placeholder-registry.json"))
        assertEquals(RegistryStatus.OK, registry.status)
        assertEquals(4, registry.agents.size)
        assertEquals(2, registry.ofPerson("00000000-0000-4000-8000-00000000000A").size) // case does not matter
        assertEquals(40.0, registry.agents.first().capacityHoursPerWeek, 0.0)
        assertEquals("paused", registry.byId("AG-0004")!!.status)
        assertEquals("agent:AG-0001", registry.agents.first().calendarTag)
    }

    @Test
    fun lookupsByLabelTagAndResourceId() {
        val registry = fixture()
        assertEquals("AG-0001", registry.byLabel("$ROBOT Writer-Bot")?.id)
        assertNull(registry.byLabel("$ROBOT Writer-Bot ")) // a different title
        assertEquals("Design-Bot", registry.byTag("agent:AG-0002")?.name)
        assertEquals("AG-0001", registry.byResourceId(11)?.id)
        assertNull(registry.byResourceId(12))
        assertEquals(3, registry.ofPerson("ali").size)
        assertEquals(setOf("other-org"), registry.organisationsOf("olaf"))
    }

    @Test
    fun aLabelWithADifferentUnicodeFormStillMatches() {
        val composed = "Café-Bot"
        val decomposed = "Café-Bot"
        val json = """{"schema":"nourax-agent-registry","version":1,"agents":[
            {"id":"AG-1","name":"$composed","person":"ali","status":"active","organisation":"o","links":{"noboard_label":"$composed"}}]}"""
        val registry = AgentRegistry.parse(json.toByteArray())
        assertNotNull(registry.byLabel(decomposed))
    }

    @Test
    fun badSchemaOrVersionIsInvalid() {
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse("not json".toByteArray()).status)
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse("[]".toByteArray()).status)
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse("""{"schema":"other","version":1,"agents":[]}""".toByteArray()).status)
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse("""{"schema":"nourax-agent-registry","version":2,"agents":[]}""".toByteArray()).status)
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse("""{"schema":"nourax-agent-registry","version":1}""".toByteArray()).status)
        assertEquals(RegistryStatus.MISSING, AgentRegistry.MISSING.status)
    }

    @Test
    fun incompleteAgentsAreSkippedAndCounted() {
        val json = """{"schema":"nourax-agent-registry","version":1,"agents":[
            {"id":"AG-1","name":"Ok","person":"ali","status":"active","organisation":"o"},
            {"id":"","name":"No id","person":"ali","status":"active","organisation":"o"},
            {"id":"AG-3","name":"","person":"ali","status":"active","organisation":"o"},
            {"id":"AG-4","name":"No person","person":"","status":"active","organisation":"o"},
            {"id":"AG-5","name":"Bad status","person":"ali","status":"sleeping","organisation":"o"},
            {"id":"AG-6","name":"No org","person":"ali","status":"active","organisation":""},
            "not an object"]}"""
        val registry = AgentRegistry.parse(json.toByteArray())
        assertEquals(RegistryStatus.OK, registry.status)
        assertEquals(1, registry.agents.size)
        assertEquals(6, registry.skipped)
    }

    @Test
    fun aRepeatedIdIsSkipped() {
        val json = """{"schema":"nourax-agent-registry","version":1,"agents":[
            {"id":"AG-1","name":"First","person":"ali","status":"active","organisation":"o"},
            {"id":"AG-1","name":"Second","person":"ali","status":"active","organisation":"o"}]}"""
        val registry = AgentRegistry.parse(json.toByteArray())
        assertEquals(1, registry.agents.size)
        assertEquals("First", registry.agents.first().name)
        assertEquals(1, registry.skipped)
    }

    @Test
    fun aFileOverOneMegabyteIsInvalid() {
        val big = ByteArray(AgentRegistry.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(RegistryStatus.INVALID, AgentRegistry.parse(big).status)
    }

    @Test
    fun referencesCarryTheStatus() {
        val registry = fixture()
        val active = AgentRegistry.refFor(registry.byId("AG-0001")!!)
        assertTrue(!active.isSelf && !active.unknown && !active.retired && !active.paused)
        assertEquals("Writer-Bot", active.name)
        assertTrue(AgentRegistry.refFor(registry.byId("AG-0004")!!).retired)
        assertTrue(AgentRegistry.refFor(registry.byId("AG-0003")!!).paused)
        val unknown = AgentRegistry.unknownRef("AG-0001")
        assertTrue(unknown.unknown && !unknown.isSelf)
        assertTrue(AgentRef.SELF.isSelf)
    }

    @Test
    fun thePlanningPersonNameIsExact() {
        assertEquals("$ROBOT Writer-Bot (Ali)", AgentRegistry.planningPersonName("Writer-Bot", "Ali"))
    }

    @Test
    fun theDriveSourceAsksForTheUsersOwnFile() {
        val http = FakeHttp()
        http.routeFile("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json", 200, "agent-registry.json")
        val fetched = DriveRegistrySource(http, "ali").fetch(null)
        assertEquals(200, fetched.status)
        assertEquals(6, AgentRegistry.parse(fetched.body!!).agents.size)
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests.first().method)
    }

    @Test
    fun theUserIdIsEncodedInThePath() {
        val http = FakeHttp()
        DriveRegistrySource(http, "a b/c").fetch(null)
        assertEquals("remote.php/dav/files/a%20b%2Fc/NoAgenda/agent-registry.json", http.requests.first().path)
    }

    @Test
    fun theDriveSourceAnswers304And404() {
        val http = FakeHttp()
        http.route("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json", 304, "")
        val sent = DriveRegistrySource(http, "ali").fetch("\"abc\"")
        assertTrue(sent.notModified)
        assertEquals("\"abc\"", http.requests.first().headers["If-None-Match"])
        http.route("GET", "remote.php/dav/files/ali/NoAgenda/agent-registry.json", 404, "")
        assertEquals(404, DriveRegistrySource(http, "ali").fetch(null).status)
        http.offline = true
        assertTrue(DriveRegistrySource(http, "ali").fetch(null).networkError)
    }

    @Test
    fun theSourceIsOneSwappableFactory() {
        // Today the registry is the person's own file; the agents app address is empty until it exists.
        assertEquals("", RegistrySources.AGENTS_APP_REGISTRY_URL)
        assertTrue(RegistrySources.create(FakeHttp(), "ali") is DriveRegistrySource)
        assertFalse(HttpResult(404).ok)
    }
}
