/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.reports

/** Why a report exists. The wire names are fixed by the round 3 contract (B). */
enum class ReportKind(val wire: String) {
    CRASH("crash"),
    MANUAL("manual"),
    UNCLEAN_EXIT("unclean-exit")
}

/** What report.json says about the app and the phone. */
data class ReportMeta(
    val appVersion: String,
    val build: String,
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
    val createdUtc: String,
    val serverHost: String
)

/** report.json, written by hand so the unit tests do not depend on Android's JSON classes. */
object ReportJson {
    const val SCHEMA = 1
    const val APP = "NoDrive Android"
    const val MAX_NOTE_CHARS = 10_000

    /** The 12 fields, in a fixed order. [userNote] is scrubbed here as well. */
    fun build(kind: ReportKind, meta: ReportMeta, userNote: String): String {
        val note = Scrubber.scrub(userNote.take(MAX_NOTE_CHARS))
        return buildString {
            append("{\n")
            append("  \"schema\": $SCHEMA,\n")
            field("kind", kind.wire)
            field("app", APP)
            field("app_version", meta.appVersion)
            field("build", meta.build)
            field("platform", ReportName.PLATFORM)
            field("os_version", meta.osVersion)
            field("device_model", meta.deviceModel)
            field("locale", meta.locale)
            field("created_utc", meta.createdUtc)
            field("server_host", meta.serverHost)
            field("user_note", note, last = true)
            append("}\n")
        }
    }

    private fun StringBuilder.field(name: String, value: String, last: Boolean = false) {
        append("  \"").append(name).append("\": \"").append(escape(value)).append('"')
        append(if (last) "\n" else ",\n")
    }

    fun escape(value: String): String = buildString {
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
