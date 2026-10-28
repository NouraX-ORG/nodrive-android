/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy

/**
 * What to do when a file is already in NoDrive (round 3 contract, A). Upstream's [NameCollisionPolicy] cannot hold
 * "Smart" (its ordinals are stored in the database), so the account keeps one of these, and the upstream column of every
 * backup row is set to [upstreamPolicy]. Smart uses ASK_USER as its carrier: if the Smart hook ever stops running, the
 * folder falls back to upstream's visible prompt, never to a silent skip and never to an overwrite.
 */
enum class ConflictChoice(val key: String) {
    SMART("smart"),
    KEEP_NODRIVE("keep_nodrive"),
    KEEP_PHONE("keep_phone"),
    KEEP_BOTH("keep_both"),
    ASK("ask");

    /** The value the upstream column of an owned row holds for this choice. The only place that names OVERWRITE. */
    fun upstreamPolicy(): NameCollisionPolicy = when (this) {
        SMART -> NameCollisionPolicy.ASK_USER
        KEEP_NODRIVE -> NameCollisionPolicy.SKIP
        KEEP_PHONE -> NameCollisionPolicy.OVERWRITE
        KEEP_BOTH -> NameCollisionPolicy.RENAME
        ASK -> NameCollisionPolicy.ASK_USER
    }

    companion object {
        /** The dialog of "Resolve all conflicts…" offers these four (Ask makes no sense for conflicts that wait). */
        val RESOLVE_ALL_CHOICES = listOf(SMART, KEEP_NODRIVE, KEEP_PHONE, KEEP_BOTH)

        /** Missing or unknown means Smart, the default. */
        fun fromKey(key: String?): ConflictChoice = entries.firstOrNull { it.key == key } ?: SMART

        /**
         * The choice an owned row shows (in the per-folder Auto upload dialog): the row's own value, except that
         * ASK_USER means "Ask" only while the account's choice is Ask, otherwise it is the Smart carrier.
         */
        fun forOwnedRow(rowPolicy: NameCollisionPolicy?, accountChoice: ConflictChoice): ConflictChoice =
            when (rowPolicy) {
                NameCollisionPolicy.SKIP -> KEEP_NODRIVE
                NameCollisionPolicy.OVERWRITE -> KEEP_PHONE
                NameCollisionPolicy.RENAME -> KEEP_BOTH
                NameCollisionPolicy.ASK_USER -> if (accountChoice == ASK) ASK else SMART
                null -> accountChoice
            }
    }
}
