/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** Starts one [IntentSpec]: true when something started, false when there is nothing to start; may throw. */
fun interface IntentStarter {
    fun start(spec: IntentSpec): Boolean
}

enum class LaunchResult { STARTED, FAILED }

/** Tries the places' intents in order and never lets a missing target break the screen. */
class PlaceLauncher(private val starter: IntentStarter) {
    @Suppress("TooGenericExceptionCaught")
    fun open(place: Place): LaunchResult {
        for (spec in place.chain) {
            val started = try {
                starter.start(spec)
            } catch (_: RuntimeException) {
                false
            }
            if (started) return LaunchResult.STARTED
        }
        return LaunchResult.FAILED
    }
}
