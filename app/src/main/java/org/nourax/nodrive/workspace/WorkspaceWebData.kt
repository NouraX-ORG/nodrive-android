/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.nextcloud.client.account.User
import com.owncloud.android.datamodel.ArbitraryDataProvider
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.utils.FilesSyncHelper

/**
 * The in-app web view keeps the session of one account at a time. Whatever the web view stored for the
 * server (cookies, local storage) is removed when the account changes, signs out or is removed.
 */
object WorkspaceWebData {
    private const val TAG = "WorkspaceWebData"
    private const val KEY_ACCOUNT = "nodrive_workspace_web_account"

    /** Call before a session is handed to the web view: data of another account is wiped first. */
    fun prepareFor(context: Context, accountName: String) {
        val data = ArbitraryDataProviderImpl(context)
        if (data.getValue(FilesSyncHelper.GLOBAL, KEY_ACCOUNT) != accountName) {
            wipe(context)
        }
        data.storeOrUpdateKeyValue(FilesSyncHelper.GLOBAL, KEY_ACCOUNT, accountName)
    }

    fun wipe(context: Context) {
        try {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            Handler(Looper.getMainLooper()).post { WebStorage.getInstance().deleteAllData() }
        } catch (e: RuntimeException) {
            Log_OC.w(TAG, "web view data not cleared: ${e.javaClass.simpleName}")
        }
        ArbitraryDataProviderImpl(context).deleteKeyForAccount(FilesSyncHelper.GLOBAL, KEY_ACCOUNT)
    }

    fun onAccountRemoved(context: Context, user: User) {
        val data: ArbitraryDataProvider = ArbitraryDataProviderImpl(context)
        WorkspaceAppsDataStore.forget(user.accountName, data)
        wipe(context)
    }
}
