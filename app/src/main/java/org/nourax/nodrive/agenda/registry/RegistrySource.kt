/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.registry

import org.nourax.nodrive.agenda.net.AgendaHttp
import java.net.URLEncoder

/** The result of asking a registry source. [notModified] means the cached copy is still current. */
class RegistryFetch(
    val status: Int,
    val body: ByteArray? = null,
    val etag: String? = null,
    val notModified: Boolean = false,
    val networkError: Boolean = false
)

/**
 * The one swappable source of the agent registry (design 4.1). Today it is the person's own file in NoDrive; later
 * a URL of the Nourax agents app that returns the same JSON. The switch is one change in [RegistrySources].
 */
interface RegistrySource {
    fun fetch(etag: String?): RegistryFetch
}

/** `GET remote.php/dav/files/<user>/NoAgenda/agent-registry.json` with the user's own login (placeholder phase). */
class DriveRegistrySource(private val http: AgendaHttp, private val userId: String) : RegistrySource {
    override fun fetch(etag: String?): RegistryFetch {
        val path = "remote.php/dav/files/${encodeSegment(userId)}/NoAgenda/agent-registry.json"
        val headers = if (etag.isNullOrEmpty()) emptyMap() else mapOf("If-None-Match" to etag)
        val result = http.send("GET", path, headers)
        return when {
            result.networkError -> RegistryFetch(0, networkError = true)
            result.status == 304 -> RegistryFetch(304, notModified = true, etag = etag)
            result.status == 200 -> RegistryFetch(200, result.body, result.header("ETag"))
            else -> RegistryFetch(result.status)
        }
    }

    private fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

object RegistrySources {
    /**
     * Reserved for the address of the Nourax agents app once it exists (the same JSON, answered per user). Empty
     * and not read today: the registry is the user's own file. When the agents app exists, a second
     * [RegistrySource] for that address is added and chosen in [create]; nothing else changes (design 4.1).
     */
    const val AGENTS_APP_REGISTRY_URL = ""

    fun create(http: AgendaHttp, userId: String): RegistrySource = DriveRegistrySource(http, userId)
}
