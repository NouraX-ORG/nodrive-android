/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

/** What a PROPFIND for the file that is already in NoDrive told us. [F] is whatever describes a failure (a result). */
sealed interface RemoteSize<out F> {
    /** The remote `getcontentlength` in bytes. */
    data class Known(val bytes: Long) : RemoteSize<Nothing>

    /** The server answered "not found": the file is gone again. */
    data object Missing : RemoteSize<Nothing>

    /** The size could not be read (network, permission, anything else). */
    data class Unreadable<F>(val failure: F) : RemoteSize<F>
}

sealed interface SmartDecision<out F> {
    /** Same size: it is already backed up. Nothing is uploaded and nothing is shown. */
    data object AlreadyBackedUp : SmartDecision<Nothing>

    /** Different size (or gone): upload it under a new numbered name. Both files stay. */
    data object KeepBoth : SmartDecision<Nothing>

    /** The size could not be read: fail like any other failure, so it is tried again later. Never a guess. */
    data class Retry<F>(val failure: F) : SmartDecision<F>
}

/** The Smart rule (round 3 contract, A). It has no overwrite and no delete outcome. */
object SmartRule {
    fun <F> decide(localBytes: Long, remote: RemoteSize<F>): SmartDecision<F> = when (remote) {
        is RemoteSize.Known -> if (remote.bytes == localBytes) SmartDecision.AlreadyBackedUp else SmartDecision.KeepBoth
        RemoteSize.Missing -> SmartDecision.KeepBoth
        is RemoteSize.Unreadable -> SmartDecision.Retry(remote.failure)
    }
}
