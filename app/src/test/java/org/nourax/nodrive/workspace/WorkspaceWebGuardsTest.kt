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

class WorkspaceWebGuardsTest {
    private val base = "https://drive.nourax.org"
    private val account = "citest@drive.nourax.org"
    private val minInterval = WorkspaceNavigationPolicy.RELOGIN_MIN_INTERVAL_MILLIS

    @Test
    fun aFirstSignInIsAlwaysAllowed() {
        assertTrue(WorkspaceNavigationPolicy.mayRelogin(null, 5L))
    }

    @Test
    fun aSessionThatDiesWithinTheIntervalDoesNotSignInAgain() {
        assertFalse(WorkspaceNavigationPolicy.mayRelogin(1_000L, 1_000L))
        assertFalse(WorkspaceNavigationPolicy.mayRelogin(1_000L, 1_000L + minInterval - 1))
        assertFalse(WorkspaceNavigationPolicy.mayRelogin(10_000L, 5_000L))
    }

    @Test
    fun anOldSessionMaySignInAgain() {
        assertTrue(WorkspaceNavigationPolicy.mayRelogin(1_000L, 1_000L + minInterval))
    }

    @Test
    fun theAccountMustStillBeTheCurrentOne() {
        val url = "$base/apps/mail/"
        assertTrue(WorkspaceNavigationPolicy.mayStart(account, account, url, base))
        assertFalse(WorkspaceNavigationPolicy.mayStart(account, "other@drive.nourax.org", url, base))
        assertFalse(WorkspaceNavigationPolicy.mayStart(account, null, url, base))
        assertFalse(WorkspaceNavigationPolicy.mayStart(null, null, url, base))
    }

    @Test
    fun aStartUrlOfAnotherOriginOrALoginPageDoesNotStart() {
        assertFalse(WorkspaceNavigationPolicy.mayStart(account, account, "https://evil.example/", base))
        assertFalse(WorkspaceNavigationPolicy.mayStart(account, account, "$base/login", base))
        assertFalse(WorkspaceNavigationPolicy.mayStart(account, account, "$base@evil.example/", base))
    }

    @Test
    fun foreignWebPagesAreRecognisedWhateverStartedTheLoad() {
        listOf("https://evil.example/post", "http://drive.nourax.org/x", "https://drive.nourax.org:8443/", "HTTPS://EVIL.EXAMPLE/")
            .forEach { assertTrue(it, WorkspaceNavigationPolicy.isForeignWebPage(it, base)) }
        assertTrue(WorkspaceNavigationPolicy.isForeignWebPage("https://evil.example/a|b", base))
    }

    @Test
    fun ownPagesAndNonWebUrlsAreNotForeign() {
        listOf("https://drive.nourax.org/apps/mail/", "https://drive.nourax.org/login", "about:blank", "data:text/html,x", "", null)
            .forEach { assertFalse(it.toString(), WorkspaceNavigationPolicy.isForeignWebPage(it, base)) }
        assertEquals(false, WorkspaceNavigationPolicy.isForeignWebPage("javascript:alert(1)", base))
    }
}
