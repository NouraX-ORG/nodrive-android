/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The time and the zone NoAgenda works in. Tests run on a fixed clock. */
interface AgendaClock {
    fun now(): Instant

    fun zone(): ZoneId

    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
}

object SystemAgendaClock : AgendaClock {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedAgendaClock(private val instant: Instant, private val zoneId: ZoneId = ZoneId.of("UTC")) : AgendaClock {
    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId
}
