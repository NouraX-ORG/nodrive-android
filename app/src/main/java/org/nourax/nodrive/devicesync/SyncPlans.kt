/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

/** An object on the phone, as far as the plans need to know it. */
class PhoneRowRef(
    val rowId: Long,
    /** The server address the row is linked to, or null when it was never linked. */
    val syncId: String?,
    val uid: String,
    val dirty: Boolean,
    val deleted: Boolean
)

/** An object on the server. */
class ServerRef(val href: String, val etag: String, val uid: String)

sealed class FirstSyncAction {
    class AddToPhone(val server: ServerRef) : FirstSyncAction()
    class AddToServer(val row: PhoneRowRef) : FirstSyncAction()
    class Link(val row: PhoneRowRef, val server: ServerRef) : FirstSyncAction()
    class Wait(val row: PhoneRowRef) : FirstSyncAction()
}

/**
 * The first sync of a collection only adds (design 10, plan 4.4). There is no delete action in this plan: a missing server object
 * for a row with an old address leads to an add, a deleted phone row waits for the first sync to be complete.
 */
object FirstSyncPlan {
    fun plan(server: List<ServerRef>, phone: List<PhoneRowRef>): List<FirstSyncAction> {
        val out = ArrayList<FirstSyncAction>()
        val linked = HashSet<Long>()
        val byHref = phone.filter { it.syncId != null }.associateBy { it.syncId }
        val byUid = phone.filter { it.syncId == null && it.uid.isNotEmpty() && !it.deleted }.groupBy { it.uid }
        for (s in server) {
            val same = byHref[s.href]
            if (same != null) {
                linked += same.rowId
                continue
            }
            val match = byUid[s.uid]?.firstOrNull { it.rowId !in linked }
            if (match != null && s.uid.isNotEmpty()) {
                linked += match.rowId
                out += FirstSyncAction.Link(match, s)
            } else {
                out += FirstSyncAction.AddToPhone(s)
            }
        }
        for (row in phone) {
            if (row.rowId in linked) continue
            if (row.deleted) {
                out += FirstSyncAction.Wait(row)
            } else if (row.syncId == null || server.none { it.href == row.syncId }) {
                // Never linked, or its object is missing on the server: add it, never delete anything.
                out += FirstSyncAction.AddToServer(row)
            }
        }
        return out
    }
}

enum class ServerState { UNCHANGED, CHANGED, DELETED }

enum class Decision {
    /** Nothing to do. */
    NONE,

    /** The phone's edit goes to the server. */
    UPLOAD_PATCH,

    /** The phone's delete goes to the server. */
    UPLOAD_DELETE,

    /** Both changed: the server version goes to the phone, the phone version is kept in "Not saved". */
    SERVER_WINS_KEEP_PHONE_VERSION,

    /** An edit on the phone, a delete on the server: the object is uploaded again (the edit wins). */
    UPLOAD_AS_NEW,

    /** A delete on the phone, an edit on the server: the server version comes back to the phone (the edit wins). */
    KEEP_SERVER_VERSION,

    /** Deleted on both sides: forget the row. */
    FORGET_ROW,

    /** Only the server changed. */
    APPLY_SERVER,

    /** Only the server deleted it. */
    REMOVE_FROM_PHONE
}

/** The conflict rules after the first sync (plan 4.5): the server wins on the phone, an edit beats a delete, nothing is lost silently. */
object ConflictPlan {
    fun decide(phoneEdited: Boolean, phoneDeleted: Boolean, server: ServerState): Decision = when {
        phoneDeleted -> when (server) {
            ServerState.UNCHANGED -> Decision.UPLOAD_DELETE
            ServerState.CHANGED -> Decision.KEEP_SERVER_VERSION
            ServerState.DELETED -> Decision.FORGET_ROW
        }
        phoneEdited -> when (server) {
            ServerState.UNCHANGED -> Decision.UPLOAD_PATCH
            ServerState.CHANGED -> Decision.SERVER_WINS_KEEP_PHONE_VERSION
            ServerState.DELETED -> Decision.UPLOAD_AS_NEW
        }
        else -> when (server) {
            ServerState.UNCHANGED -> Decision.NONE
            ServerState.CHANGED -> Decision.APPLY_SERVER
            ServerState.DELETED -> Decision.REMOVE_FROM_PHONE
        }
    }
}

/** More than 20 deletes and more than 20 % of the collection, in either direction: nothing is deleted and Android asks the person. */
object DeleteGuard {
    const val MIN_DELETES = 20
    const val PERCENT = 20

    fun tooMany(deletes: Int, collectionSize: Int): Boolean =
        deletes > MIN_DELETES && deletes * 100 > collectionSize.coerceAtLeast(deletes) * PERCENT

    /** What the sync does with the [deletes] it was going to make. */
    fun allow(deletes: Int, collectionSize: Int, overrideTooMany: Boolean): Boolean =
        overrideTooMany || !tooMany(deletes, collectionSize)
}
