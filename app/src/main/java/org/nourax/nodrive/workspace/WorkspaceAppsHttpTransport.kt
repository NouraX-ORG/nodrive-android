/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.nextcloud.common.NextcloudClient
import okhttp3.Request

/** The navigation request, sent with the account's own client to the account's own server. */
class WorkspaceAppsHttpTransport(private val client: NextcloudClient) : WorkspaceAppsTransport {
    override fun get(etag: String?): WorkspaceAppsResponse {
        val base = client.baseUri.toString()
        val origin = checkNotNull(WorkspaceOrigin.origin(base)) { "account server is not a web URL" }
        val builder = Request.Builder()
            .url(origin + client.baseUri.path.orEmpty().trimEnd('/') + NAVIGATION_PATH)
            .header("Authorization", client.credentials)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
        if (!etag.isNullOrEmpty()) builder.header("If-None-Match", etag)
        val http = client.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return http.newCall(builder.build()).execute().use { response ->
            WorkspaceAppsResponse(response.code, response.body.string(), response.header("ETag"))
        }
    }

    private companion object {
        const val NAVIGATION_PATH = "/ocs/v2.php/core/navigation/apps?absolute=false&format=json"
    }
}
