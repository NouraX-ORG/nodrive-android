/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

/** Where a link of the notification list opens (design rule 9). */
enum class WorkspaceRoute { IN_APP, EXTERNAL, IGNORE }

/** Where a Workspace menu entry opens: signed in inside the app, in a Custom Tab without any account, or nowhere. */
enum class WorkspaceAppRoute { SIGNED_IN, CUSTOM_TAB, IGNORE }

object WorkspaceLinkRouter {
    /** Only an entry of the account's own origin may be signed in; an https entry elsewhere gets no credential at all. */
    fun routeApp(href: String?, base: String): WorkspaceAppRoute = when {
        WorkspaceOrigin.isSameOrigin(href, base) -> WorkspaceAppRoute.SIGNED_IN
        WorkspaceOrigin.isOutsideHttps(href) -> WorkspaceAppRoute.CUSTOM_TAB
        else -> WorkspaceAppRoute.IGNORE
    }

    fun route(url: String?, base: String): WorkspaceRoute = when {
        WorkspaceOrigin.parse(url?.trim()) == null -> WorkspaceRoute.IGNORE
        WorkspaceOrigin.isSameOrigin(url?.trim(), base) -> WorkspaceRoute.IN_APP
        else -> WorkspaceRoute.EXTERNAL
    }
}
