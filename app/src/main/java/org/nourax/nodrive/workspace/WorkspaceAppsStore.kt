/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

data class WorkspaceAppsCache(val json: String, val etag: String?, val fetchedAtMillis: Long)

/** The per-account copy of the last navigation answer. */
interface WorkspaceAppsStore {
    fun load(): WorkspaceAppsCache?
    fun save(cache: WorkspaceAppsCache)
    fun clear()
}
