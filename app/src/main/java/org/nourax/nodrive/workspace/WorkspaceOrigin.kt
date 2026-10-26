/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * The credential of an account may only go to the origin (scheme, host, port) of that account's server.
 * Every decision about "is this the account's own server" lives here, as pure functions on strings.
 */
object WorkspaceOrigin {
    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val PORT_HTTP = 80
    private const val PORT_HTTPS = 443
    private const val NO_PORT = -1
    private val loginPath = Regex("^(/index\.php)?(/login|/apps/user_oidc/login)(/.*)?$")
    private val logoutPath = Regex("^(/index\.php)?/logout(/.*)?$")

    fun parse(value: String?): URI? {
        if (value.isNullOrEmpty()) return null
        return try {
            URI(value)
        } catch (_: URISyntaxException) {
            null
        }
    }

    fun isSameOrigin(candidate: String?, base: String): Boolean {
        val target = webUri(parse(candidate)) ?: return false
        val own = webUri(parse(base)) ?: return false
        return target.scheme.lowercase(Locale.ROOT) == own.scheme.lowercase(Locale.ROOT) &&
            target.host.lowercase(Locale.ROOT) == own.host.lowercase(Locale.ROOT) &&
            effectivePort(target) == effectivePort(own)
    }

    /** "https://host[:port]" of the base URL, or null when it is not a plain http(s) URL. */
    fun origin(base: String): String? {
        val uri = webUri(parse(base)) ?: return null
        val port = if (uri.port == NO_PORT) "" else ":${uri.port}"
        return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.host}$port"
    }

    /** Resolves a server-given href or icon path against the account's origin; a foreign origin gives null. */
    fun resolve(href: String?, base: String): String? {
        if (href.isNullOrEmpty()) return null
        val origin = origin(base) ?: return null
        if (href.startsWith("/")) {
            return if (href.startsWith("//") || href.contains('\')) null else origin + href
        }
        return if (isSameOrigin(href, base)) href else null
    }

    fun isLoginPath(url: String, base: String): Boolean = pathBelowWebroot(url, base)?.let(loginPath::matches) == true

    fun isLogoutPath(url: String, base: String): Boolean = pathBelowWebroot(url, base)?.let(logoutPath::matches) == true

    private fun pathBelowWebroot(url: String, base: String): String? {
        if (!isSameOrigin(url, base)) return null
        val path = parse(url)?.normalize()?.path ?: return null
        val webroot = parse(base)?.normalize()?.path.orEmpty().trimEnd('/')
        if (path != webroot && !path.startsWith("$webroot/")) return null
        return path.removePrefix(webroot).ifEmpty { "/" }
    }

    private fun webUri(uri: URI?): URI? {
        if (uri == null || uri.userInfo != null || uri.host.isNullOrEmpty()) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return if (scheme == HTTP || scheme == HTTPS) uri else null
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != NO_PORT -> uri.port
        uri.scheme.lowercase(Locale.ROOT) == HTTPS -> PORT_HTTPS
        else -> PORT_HTTP
    }
}
