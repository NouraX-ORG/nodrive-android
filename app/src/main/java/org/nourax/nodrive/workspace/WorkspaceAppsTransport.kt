/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

data class WorkspaceAppsResponse(val code: Int, val body: String?, val etag: String?)

fun interface WorkspaceAppsTransport {
    /** Sends the navigation request; throws java.io.IOException when the server cannot be reached. */
    fun get(etag: String?): WorkspaceAppsResponse
}
