/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.nextcloud.client.account.User
import com.owncloud.android.R
import com.owncloud.android.utils.DisplayUtils

/** Links of the notification list (design rule 9): the account's own server opens inside the app. */
object WorkspaceLinks {
    fun open(activity: Activity, user: User, url: String?) {
        if (!activity.resources.getBoolean(R.bool.nodrive_workspace_apps)) {
            DisplayUtils.startLinkIntent(activity, url)
            return
        }
        when (WorkspaceLinkRouter.route(url, WorkspaceAccess.baseUrl(user))) {
            WorkspaceRoute.IN_APP ->
                activity.startActivity(WorkspaceWebActivity.intent(activity, user, url.orEmpty().trim(), null))
            WorkspaceRoute.EXTERNAL -> DisplayUtils.startLinkIntent(activity, url)
            WorkspaceRoute.IGNORE -> Unit
        }
    }

    fun openInCustomTab(activity: Activity, uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            DisplayUtils.startLinkIntent(activity, uri)
        }
    }
}
