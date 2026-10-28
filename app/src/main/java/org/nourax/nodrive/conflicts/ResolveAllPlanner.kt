/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import com.owncloud.android.files.services.NameCollisionPolicy

/** One upload that waits for a decision: status "failed", last result SYNC_CONFLICT. */
data class WaitingConflict(
    val uploadId: Long,
    val localPath: String,
    val remotePath: String,
    val localExists: Boolean,
    val localBytes: Long,
    /** True while the file is being uploaded right now. */
    val uploading: Boolean = false
)

/**
 * What "Resolve all conflicts…" does with each waiting conflict. There is no delete action here, by design: no phone file
 * and no Drive file is ever removed. [Requeue] uploads again with the given policy (and never touches the phone file).
 */
sealed interface ResolveAction {
    val uploadId: Long

    /** Counted as done: status "succeeded", result "skipped". Nothing is uploaded. */
    data class MarkDone(override val uploadId: Long) : ResolveAction

    data class Requeue(override val uploadId: Long, val policy: NameCollisionPolicy) : ResolveAction

    /** Stays waiting; [reason] is for the log only. */
    data class Leave(override val uploadId: Long, val reason: String) : ResolveAction
}

object ResolveAllPlanner {
    /**
     * @param remoteSize the size lookup for Smart (a PROPFIND); it is only called for the Smart choice.
     */
    fun <F> plan(
        choice: ConflictChoice,
        conflicts: List<WaitingConflict>,
        remoteSize: (WaitingConflict) -> RemoteSize<F>
    ): List<ResolveAction> = conflicts.map { conflict ->
        when {
            conflict.uploading -> ResolveAction.Leave(conflict.uploadId, "uploading now")
            choice == ConflictChoice.KEEP_NODRIVE -> ResolveAction.MarkDone(conflict.uploadId)
            !conflict.localExists -> ResolveAction.Leave(conflict.uploadId, "the phone file is gone")
            else -> when (choice) {
                ConflictChoice.KEEP_PHONE -> ResolveAction.Requeue(conflict.uploadId, choice.upstreamPolicy())
                ConflictChoice.KEEP_BOTH -> ResolveAction.Requeue(conflict.uploadId, NameCollisionPolicy.RENAME)
                ConflictChoice.SMART -> smart(conflict, remoteSize(conflict))
                // "Ask" is not offered for waiting conflicts; the per-file flow stays for them.
                ConflictChoice.ASK, ConflictChoice.KEEP_NODRIVE -> ResolveAction.Leave(conflict.uploadId, "not a choice here")
            }
        }
    }

    private fun <F> smart(conflict: WaitingConflict, remote: RemoteSize<F>): ResolveAction =
        when (SmartRule.decide(conflict.localBytes, remote)) {
            SmartDecision.AlreadyBackedUp -> ResolveAction.MarkDone(conflict.uploadId)
            SmartDecision.KeepBoth -> ResolveAction.Requeue(conflict.uploadId, NameCollisionPolicy.RENAME)
            is SmartDecision.Retry -> ResolveAction.Leave(conflict.uploadId, "the size on the Drive could not be read")
        }
}
