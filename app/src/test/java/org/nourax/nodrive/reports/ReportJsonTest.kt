/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportJsonTest {
    private val meta = ReportMeta(
        appVersion = "35.0.1-nourax.1",
        build = "350001",
        osVersion = "Android 15 (API 35)",
        deviceModel = "Google Pixel 6",
        locale = "en-GB",
        createdUtc = "2026-10-28T13:05:09Z",
        serverHost = "drive.example.org"
    )

    /** key -> raw value text (a JSON string with its quotes, or a number). */
    private fun fields(json: String): Map<String, String> =
        Regex("\"([a-z_]+)\": (\"(?:[^\"\\\\]|\\\\.)*\"|\\d+)").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun hasExactlyTheTwelveFieldsOfTheContract() {
        val f = fields(ReportJson.build(ReportKind.MANUAL, meta, "it broke"))
        assertEquals(
            listOf(
                "schema", "kind", "app", "app_version", "build", "platform", "os_version", "device_model", "locale",
                "created_utc", "server_host", "user_note"
            ),
            f.keys.toList()
        )
        assertEquals("1", f["schema"])
        assertEquals("\"NoDrive Android\"", f["app"])
        assertEquals("\"android\"", f["platform"])
        assertEquals("\"35.0.1-nourax.1\"", f["app_version"])
        assertEquals("\"350001\"", f["build"])
        assertEquals("\"2026-10-28T13:05:09Z\"", f["created_utc"])
        assertEquals("\"drive.example.org\"", f["server_host"])
        assertEquals("\"it broke\"", f["user_note"])
    }

    @Test
    fun theThreeKindsHaveTheirWireNames() {
        assertEquals("\"crash\"", fields(ReportJson.build(ReportKind.CRASH, meta, ""))["kind"])
        assertEquals("\"manual\"", fields(ReportJson.build(ReportKind.MANUAL, meta, ""))["kind"])
        assertEquals("\"unclean-exit\"", fields(ReportJson.build(ReportKind.UNCLEAN_EXIT, meta, ""))["kind"])
    }

    @Test
    fun theNoteIsScrubbedAndEscaped() {
        val json = ReportJson.build(ReportKind.MANUAL, meta, "mail me at anna@example.org\n\"quoted\" \\ tab\there password=hunter2")
        assertFalse(json.contains("anna@example.org"))
        assertFalse(json.contains("hunter2"))
        assertTrue(json.contains("<email>"))
        assertTrue(json.contains("\\n\\\"quoted\\\" \\\\ tab\\there"))
    }

    @Test
    fun createdUtcIsIso8601InUtc() {
        assertEquals("2026-10-28T13:05:09Z", ReportName.isoUtc(1_793_192_709_000L))
    }
}
