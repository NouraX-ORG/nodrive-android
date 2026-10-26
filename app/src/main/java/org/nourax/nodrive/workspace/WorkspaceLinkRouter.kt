/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

/** Where a link of the notification list opens (design rule 9). */
enum class WorkspaceRoute { IN_APP, EXTERNAL, IGNORE }

object WorkspaceLinkRouter {
    fun route(url: String?, base: String): WorkspaceRoute = when {
        WorkspaceOrigin.parse(url?.trim()) == null -> WorkspaceRoute.IGNORE
        WorkspaceOrigin.isSameOrigin(url?.trim(), base) -> WorkspaceRoute.IN_APP
        else -> WorkspaceRoute.EXTERNAL
    }
}
