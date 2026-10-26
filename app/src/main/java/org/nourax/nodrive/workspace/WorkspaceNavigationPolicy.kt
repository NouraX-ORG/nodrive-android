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
}
