/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.app.Activity
import android.content.Context
import com.nextcloud.client.account.User
import com.nextcloud.common.NextcloudClient
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory

/** Wires the pure parts (repository, bootstrap) to the account of the running app. */
object WorkspaceAccess {
    fun baseUrl(user: User): String = user.server.uri.toString()

    fun client(context: Context, user: User): NextcloudClient =
        OwnCloudClientManagerFactory.getDefaultSingleton().getNextcloudClientFor(user.toOwnCloudAccount(), context)

    fun repository(activity: Activity, user: User): WorkspaceAppsRepository {
        val context = activity.applicationContext
        return WorkspaceAppsRepository(
            baseUrl(user),
            WorkspaceAppsDataStore(user.accountName, ArbitraryDataProviderImpl(context)),
            WorkspaceAppsTransport { etag -> WorkspaceAppsHttpTransport(client(context, user)).get(etag) }
        )
    }
}
