/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceAppsParserTest {
    private val base = "https://drive.nourax.org"

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("workspace/navigation-apps.json"))
            .bufferedReader().use { it.readText() }

    @Test
    fun filesActivityPhotosAndExternalEntriesAreDroppedAndOrderIsKept() {
        val apps = WorkspaceAppsParser.parse(fixture(), base)
        assertEquals(
            listOf("dashboard", "mail", "calendar", "spreed", "elsewhere", "nourax_nomail", "notes"),
            apps.map { it.id }
        )
        assertEquals(
            listOf("Dashboard", "Mail", "Calendar", "Talk", "Elsewhere", "NoMail", "Notes"),
            apps.map { it.name }
        )
    }

    @Test
    fun noMailOnAnotherOriginIsKeptAsAnOutsideEntryWithItsOwnOriginIcon() {
        val apps = WorkspaceAppsParser.parse(fixture(), base)
        val nomail = apps.first { it.id == "nourax_nomail" }
        assertEquals("https://mail.nourax.org/", nomail.href)
        assertEquals("https://drive.nourax.org/apps/nourax_branding/img/nomail.svg", nomail.iconUrl)
        assertTrue(nomail.outside)
        // An icon on another origin is never requested: the entry keeps the default icon.
        val elsewhere = apps.first { it.id == "elsewhere" }
        assertTrue(elsewhere.outside)
        assertNull(elsewhere.iconUrl)
        assertTrue(apps.filter { it.id !in setOf("nourax_nomail", "elsewhere") }.none { it.outside })
    }

    @Test
    fun entriesOfAnotherOriginThatAreNotPlainHttpsAreDropped() {
        val hrefs = listOf(
            "http://mail.nourax.org/",
            "ftp://mail.nourax.org/",
            "javascript:alert(1)",
            "mailto:a@b.example",
            "intent://x#Intent;end",
            "https://drive.nourax.org@mail.nourax.org/",
            "https://user:pw@mail.nourax.org/",
            "https:///nohost",
            "https://",
            "//mail.nourax.org/",
            "\\mail.nourax.org",
            "mail.nourax.org",
            "https://mail.nourax.org/a b",
            ""
        )
        val entries = hrefs.mapIndexed { i, href ->
            """{"id":"e$i","type":"link","name":"E$i","href":${JsonPrimitive(href)}}"""
        }
        val json = """{"ocs":{"data":[${entries.joinToString(",")},
            {"id":"ok","type":"link","name":"Ok","href":"https://mail.nourax.org/"}]}}"""
        assertEquals(listOf("ok"), WorkspaceAppsParser.parse(json, base).map { it.id })
    }

    @Test
    fun outsideEntriesStillFollowTheOtherFilters() {
        val json = """{"ocs":{"data":[
            {"id":"files","type":"link","name":"Files","href":"https://mail.nourax.org/","app":"files"},
            {"id":"x","type":"link","name":"X","href":"https://mail.nourax.org/","app":"external"},
            {"id":"y","type":"settings","name":"Y","href":"https://mail.nourax.org/"},
            {"id":"z","type":"link","name":" ","href":"https://mail.nourax.org/"}]}}"""
        assertTrue(WorkspaceAppsParser.parse(json, base).isEmpty())
    }

    @Test
    fun anAbsoluteHrefOnTheAccountOriginIsNotOutside() {
        val json = """{"ocs":{"data":[{"id":"m","type":"link","name":"M","href":"https://drive.nourax.org/apps/m/"}]}}"""
        val app = WorkspaceAppsParser.parse(json, base).single()
        assertEquals(false, app.outside)
    }

    @Test
    fun hiddenAppsAreDroppedByIdAndByAppName() {
        val json = """{"ocs":{"data":[
            {"id":"activity","type":"link","name":"Activity","href":"/apps/activity/","app":"activity"},
            {"id":"photos","type":"link","name":"Photos","href":"/apps/photos/","app":"photos"},
            {"id":"x","type":"link","name":"Pictures","href":"/apps/photos/","app":"photos"},
            {"id":"y","type":"link","name":"Feed","href":"/apps/activity/","app":"activity"},
            {"id":"mail","type":"link","name":"Mail","href":"/apps/mail/","app":"mail"}]}}"""
        assertEquals(listOf("mail"), WorkspaceAppsParser.parse(json, base).map { it.id })
    }

    @Test
    fun hrefAndIconAreResolvedAgainstTheAccountOrigin() {
        val mail = WorkspaceAppsParser.parse(fixture(), base).first { it.id == "mail" }
        assertEquals(false, mail.outside)
        assertEquals("https://drive.nourax.org/apps/mail/", mail.href)
        assertEquals("https://drive.nourax.org/apps/mail/img/app.svg", mail.iconUrl)
        assertEquals("mail", mail.appId)
    }

    @Test
    fun entryWithoutOrderOrAppIsKept() {
        val notes = WorkspaceAppsParser.parse(fixture(), base).first { it.id == "notes" }
        assertNull(notes.appId)
        assertEquals("https://drive.nourax.org/apps/notes/", notes.href)
    }

    @Test
    fun webrootIsKeptOnce() {
        val json = """{"ocs":{"data":[{"id":"mail","type":"link","name":"Mail","href":"/cloud/apps/mail/",
            "icon":"/cloud/apps/mail/img/app.svg"}]}}"""
        val app = WorkspaceAppsParser.parse(json, "https://h.example/cloud").single()
        assertEquals("https://h.example/cloud/apps/mail/", app.href)
    }

    @Test
    fun emptyOrMissingDataGivesAnEmptyList() {
        assertTrue(WorkspaceAppsParser.parse("""{"ocs":{"data":[]}}""", base).isEmpty())
        assertTrue(WorkspaceAppsParser.parse("""{"ocs":{"meta":{}}}""", base).isEmpty())
    }

    @Test
    fun entriesWithoutNameOrWithAnotherTypeAreDropped() {
        val json = """{"ocs":{"data":[
            {"id":"a","type":"link","name":" ","href":"/apps/a/"},
            {"id":"b","type":"settings","name":"B","href":"/apps/b/"},
            {"id":"c","type":"link","name":"C","href":"/apps/c/"}]}}"""
        assertEquals(listOf("c"), WorkspaceAppsParser.parse(json, base).map { it.id })
    }

    @Test
    fun malformedJsonThrows() {
        listOf("not json", "{}", """{"ocs":[]}""").forEach {
            try {
                WorkspaceAppsParser.parse(it, base)
                fail("expected an error for $it")
            } catch (_: JsonParseException) {
                // expected
            }
        }
    }
}
