/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

sealed interface WorkspaceSessionResult {
    data class Success(val cookies: List<WorkspaceCookie>) : WorkspaceSessionResult

    /** The reason is a short code for the log, never a header, cookie value or body. */
    data class Failure(val reason: String) : WorkspaceSessionResult
}
