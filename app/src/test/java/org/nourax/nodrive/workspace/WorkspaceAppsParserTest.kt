/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.google.gson.JsonParseException
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
    fun filesActivityPhotosExternalAndForeignEntriesAreDroppedAndOrderIsKept() {
        val apps = WorkspaceAppsParser.parse(fixture(), base)
        assertEquals(listOf("dashboard", "mail", "calendar", "spreed", "notes"), apps.map { it.id })
        assertEquals(listOf("Dashboard", "Mail", "Calendar", "Talk", "Notes"), apps.map { it.name })
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
