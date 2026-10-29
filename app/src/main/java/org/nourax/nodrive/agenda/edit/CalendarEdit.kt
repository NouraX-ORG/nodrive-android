/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import org.nourax.nodrive.agenda.ical.ICalPatch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The semantic change of an event or a to-do (EditKeys) turned into properties (plan 2.4). Times are written in UTC with `Z`, so
 * no time zone definition has to be made. The agent id is written as `X-NOURAX-AGENT-ID` and, as a second copy that survives
 * programs which drop unknown properties, as the registry's calendar tag in `CATEGORIES` (design 4.2).
 */
object CalendarEdit {
    const val AGENT_PROPERTY = "X-NOURAX-AGENT-ID"
    private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Applies [change] to the one event or to-do of [text]. Returns the new text. */
    fun patch(text: String, isTodo: Boolean, change: Map<String, String>, now: Instant): String {
        val patch = ICalPatch(text, if (isTodo) "VTODO" else "VEVENT")
        apply(patch, isTodo, change)
        patch.stamp(now)
        return patch.build()
    }

    private fun apply(patch: ICalPatch, isTodo: Boolean, change: Map<String, String>) {
        change[EditKeys.SUMMARY]?.let { patch.setText("SUMMARY", it) }
        change[EditKeys.DESCRIPTION]?.let { if (it.isEmpty()) patch.remove("DESCRIPTION") else patch.setText("DESCRIPTION", it) }
        if (!isTodo) {
            val start = EditKeys.parse(change[EditKeys.START])
            val end = EditKeys.parse(change[EditKeys.END])
            if (start != null || end != null) {
                patch.remove("DURATION")
                when (start) {
                    is EditKeys.Value.Day -> {
                        patch.setDate("DTSTART", start.date)
                        // The last day is written; DTEND is the day after (an all day event ends at the start of the next day).
                        val last = (end as? EditKeys.Value.Day)?.date ?: start.date
                        patch.setDate("DTEND", last.plusDays(1))
                    }
                    is EditKeys.Value.Moment -> {
                        patch.setUtc("DTSTART", start.instant)
                        val stop = (end as? EditKeys.Value.Moment)?.instant ?: start.instant.plusSeconds(HOUR_SECONDS)
                        patch.setUtc("DTEND", stop)
                    }
                    else -> Unit
                }
            }
        } else {
            for ((key, name) in listOf(EditKeys.START to "DTSTART", EditKeys.DUE to "DUE")) {
                when (val value = EditKeys.parse(change[key])) {
                    null -> Unit
                    EditKeys.Value.None -> patch.remove(name)
                    is EditKeys.Value.Day -> patch.setDate(name, value.date)
                    is EditKeys.Value.Moment -> patch.setUtc(name, value.instant)
                }
            }
            change[EditKeys.DONE]?.let { done ->
                if (done == "true") {
                    patch.set("STATUS", "", "COMPLETED")
                    patch.set("COMPLETED", "", UTC_FORMAT.format(Instant.ofEpochMilli(completedAt(change)).atOffset(ZoneOffset.UTC)))
                    patch.set("PERCENT-COMPLETE", "", "100")
                } else {
                    patch.set("STATUS", "", "NEEDS-ACTION")
                    patch.remove("COMPLETED")
                    patch.remove("PERCENT-COMPLETE")
                }
            }
        }
        applyAgent(patch, change)
    }

    private fun completedAt(change: Map<String, String>): Long = change[COMPLETED_AT]?.toLongOrNull() ?: System.currentTimeMillis()

    private fun applyAgent(patch: ICalPatch, change: Map<String, String>) {
        val agent = change[EditKeys.AGENT] ?: return
        val allTags = change[EditKeys.ALL_TAGS].orEmpty().split(EditKeys.SEP).filter { it.isNotEmpty() }.toSet()
        if (agent == EditKeys.SELF || agent.isEmpty()) {
            patch.remove(AGENT_PROPERTY)
            patch.changeCategories(allTags, null)
        } else {
            patch.setText(AGENT_PROPERTY, agent)
            val tag = change[EditKeys.AGENT_TAG].orEmpty()
            patch.changeCategories(allTags - tag, tag.ifEmpty { null })
        }
    }

    /** The text of a new event or to-do (a calendar object with one component). */
    fun create(isTodo: Boolean, uid: String, change: Map<String, String>, now: Instant): String {
        val component = if (isTodo) "VTODO" else "VEVENT"
        val skeleton = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Nourax//NoAgenda//EN\r\nBEGIN:").append(component).append("\r\nUID:")
            append(ICalPatch.escape(uid)).append("\r\nEND:").append(component).append("\r\nEND:VCALENDAR\r\n")
        }
        val patch = ICalPatch(skeleton, component)
        patch.setText("SUMMARY", change[EditKeys.SUMMARY].orEmpty())
        if (isTodo) {
            patch.set("STATUS", "", "NEEDS-ACTION")
        } else {
            patch.set("TRANSP", "", "OPAQUE")
        }
        apply(patch, isTodo, change - EditKeys.SUMMARY)
        patch.set("CREATED", "", UTC_FORMAT.format(now.atOffset(ZoneOffset.UTC)))
        patch.stamp(now)
        return patch.build()
    }

    const val COMPLETED_AT = "completedAt"
    private const val HOUR_SECONDS = 3600L
}
