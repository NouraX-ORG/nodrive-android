/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.backup

import android.content.Context
import com.nextcloud.client.core.Clock
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.SyncedFolderProvider

object BackupControllers {
    fun create(context: Context, provider: SyncedFolderProvider, clock: Clock, account: String): BackupController =
        BackupController(
            account,
            PhoneFolders(context),
            SyncedFolderStore(provider, clock),
            BackupState(account, ArbitraryDataProviderImpl(context)),
            PhoneFolders.deviceName(context)
        )

    fun isAvailable(context: Context): Boolean =
        context.resources.getBoolean(com.owncloud.android.R.bool.nodrive_backup)
}
