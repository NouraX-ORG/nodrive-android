/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.google.gson.JsonParser
import com.nextcloud.common.NextcloudClient
import com.owncloud.android.lib.common.utils.Log_OC
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Design section 1.5: the app's own HTTP client sends the one request that carries the app password, to the
 * account's own server and without following redirects. Only the cookies of the answer go to the web view.
 */
class WorkspaceSessionBootstrap(private val client: NextcloudClient, private val userAgent: String) {
    fun run(): WorkspaceSessionResult {
        val base = client.baseUri.toString()
        val origin = WorkspaceOrigin.origin(base) ?: return WorkspaceSessionResult.Failure("base-url")
        val request = Request.Builder()
            .url(origin + client.baseUri.path.orEmpty().trimEnd('/') + USER_PATH)
            .header("Authorization", client.credentials)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .build()
        val http = client.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return try {
            http.newCall(request).execute().use { response ->
                evaluate(response.code, response.body.string(), response.headers("Set-Cookie"), client.userId)
            }
        } catch (e: IOException) {
            Log_OC.w(TAG, "session request failed: ${e.javaClass.simpleName}")
            WorkspaceSessionResult.Failure("network")
        }
    }

    companion object {
        private const val TAG = "WorkspaceSession"
        private const val USER_PATH = "/ocs/v2.php/cloud/user?format=json"

        /** Accepts only HTTP 200 for the account's own user id with a usable session cookie set. */
        fun evaluate(code: Int, body: String, setCookies: List<String>, userId: String): WorkspaceSessionResult {
            if (code != HttpURLConnection.HTTP_OK) return WorkspaceSessionResult.Failure("http-$code")
            val id = try {
                JsonParser.parseString(body).asJsonObject.getAsJsonObject("ocs").getAsJsonObject("data")
                    .get("id").asString
            } catch (_: RuntimeException) {
                return WorkspaceSessionResult.Failure("answer")
            }
            if (id != userId) return WorkspaceSessionResult.Failure("user")
            val cookies = WorkspaceCookies.fromSetCookieHeaders(setCookies)
            if (!WorkspaceCookies.hasSession(cookies)) return WorkspaceSessionResult.Failure("cookies")
            return WorkspaceSessionResult.Success(cookies)
        }
    }
}
