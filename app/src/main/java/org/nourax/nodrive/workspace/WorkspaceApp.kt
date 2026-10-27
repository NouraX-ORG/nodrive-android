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
    val iconUrl: String?,
    /** An https entry of another origin (such as NoMail): it opens in a Custom Tab, never signed in. */
    val outside: Boolean = false
)
