/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.store.InMemoryAgendaStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Privacy (plan 1.9, builder's call 11): the app log goes into problem reports, so NoAgenda never logs titles, descriptions,
 * attendees, names or addresses, and never stores a password or a token.
 */
class AgendaPrivacyTest {
    private val lines = CopyOnWriteArrayList<String>()
    private val secret = "planted-secret-" + "0123456789abcdef"

    @Before
    fun capture() {
        AgendaLog.sink = { level, tag, message -> lines += "$level $tag $message" }
    }

    @After
    fun release() {
        AgendaLog.sink = null
    }

    /** The transport adds the credential, as the real one does; the fake server records it. */
    private class AuthorisingHttp(private val inner: AgendaHttp, private val secret: String) : AgendaHttp {
        override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResult =
            inner.send(method, path, headers + ("Authorization" to "Basic $secret"), body, contentType)
    }

    private val planted = listOf(
        "Dentist", "Client call", "Check-up, bring the card", "ali@example.test", "mara@example.test", "Banner design", "Writer-Bot",
        "Ali", "Sara", "Mara", "Team lunch", "Landing page card"
    )

    @Test
    fun theLogHasNoTitlesNamesAddressesOrCredentials() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        // a failing calendar and a failing project make the code write its error lines
        http.route("REPORT", "remote.php/dav/calendars/ali/work/", 500, "")
        http.route("GET", "ocs/v2.php/apps/nc_gantt/api/v1/projects/1/tasks", 500, "")
        http.route("GET", "index.php/apps/deck/api/v1.0/boards/10/stacks", 500, "")
        val store = InMemoryAgendaStore()
        val agenda = AccountAgenda("ali@https://drive.example.test", AuthorisingHttp(http, secret), store, FixedAgendaClock(NOW) as AgendaClock)
        agenda.refresh(manual = true)
        agenda.updateSettings { it.copy(everOpened = true) }

        assertTrue("the code wrote some lines", lines.isNotEmpty())
        val log = lines.joinToString("\n")
        for (value in planted + secret + "Basic") {
            assertFalse("the log must not hold \"$value\"", log.contains(value))
        }
        // what is saved on the phone holds no credential either
        val saved = store.dump()
        assertFalse(saved.contains(secret))
        assertFalse(saved.contains("Authorization"))
        assertFalse(saved.contains("Basic "))
        assertFalse(saved.contains("password", ignoreCase = true))
        // the fake did receive the credential (so the test could have found it)
        assertTrue(http.requests.all { it.headers["Authorization"] == "Basic $secret" })
    }

    @Test
    fun noRowStoresTheRawIcsOrXml() {
        val http = FakeHttp()
        routeScenario(http, "ali")
        val store = InMemoryAgendaStore()
        AccountAgenda("ali@https://drive.example.test", http, store, FixedAgendaClock(NOW)).refresh(manual = true)
        val saved = store.dump()
        assertFalse(saved.contains("BEGIN:VCALENDAR"))
        assertFalse(saved.contains("<d:multistatus"))
    }
}
