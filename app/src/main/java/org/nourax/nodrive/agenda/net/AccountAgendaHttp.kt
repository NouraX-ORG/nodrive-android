/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.net

import com.nextcloud.common.NextcloudClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.nourax.nodrive.workspace.WorkspaceOrigin
import java.util.concurrent.TimeUnit

/**
 * Sends the requests with the account's own client, its own `Authorization` value and only to the account's own
 * origin. Redirects are off, so the credential can never follow a redirect to another host.
 */
class AccountAgendaHttp(
    private val baseUrl: String,
    private val authorization: String,
    okHttp: OkHttpClient,
    /** Which writes may go out. NoAgenda's own rules by default; device sync passes its own (devicesync.DeviceSyncWritePolicy). */
    private val writePolicy: (String, String) -> Boolean = WritePolicy::allows
) : AgendaHttp {
    private val http: OkHttpClient = okHttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val origin: String = checkNotNull(WorkspaceOrigin.origin(baseUrl)) { "account server is not a web URL" }
    private val basePath: String = runCatching { java.net.URI(baseUrl).rawPath.orEmpty().trimEnd('/') }.getOrDefault("")

    /** The full URL for [path], or null when it would leave the account's origin. */
    fun urlFor(path: String): String? {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return if (WorkspaceOrigin.isSameOrigin(path, baseUrl)) path else null
        }
        if (path.startsWith("//")) return null
        return if (path.startsWith("/")) {
            // An href of the server: it already contains the server's own sub-folder, if it has one.
            origin + path
        } else {
            "$origin$basePath/$path"
        }
    }

    override fun send(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): HttpResult {
        // Only the writes of the edit step go out (WritePolicy): events and to-dos, NoGantt task updates, NoBoard card updates.
        if (!writePolicy(method, path)) return HttpResult(0, refused = true)
        val url = urlFor(path) ?: return HttpResult(0, refused = true)
        return try {
            val requestBody = when {
                body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
                method in BODY_METHODS -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val builder = Request.Builder().url(url).method(method, requestBody).header("Authorization", authorization)
            headers.forEach { (name, value) -> builder.header(name, value) }
            http.newCall(builder.build()).execute().use { response ->
                val bytes = response.body.bytes()
                HttpResult(response.code, bytes, response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() })
            }
        } catch (_: Exception) {
            HttpResult(0, networkError = true)
        }
    }

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 60L
        private val BODY_METHODS = setOf("REPORT", "PROPFIND", "PUT", "POST")

        fun of(client: NextcloudClient): AccountAgendaHttp =
            AccountAgendaHttp(client.baseUri.toString(), client.credentials, client.client)

        fun of(client: NextcloudClient, writePolicy: (String, String) -> Boolean): AccountAgendaHttp =
            AccountAgendaHttp(client.baseUri.toString(), client.credentials, client.client, writePolicy)
    }
}
