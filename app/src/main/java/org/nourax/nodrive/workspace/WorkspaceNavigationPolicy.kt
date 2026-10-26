/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import java.util.Locale

/** What the in-app web view does with a main-frame navigation (design rules 2 to 4). */
enum class WorkspaceDecision { LOAD, RELOGIN, LOGOUT, CUSTOM_TAB, SYSTEM, BLOCK }

object WorkspaceNavigationPolicy {
    private val webSchemes = setOf("http", "https")
    private val systemSchemes = setOf("mailto", "tel")

    fun decide(url: String, base: String): WorkspaceDecision {
        val uri = WorkspaceOrigin.parse(url) ?: return WorkspaceDecision.BLOCK
        return when {
            WorkspaceOrigin.isSameOrigin(url, base) -> when {
                WorkspaceOrigin.isLoginPath(url, base) -> WorkspaceDecision.RELOGIN
                WorkspaceOrigin.isLogoutPath(url, base) -> WorkspaceDecision.LOGOUT
                else -> WorkspaceDecision.LOAD
            }
            uri.scheme?.lowercase(Locale.ROOT) in webSchemes -> WorkspaceDecision.CUSTOM_TAB
            uri.scheme?.lowercase(Locale.ROOT) in systemSchemes -> WorkspaceDecision.SYSTEM
            else -> WorkspaceDecision.BLOCK
        }
    }

    /**
     * True for a web page of another origin. A page whose load the web view started without asking
     * shouldOverrideUrlLoading (a form posted from the page) must not be shown inside the app.
     */
    fun isForeignWebPage(url: String?, base: String): Boolean {
        if (url.isNullOrEmpty()) return false
        return when (decide(url, base)) {
            WorkspaceDecision.CUSTOM_TAB -> true
            WorkspaceDecision.BLOCK -> url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
            else -> false
        }
    }

    /** The page may only open for the account that is still the current one, and only at the account's own server. */
    fun mayStart(requestedAccount: String?, currentAccount: String?, url: String, base: String): Boolean =
        currentAccount != null && currentAccount == requestedAccount && decide(url, base) == WorkspaceDecision.LOAD

    /** A new sign-in only when none has just happened: a session that dies at once must not loop. */
    fun mayRelogin(sessionStartedAtMillis: Long?, nowMillis: Long): Boolean =
        sessionStartedAtMillis == null || nowMillis - sessionStartedAtMillis >= RELOGIN_MIN_INTERVAL_MILLIS

    const val RELOGIN_MIN_INTERVAL_MILLIS = 60_000L
}
