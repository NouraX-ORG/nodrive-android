/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOriginTest {
    private val base = "https://drive.nourax.org"

    @Test
    fun sameOriginAccepted() {
        listOf(
            "https://drive.nourax.org/apps/mail/",
            "https://DRIVE.nourax.org/x",
            "https://drive.nourax.org:443/x",
            "https://drive.nourax.org"
        ).forEach { assertTrue(it, WorkspaceOrigin.isSameOrigin(it, base)) }
    }

    @Test
    fun otherOriginsRefused() {
        listOf(
            "https://evil.example/",
            "http://drive.nourax.org/",
            "https://drive.nourax.org:8443/",
            "https://drive.nourax.org@evil.example/",
            "https://user@drive.nourax.org/",
            "https://drive.nourax.org.evil.example/",
            "https://sub.drive.nourax.org/",
            "https://nourax.org/",
            "https://drive.nourax.org./",
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///etc/hosts",
            "intent://x",
            "",
            "not a url"
        ).forEach { assertFalse(it, WorkspaceOrigin.isSameOrigin(it, base)) }
        assertFalse(WorkspaceOrigin.isSameOrigin(null, base))
    }

    @Test
    fun portsOfAHttpBase() {
        val local = "http://10.0.2.2:8080"
        assertTrue(WorkspaceOrigin.isSameOrigin("http://10.0.2.2:8080/x", local))
        assertFalse(WorkspaceOrigin.isSameOrigin("http://10.0.2.2:8081/x", local))
        assertFalse(WorkspaceOrigin.isSameOrigin("http://10.0.2.2/x", local))
        assertFalse(WorkspaceOrigin.isSameOrigin("https://10.0.2.2:8080/x", local))
    }

    @Test
    fun unusableBaseNeverMatches() {
        assertFalse(WorkspaceOrigin.isSameOrigin("https://drive.nourax.org/", ""))
        assertFalse(WorkspaceOrigin.isSameOrigin("https://drive.nourax.org/", "ftp://drive.nourax.org"))
        assertNull(WorkspaceOrigin.origin("not a url"))
    }

    @Test
    fun loginPaths() {
        listOf(
            "https://drive.nourax.org/login",
            "https://drive.nourax.org/login?redirect_url=%2Fapps%2Fmail",
            "https://drive.nourax.org/index.php/login?redirect_url=/apps/mail",
            "https://drive.nourax.org/apps/user_oidc/login/1",
            "https://drive.nourax.org/index.php/apps/user_oidc/login/1?x=y",
            "https://drive.nourax.org/apps/../login"
        ).forEach { assertTrue(it, WorkspaceOrigin.isLoginPath(it, base)) }
        listOf(
            "https://drive.nourax.org/apps/mail/login-help",
            "https://drive.nourax.org/loginx",
            "https://drive.nourax.org/apps/mail/",
            "https://evil.example/login"
        ).forEach { assertFalse(it, WorkspaceOrigin.isLoginPath(it, base)) }
    }

    @Test
    fun loginPathBelowAWebroot() {
        val cloud = "https://h.example/cloud"
        assertTrue(WorkspaceOrigin.isLoginPath("https://h.example/cloud/index.php/login", cloud))
        assertTrue(WorkspaceOrigin.isLoginPath("https://h.example/cloud/login", cloud))
        assertFalse(WorkspaceOrigin.isLoginPath("https://h.example/login", cloud))
        assertTrue(WorkspaceOrigin.isLogoutPath("https://h.example/cloud/logout?requesttoken=x", cloud))
    }

    @Test
    fun logoutPaths() {
        assertTrue(WorkspaceOrigin.isLogoutPath("https://drive.nourax.org/logout?requesttoken=abc", base))
        assertTrue(WorkspaceOrigin.isLogoutPath("https://drive.nourax.org/index.php/logout", base))
        assertFalse(WorkspaceOrigin.isLogoutPath("https://drive.nourax.org/apps/mail/logout-help", base))
        assertFalse(WorkspaceOrigin.isLogoutPath("https://evil.example/logout", base))
    }

    @Test
    fun resolveAgainstTheOrigin() {
        val cloud = "https://h.example/cloud"
        assertEquals("https://h.example/cloud/apps/mail/", WorkspaceOrigin.resolve("/cloud/apps/mail/", cloud))
        assertEquals("https://drive.nourax.org/apps/x", WorkspaceOrigin.resolve("https://drive.nourax.org/apps/x", base))
        assertNull(WorkspaceOrigin.resolve("https://evil.example/apps/x", base))
        assertNull(WorkspaceOrigin.resolve("//evil.example/x", base))
        assertNull(WorkspaceOrigin.resolve("apps/mail", base))
        assertNull(WorkspaceOrigin.resolve("", base))
        assertNull(WorkspaceOrigin.resolve(null, base))
    }
}
