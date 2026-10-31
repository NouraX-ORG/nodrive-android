/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync

import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.devicesync.dav.CardDavClient
import org.nourax.nodrive.devicesync.store.CollectionEntity
import org.nourax.nodrive.devicesync.store.DeviceSyncDao
import java.time.Instant
import java.time.ZoneId

/** What Android asked of this sync (the extras of the sync request). */
class SyncFlags(
    /** `SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS`: the person said go ahead with the deletions. */
    val overrideTooManyDeletions: Boolean = false,
    /** `SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS`: the person said undo the deletions made on the phone. */
    val discardLocalDeletions: Boolean = false
)

/** Everything one sync of one account needs: the server, the database and the clock (a seam for tests). */
class SyncEnv(
    val account: String,
    val http: AgendaHttp,
    val dav: CardDavClient,
    val dao: DeviceSyncDao,
    val zone: ZoneId,
    val clock: () -> Instant = { Instant.now() },
    val flags: SyncFlags = SyncFlags(),
    /** The address book that gets the contacts made on the phone (several can be chosen, a new contact goes to one); null = any. */
    val newContactsBook: String? = null
) {
    fun withNewContactsBook(href: String?) = SyncEnv(account, http, dav, dao, zone, clock, flags, href)
}

/** The same collection row with some fields changed. */
fun CollectionEntity.with(
    syncToken: String = this.syncToken,
    androidId: Long = this.androidId,
    firstSyncDone: Boolean = this.firstSyncDone,
    objectCount: Int = this.objectCount,
    chosen: Boolean = this.chosen,
    name: String = this.name,
    color: String = this.color,
    readOnly: Boolean = this.readOnly,
    offReason: String = this.offReason
) = CollectionEntity(account, href, kind, name, color, readOnly, offReason, chosen, syncToken, androidId, firstSyncDone, objectCount)
