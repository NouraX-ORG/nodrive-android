/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.graphics.drawable.Drawable
import android.view.MenuItem
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.navigation.NavigationView
import com.nextcloud.client.account.User
import com.nextcloud.utils.GlideHelper
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.DrawerActivity
import com.owncloud.android.utils.DrawableUtil
import kotlin.concurrent.thread

/** The Workspace apps group of the side menu: it sits between "Activity" (order 1) and the external links (3). */
object WorkspaceAppsDrawer {
    const val GROUP_ID = 4400
    const val ITEM_BASE = 400
    private const val MENU_ORDER = 2
    private const val MAX_APPS = 50
    private const val ICON_SIZE_PX = 32
    private const val TAG = "WorkspaceAppsDrawer"

    @Volatile
    private var shown: List<WorkspaceApp> = emptyList()

    fun enabled(activity: DrawerActivity): Boolean = activity.resources.getBoolean(R.bool.nodrive_workspace_apps)

    /** Rebuilds the group from the cached list (read off the main thread). */
    @JvmStatic
    fun update(activity: DrawerActivity, navView: NavigationView, user: User) {
        if (!enabled(activity) || user.isAnonymous) return
        thread(name = TAG) {
            val apps = WorkspaceAccess.repository(activity, user).cached().take(MAX_APPS)
            activity.runOnUiThread { if (!activity.isFinishing) show(activity, navView, user, apps) }
        }
    }

    /** Fetches the list from the server (at most every ten minutes) and shows what changed. */
    @JvmStatic
    fun refresh(activity: DrawerActivity, navView: NavigationView?, user: User) {
        if (!enabled(activity) || user.isAnonymous) return
        thread(name = TAG) {
            val outcome = try {
                WorkspaceAccess.repository(activity, user).refresh()
            } catch (e: Exception) {
                Log_OC.w(TAG, "apps list not refreshed: ${e.javaClass.simpleName}")
                WorkspaceRefresh.KEPT
            }
            if (navView != null && outcome in listOf(WorkspaceRefresh.UPDATED, WorkspaceRefresh.CLEARED)) {
                update(activity, navView, user)
            }
        }
    }

    @JvmStatic
    fun onItemClicked(activity: DrawerActivity, item: MenuItem, user: User): Boolean {
        if (item.groupId != GROUP_ID) return false
        val app = shown.getOrNull(item.itemId - ITEM_BASE) ?: return false
        activity.closeDrawer()
        activity.startActivity(WorkspaceWebActivity.intent(activity, user, app.href, app.name))
        return true
    }

    private fun show(activity: DrawerActivity, navView: NavigationView, user: User, apps: List<WorkspaceApp>) {
        shown = apps
        val menu = navView.menu
        menu.removeGroup(GROUP_ID)
        val color = ContextCompat.getColor(activity, R.color.drawer_menu_icon)
        apps.forEachIndexed { index, app ->
            val id = ITEM_BASE + index
            menu.add(GROUP_ID, id, MENU_ORDER, app.name).isCheckable = false
            setIcon(navView, id, ContextCompat.getDrawable(activity, R.drawable.ic_link), color)
            val icon = app.iconUrl ?: return@forEachIndexed
            GlideHelper.loadIntoTarget(activity, user.toOwnCloudAccount(), icon, iconTarget(navView, id, color), R.drawable.ic_link)
        }
    }

    private fun iconTarget(navView: NavigationView, id: Int, color: Int) = object : CustomTarget<Drawable>() {
        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
            val resized = DrawableUtil.getResizedDrawable(navView.context, resource, ICON_SIZE_PX)
            setIcon(navView, id, resized, color)
        }

        override fun onLoadCleared(placeholder: Drawable?) = Unit
    }

    private fun setIcon(navView: NavigationView, id: Int, drawable: Drawable?, color: Int) {
        val item = navView.menu.findItem(id) ?: return
        if (drawable == null) return
        val tinted = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(tinted, color)
        item.icon = tinted
    }
}
