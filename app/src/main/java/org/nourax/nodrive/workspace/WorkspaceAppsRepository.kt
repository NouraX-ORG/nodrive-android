/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.google.gson.JsonParseException
import java.io.IOException
import java.net.HttpURLConnection

enum class WorkspaceRefresh { UPDATED, UNCHANGED, CLEARED, KEPT, THROTTLED }

/** Design section 2: show the cache at once, refresh with the stored ETag, keep the cache when in doubt. */
class WorkspaceAppsRepository(
    private val base: String,
    private val store: WorkspaceAppsStore,
    private val transport: WorkspaceAppsTransport,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun cached(): List<WorkspaceApp> {
        val cache = store.load() ?: return emptyList()
        return try {
            WorkspaceAppsParser.parse(cache.json, base)
        } catch (_: JsonParseException) {
            emptyList()
        }
    }

    fun refresh(force: Boolean = false): WorkspaceRefresh {
        val cache = store.load()
        val now = clock()
        if (!force && cache != null && now - cache.fetchedAtMillis in 0 until MIN_INTERVAL_MILLIS) {
            return WorkspaceRefresh.THROTTLED
        }
        val response = try {
            transport.get(cache?.etag)
        } catch (_: IOException) {
            return WorkspaceRefresh.KEPT
        }
        return when (response.code) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> unchanged(cache, now)
            HttpURLConnection.HTTP_NOT_FOUND -> {
                store.clear()
                WorkspaceRefresh.CLEARED
            }
            HttpURLConnection.HTTP_OK -> updated(response, now)
            else -> WorkspaceRefresh.KEPT
        }
    }

    private fun unchanged(cache: WorkspaceAppsCache?, now: Long): WorkspaceRefresh {
        if (cache == null) return WorkspaceRefresh.KEPT
        store.save(cache.copy(fetchedAtMillis = now))
        return WorkspaceRefresh.UNCHANGED
    }

    private fun updated(response: WorkspaceAppsResponse, now: Long): WorkspaceRefresh {
        val body = response.body ?: return WorkspaceRefresh.KEPT
        try {
            WorkspaceAppsParser.parse(body, base)
        } catch (_: JsonParseException) {
            return WorkspaceRefresh.KEPT
        }
        store.save(WorkspaceAppsCache(body, response.etag, now))
        return WorkspaceRefresh.UPDATED
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 10 * 60 * 1000L
    }
}
