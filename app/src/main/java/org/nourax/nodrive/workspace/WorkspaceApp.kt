/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

data class WorkspaceApp(
    val id: String,
    val appId: String?,
    val name: String,
    val href: String,
    val iconUrl: String?
)
