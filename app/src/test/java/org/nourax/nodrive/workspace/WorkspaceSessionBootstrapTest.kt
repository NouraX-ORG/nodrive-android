/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceSessionBootstrapTest {
    private val body = """{"ocs":{"meta":{"statuscode":200},"data":{"id":"citest"}}}"""
    private val cookies = listOf(
        "oc_sessionPassphrase=secretpassphrase; path=/; HttpOnly; SameSite=Lax",
        "ocabc123def4=secretsession; path=/; HttpOnly; SameSite=Lax",
        "nc_sameSiteCookielax=true; path=/; SameSite=Lax"
    )

    private fun evaluate(code: Int = 200, answer: String = body, headers: List<String> = cookies, user: String = "citest") =
        WorkspaceSessionBootstrap.evaluate(code, answer, headers, user)

    private fun reason(result: WorkspaceSessionResult) = (result as WorkspaceSessionResult.Failure).reason

    @Test
    fun acceptsTheOwnUserWithSessionCookies() {
        assertEquals(3, (evaluate() as WorkspaceSessionResult.Success).cookies.size)
    }

    @Test
    fun refusesAnotherUserId() {
        assertEquals("user", reason(evaluate(user = "someone")))
    }

    @Test
    fun refusesRedirectsAndErrors() {
        listOf(301, 302, 307, 401, 429, 500).forEach { assertEquals("http-$it", reason(evaluate(code = it))) }
    }

    @Test
    fun refusesAMissingPassphraseOrSessionCookie() {
        assertEquals("cookies", reason(evaluate(headers = cookies.drop(1))))
        assertEquals("cookies", reason(evaluate(headers = cookies.filterIndexed { index, _ -> index != 1 })))
        assertEquals("cookies", reason(evaluate(headers = emptyList())))
    }

    @Test
    fun refusesAnAnswerThatIsNotOcs() {
        assertEquals("answer", reason(evaluate(answer = "<html>")))
        assertEquals("answer", reason(evaluate(answer = "{}")))
    }

    @Test
    fun theFailureReasonNeverHoldsACookieValue() {
        val reasons = listOf(reason(evaluate(headers = cookies.drop(1))), reason(evaluate(user = "someone")))
        reasons.forEach {
            assertFalse(it, it.contains("secretpassphrase") || it.contains("secretsession"))
        }
    }
}
