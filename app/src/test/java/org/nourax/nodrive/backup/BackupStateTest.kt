/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import com.nextcloud.client.account.User
import com.owncloud.android.datamodel.ArbitraryDataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStateTest {
    class MemoryData : ArbitraryDataProvider {
        val values = mutableMapOf<String, String>()

        private fun key(account: String, key: String) = "$account|$key"

        override fun deleteKeyForAccount(account: String, key: String) {
            values.remove(key(account, key))
        }

        override fun storeOrUpdateKeyValue(accountName: String, key: String, newValue: Long) {
            values[key(accountName, key)] = newValue.toString()
        }

        override fun incrementValue(accountName: String, key: String) = Unit

        override fun storeOrUpdateKeyValue(accountName: String, key: String, newValue: Boolean) {
            values[key(accountName, key)] = newValue.toString()
        }

        override fun storeOrUpdateKeyValue(accountName: String, key: String, newValue: String) {
            values[key(accountName, key)] = newValue
        }

        override fun storeOrUpdateKeyValue(user: User, key: String, newValue: String) = Unit

        override fun getLongValue(accountName: String, key: String) = values[key(accountName, key)]?.toLong() ?: -1L

        override fun getLongValue(user: User, key: String) = -1L

        override fun getBooleanValue(accountName: String, key: String) = values[key(accountName, key)] == "true"

        override fun getBooleanValue(user: User, key: String) = false

        override fun getIntegerValue(accountName: String, key: String) = -1

        override fun getValue(user: User?, key: String) = ""

        override fun getValue(accountName: String, key: String) = values[key(accountName, key)].orEmpty()
    }

    private val data = MemoryData()
    private val state = BackupState("a@h", data)

    @Test
    fun emptyStateReadsAsAllOff() {
        assertEquals(BackupSnapshot(), state.read())
    }

    @Test
    fun snapshotSurvivesARoundTrip() {
        val snapshot = BackupSnapshot(
            mediaOn = true,
            docsOn = true,
            root = "/Phone backup/Pixel 8",
            owned = setOf(3L, 7L),
            seen = setOf("1:/storage/emulated/0/DCIM/Camera", "2:/storage/emulated/0/Movies")
        )
        state.write(snapshot)
        assertEquals(snapshot, state.read())
    }

    @Test
    fun brokenJsonReadsAsEmptyLists() {
        data.storeOrUpdateKeyValue("a@h", BackupState.KEY_OWNED, "{not json")
        data.storeOrUpdateKeyValue("a@h", BackupState.KEY_SEEN, "[1,")
        assertEquals(emptySet<Long>(), state.read().owned)
        assertEquals(emptySet<String>(), state.read().seen)
    }

    @Test
    fun forgetClearsAllSixKeysOfThatAccountOnly() {
        state.write(BackupSnapshot(mediaOn = true, docsOn = true, root = "/r", owned = setOf(1L), seen = setOf("k")))
        state.markOfferPending()
        val other = BackupState("b@h", data)
        other.write(BackupSnapshot(mediaOn = true))

        BackupState.forget("a@h", data)

        assertTrue(data.values.keys.none { it.startsWith("a@h|") })
        assertTrue(other.read().mediaOn)
        assertEquals(6, listOf(
            BackupState.KEY_MEDIA, BackupState.KEY_DOCS, BackupState.KEY_ROOT,
            BackupState.KEY_OWNED, BackupState.KEY_SEEN, BackupState.KEY_OFFER
        ).distinct().size)
    }

    @Test
    fun offerGoesPendingThenDoneAndNeverPendingAgain() {
        assertEquals(OfferState.NONE, state.offer())
        state.markOfferPending()
        assertEquals(OfferState.PENDING, state.offer())
        state.markOfferDone()
        assertEquals(OfferState.DONE, state.offer())
        state.markOfferPending()
        assertEquals(OfferState.DONE, state.offer())
        assertFalse(state.offer() == OfferState.PENDING)
    }
}
