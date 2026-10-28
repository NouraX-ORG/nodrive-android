/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.owncloud.android.datamodel.ArbitraryDataProvider
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.utils.Log_OC

/** The backup state of one account, kept in the app's ArbitraryDataProvider (as the Workspace apps cache is). */
class BackupState(private val account: String, private val data: ArbitraryDataProvider) {
    fun read(): BackupSnapshot = BackupSnapshot(
        mediaOn = flag(KEY_MEDIA),
        docsOn = flag(KEY_DOCS),
        root = data.getValue(account, KEY_ROOT).takeUnless { it.isNullOrEmpty() },
        owned = readList(KEY_OWNED, LongArray::class.java)?.toSet().orEmpty(),
        seen = readList(KEY_SEEN, Array<String>::class.java)?.toSet().orEmpty()
    )

    fun write(snapshot: BackupSnapshot) {
        data.storeOrUpdateKeyValue(account, KEY_MEDIA, snapshot.mediaOn.toString())
        data.storeOrUpdateKeyValue(account, KEY_DOCS, snapshot.docsOn.toString())
        data.storeOrUpdateKeyValue(account, KEY_ROOT, snapshot.root.orEmpty())
        data.storeOrUpdateKeyValue(account, KEY_OWNED, GSON.toJson(snapshot.owned.toLongArray()))
        data.storeOrUpdateKeyValue(account, KEY_SEEN, GSON.toJson(snapshot.seen.toTypedArray()))
    }

    fun offer(): OfferState = when (data.getValue(account, KEY_OFFER)) {
        OFFER_PENDING -> OfferState.PENDING
        OFFER_DONE -> OfferState.DONE
        else -> OfferState.NONE
    }

    /** Pending only while the account has never had an offer: it is made once, whatever the user answers. */
    fun markOfferPending() {
        if (offer() == OfferState.NONE) data.storeOrUpdateKeyValue(account, KEY_OFFER, OFFER_PENDING)
    }

    fun markOfferDone() = data.storeOrUpdateKeyValue(account, KEY_OFFER, OFFER_DONE)

    private fun flag(key: String) = data.getValue(account, key) == true.toString()

    private fun <T> readList(key: String, type: Class<T>): T? {
        val json = data.getValue(account, key)
        if (json.isNullOrEmpty()) return null
        return try {
            GSON.fromJson(json, type)
        } catch (_: JsonParseException) {
            null
        }
    }

    companion object {
        const val KEY_MEDIA = "nodrive_backup_media"
        const val KEY_DOCS = "nodrive_backup_docs"
        const val KEY_ROOT = "nodrive_backup_root"
        const val KEY_OWNED = "nodrive_backup_owned"
        const val KEY_SEEN = "nodrive_backup_seen"
        const val KEY_OFFER = "nodrive_backup_offer"
        private const val OFFER_PENDING = "pending"
        private const val OFFER_DONE = "done"
        private val ALL_KEYS = listOf(
            KEY_MEDIA,
            KEY_DOCS,
            KEY_ROOT,
            KEY_OWNED,
            KEY_SEEN,
            KEY_OFFER,
            org.nourax.nodrive.conflicts.ConflictSettings.KEY_CHOICE,
            org.nourax.nodrive.conflicts.ConflictSettings.KEY_KEPT
        )
        private val GSON = Gson()

        /** Called when the account is removed; the rows are deleted by upstream's own account removal. */
        /** Never throws: removing an account must not stop because of the backup state. */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun forget(context: Context, accountName: String) {
            try {
                forget(accountName, ArbitraryDataProviderImpl(context))
            } catch (e: RuntimeException) {
                Log_OC.w("BackupState", "backup state not cleared: ${e.javaClass.simpleName}")
            }
        }

        fun forget(accountName: String, data: ArbitraryDataProvider) =
            ALL_KEYS.forEach { data.deleteKeyForAccount(accountName, it) }
    }
}
