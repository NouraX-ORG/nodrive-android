/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** File names of the reports: "<YYYY-MM-DD_HH-MM-SS>_<platform>_<app_version>.zip" (UTC), with _2, _3 when the name is taken. */
object ReportName {
    const val PLATFORM = "android"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC)
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private const val MAX_SUFFIX = 99

    /** The name without ".zip". */
    fun base(createdMillis: Long, appVersion: String): String =
        "${STAMP.format(Instant.ofEpochMilli(createdMillis))}_${PLATFORM}_${UNSAFE.replace(appVersion, "_")}"

    /** ISO 8601 in UTC, whole seconds, for report.json. */
    fun isoUtc(createdMillis: Long): String = ISO.format(Instant.ofEpochMilli(createdMillis))

    fun fileName(base: String, number: Int): String = if (number <= 1) "$base.zip" else "${base}_$number.zip"

    /** The first of base.zip, base_2.zip, ... that [taken] does not report as taken. */
    fun firstFree(base: String, taken: (String) -> Boolean): String {
        for (number in 1..MAX_SUFFIX) {
            val name = fileName(base, number)
            if (!taken(name)) return name
        }
        return fileName(base, MAX_SUFFIX + 1)
    }
}
