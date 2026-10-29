/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.server

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.net.AccountAgendaHttp
import org.nourax.nodrive.agenda.net.AgendaHttp
import java.io.File
import java.time.Instant
import java.util.Base64

/**
 * The tests of this package talk to the throwaway Nextcloud 35 of tools/ci/agenda-server.sh (never drive.nourax.org). They are
 * skipped, so they are no-ops in the normal unit test run, unless NODRIVE_AG_URL is set. The credential is an app password made by
 * `occ user:auth-tokens:add`, the same kind of credential the app gets from Login Flow v2. It is sent as Basic authentication by
 * [AccountAgendaHttp] itself, so the test proves the app's own transport.
 */
object ServerSupport {
    val url: String? = System.getenv("NODRIVE_AG_URL")?.takeIf { it.isNotEmpty() }

    fun assumeServer() = assumeTrue("NODRIVE_AG_URL is not set: no throwaway server", url != null)

    fun token(user: String): String = System.getenv("NODRIVE_AG_${user.uppercase()}_TOKEN").orEmpty()

    fun http(user: String): AgendaHttp {
        val authorization = "Basic " + Base64.getEncoder().encodeToString("$user:${token(user)}".toByteArray())
        return AccountAgendaHttp(checkNotNull(url), authorization, OkHttpClient())
    }

    val clock = FixedAgendaClock(Instant.now())

    private val reportDir: File = File(System.getenv("NODRIVE_AG_REPORT_DIR") ?: "build/agenda-server").also { it.mkdirs() }

    /** Appends one line to a report file. Only names, status codes and counts go in. */
    @Synchronized
    fun report(file: String, line: String) {
        File(reportDir, file).appendText(line + "\n")
    }
}
