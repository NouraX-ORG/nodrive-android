/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRoutingTest {
    private val base = "https://drive.nourax.org"

    private fun route(url: String?) = WorkspaceLinkRouter.route(url, base)

    private fun routeApp(href: String?) = WorkspaceLinkRouter.routeApp(href, base)

    private fun decide(url: String) = WorkspaceNavigationPolicy.decide(url, base)

    @Test
    fun notificationLinksOfTheOwnServerOpenInTheApp() {
        assertEquals(WorkspaceRoute.IN_APP, route("https://drive.nourax.org/apps/spreed/call/x"))
        assertEquals(WorkspaceRoute.IN_APP, route(" https://drive.nourax.org/apps/mail/ "))
    }

    @Test
    fun otherLinksKeepTheirOldBehaviour() {
        assertEquals(WorkspaceRoute.EXTERNAL, route("https://example.org/x"))
        assertEquals(WorkspaceRoute.EXTERNAL, route("https://drive.nourax.org@evil.example/"))
        assertEquals(WorkspaceRoute.EXTERNAL, route("http://drive.nourax.org/x"))
        assertEquals(WorkspaceRoute.EXTERNAL, route("mailto:a@b.example"))
    }

    @Test
    fun emptyOrInvalidLinksAreIgnored() {
        assertEquals(WorkspaceRoute.IGNORE, route(null))
        assertEquals(WorkspaceRoute.IGNORE, route(""))
        assertEquals(WorkspaceRoute.IGNORE, route("   "))
        assertEquals(WorkspaceRoute.IGNORE, route("not a url"))
    }

    @Test
    fun workspaceEntriesOfTheOwnOriginAreSignedIn() {
        assertEquals(WorkspaceAppRoute.SIGNED_IN, routeApp("https://drive.nourax.org/apps/mail/"))
        assertEquals(WorkspaceAppRoute.SIGNED_IN, routeApp("https://drive.nourax.org:443/apps/mail/"))
    }

    @Test
    fun workspaceEntriesOfAnotherHttpsOriginGoToACustomTabWithoutAnAccount() {
        assertEquals(WorkspaceAppRoute.CUSTOM_TAB, routeApp("https://mail.nourax.org/"))
        assertEquals(WorkspaceAppRoute.CUSTOM_TAB, routeApp("https://drive.nourax.org:8443/apps/mail/"))
        assertEquals(WorkspaceAppRoute.CUSTOM_TAB, routeApp("https://example.org/x?y=1"))
    }

    @Test
    fun workspaceEntriesThatAreNotPlainHttpsOpenNowhere() {
        listOf(
            "http://mail.nourax.org/", "http://drive.nourax.org/", "mailto:a@b.example", "javascript:alert(1)",
            "https://drive.nourax.org@evil.example/", "https:///x", "", "not a url", null
        ).forEach { assertEquals(it, WorkspaceAppRoute.IGNORE, routeApp(it)) }
    }

    @Test
    fun webViewLoadsOwnPagesInPlace() {
        assertEquals(WorkspaceDecision.LOAD, decide("https://drive.nourax.org/apps/mail/"))
        assertEquals(WorkspaceDecision.RELOGIN, decide("https://drive.nourax.org/index.php/login?redirect_url=/x"))
        assertEquals(WorkspaceDecision.RELOGIN, decide("https://drive.nourax.org/apps/user_oidc/login/1"))
        assertEquals(WorkspaceDecision.LOGOUT, decide("https://drive.nourax.org/logout?requesttoken=x"))
    }

    @Test
    fun webViewSendsOtherOriginsToACustomTab() {
        assertEquals(WorkspaceDecision.CUSTOM_TAB, decide("https://evil.example/"))
        assertEquals(WorkspaceDecision.CUSTOM_TAB, decide("http://drive.nourax.org/"))
        assertEquals(WorkspaceDecision.CUSTOM_TAB, decide("https://drive.nourax.org:8443/"))
        assertEquals(WorkspaceDecision.CUSTOM_TAB, decide("https://drive.nourax.org@evil.example/"))
    }

    @Test
    fun webViewHandsMailAndPhoneToTheSystemAndBlocksTheRest() {
        assertEquals(WorkspaceDecision.SYSTEM, decide("mailto:a@b.example"))
        assertEquals(WorkspaceDecision.SYSTEM, decide("tel:+4912345"))
        listOf("intent://x#Intent;end", "javascript:alert(1)", "file:///etc/hosts", "data:text/html,x", "nodrive://x", "", "a b")
            .forEach { assertEquals(it, WorkspaceDecision.BLOCK, decide(it)) }
    }
}
