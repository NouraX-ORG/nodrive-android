/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkspaceCookiesTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    private fun cookies(vararg headers: String) = WorkspaceCookies.fromSetCookieHeaders(headers.toList(), now)

    @Test
    fun lastValueOfTheSameCookieWins() {
        val result = cookies(
            "ocabc123def4=first; path=/; HttpOnly; SameSite=Lax",
            "oc_sessionPassphrase=pass; path=/; HttpOnly",
            "ocabc123def4=second; path=/; HttpOnly; SameSite=Lax"
        )
        assertEquals(listOf("oc_sessionPassphrase", "ocabc123def4"), result.map { it.name })
        assertTrue(result.last().setCookie.startsWith("ocabc123def4=second"))
    }

    @Test
    fun domainAttributeIsRemovedSoCookiesAreHostOnly() {
        val result = cookies("ocabc123def4=v; path=/; Domain=.nourax.org; Secure; HttpOnly; SameSite=Lax")
        assertEquals("ocabc123def4=v; path=/; Secure; HttpOnly; SameSite=Lax", result.single().setCookie)
    }

    @Test
    fun deletionCookiesAreDropped() {
        val result = cookies(
            "nc_username=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/",
            "nc_token=; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/",
            "nc_session_id=x; Max-Age=-1; path=/",
            "ocabc123def4=v; path=/"
        )
        assertEquals(listOf("ocabc123def4"), result.map { it.name })
    }

    @Test
    fun aDeletionAfterASetRemovesTheCookie() {
        assertTrue(cookies("a=1; path=/", "a=deleted; Max-Age=0; path=/").isEmpty())
    }

    @Test
    fun futureExpiryIsKeptAndPastExpiryIsDropped() {
        assertEquals(1, cookies("a=1; expires=Wed, 30 Dec 2026 00:00:00 GMT; path=/").size)
        assertEquals(0, cookies("a=1; expires=Mon, 01 Jan 2024 00:00:00 GMT; path=/").size)
    }

    @Test
    fun hostPrefixedCookiesKeepSecureAndRootPath() {
        val cookie = cookies(
            "__Host-nc_sameSiteCookielax=true; path=/; httponly; secure; expires=Fri, 01 Jan 2038 00:00:00 GMT; SameSite=lax"
        ).single()
        assertEquals("/", cookie.path)
        assertTrue(cookie.setCookie.contains("secure"))
        assertFalse(cookie.setCookie.contains("Domain", ignoreCase = true))
    }

    @Test
    fun sameNameOnDifferentPathsIsKeptTwice() {
        assertEquals(2, cookies("a=1; path=/", "a=2; path=/cloud").size)
    }

    @Test
    fun sessionNeedsThePassphraseAndAnotherOcCookie() {
        assertTrue(WorkspaceCookies.hasSession(cookies("oc_sessionPassphrase=p; path=/", "ocabc123def4=v; path=/")))
        assertFalse(WorkspaceCookies.hasSession(cookies("ocabc123def4=v; path=/")))
        assertFalse(WorkspaceCookies.hasSession(cookies("oc_sessionPassphrase=p; path=/")))
        assertFalse(
            WorkspaceCookies.hasSession(cookies("oc_sessionPassphrase=p; path=/", "nc_sameSiteCookielax=true; path=/"))
        )
    }

    @Test
    fun malformedHeadersAreIgnored() {
        assertTrue(cookies("", "novalue", "=x").isEmpty())
    }
}
