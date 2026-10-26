/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import com.owncloud.android.datamodel.ArbitraryDataProvider

class WorkspaceAppsDataStore(
    private val accountName: String,
    private val data: ArbitraryDataProvider
) : WorkspaceAppsStore {
    override fun load(): WorkspaceAppsCache? {
        val json = data.getValue(accountName, KEY_JSON)
        if (json.isNullOrEmpty()) return null
        return WorkspaceAppsCache(
            json,
            data.getValue(accountName, KEY_ETAG).takeUnless { it.isNullOrEmpty() },
            data.getLongValue(accountName, KEY_TIME)
        )
    }

    override fun save(cache: WorkspaceAppsCache) {
        data.storeOrUpdateKeyValue(accountName, KEY_JSON, cache.json)
        data.storeOrUpdateKeyValue(accountName, KEY_ETAG, cache.etag.orEmpty())
        data.storeOrUpdateKeyValue(accountName, KEY_TIME, cache.fetchedAtMillis)
    }

    override fun clear() = forget(accountName, data)

    companion object {
        private const val KEY_JSON = "nodrive_workspace_apps_json"
        private const val KEY_ETAG = "nodrive_workspace_apps_etag"
        private const val KEY_TIME = "nodrive_workspace_apps_time"

        fun forget(accountName: String, data: ArbitraryDataProvider) {
            listOf(KEY_JSON, KEY_ETAG, KEY_TIME).forEach { data.deleteKeyForAccount(accountName, it) }
        }
    }
}
