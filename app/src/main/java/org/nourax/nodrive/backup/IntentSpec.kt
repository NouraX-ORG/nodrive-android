/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

/** What to start, described without Android classes so the order of the fallbacks can be unit-tested. */
sealed interface IntentSpec {
    /** The launcher intent of an installed app. */
    data class LaunchPackage(val packageName: String) : IntentSpec

    data class ViewAction(val action: String, val data: String? = null) : IntentSpec
}
