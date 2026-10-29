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
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.nextcloud.client.account.User
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.DrawerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nourax.nodrive.agenda.android.AgendaDrawer
import java.util.WeakHashMap

/** The Workspace apps group of the side menu: it sits between "Activity" (order 1) and the external links (3). */
object WorkspaceAppsDrawer {
    const val GROUP_ID = 4400

    /** Far above the ids of the external links (111 + the server's link id). */
    const val ITEM_BASE = 10_000
    private const val MENU_ORDER = 2
    private const val MAX_APPS = 50
    private const val ICON_SIZE_DP = 24
    private const val TAG = "WorkspaceAppsDrawer"

    /** The apps behind the items of each menu; read and written on the main thread only. */
    private val shown = WeakHashMap<NavigationView, List<WorkspaceApp>>()

    fun enabled(activity: DrawerActivity): Boolean = activity.resources.getBoolean(R.bool.nodrive_workspace_apps)

    /**
     * Rebuilds the group from the cached list (read off the main thread). The group also holds NoAgenda, the first item,
     * so it is built when either the Workspace apps or NoAgenda are on.
     */
    @JvmStatic
    fun update(activity: DrawerActivity, navView: NavigationView, user: User) {
        if (!(enabled(activity) || AgendaDrawer.enabled(activity)) || user.isAnonymous) return
        activity.lifecycleScope.launch {
            val apps = if (enabled(activity)) {
                withContext(Dispatchers.IO) { WorkspaceAccess.repository(activity, user).cached().take(MAX_APPS) }
            } else {
                emptyList()
            }
            if (!activity.isFinishing) show(activity, navView, apps)
        }
    }

    /** Fetches the list from the server (at most every ten minutes) and shows what changed. */
    @JvmStatic
    fun refresh(activity: DrawerActivity, navView: NavigationView?, user: User) {
        if (!enabled(activity) || user.isAnonymous) return
        activity.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    WorkspaceAccess.repository(activity, user).refresh()
                } catch (e: Exception) {
                    Log_OC.w(TAG, "apps list not refreshed: ${e.javaClass.simpleName}")
                    WorkspaceRefresh.KEPT
                }
            }
            if (navView != null && outcome in listOf(WorkspaceRefresh.UPDATED, WorkspaceRefresh.CLEARED)) {
                update(activity, navView, user)
            }
        }
    }

    @JvmStatic
    fun onItemClicked(activity: DrawerActivity, item: MenuItem, user: User): Boolean {
        if (item.groupId != GROUP_ID) return false
        if (AgendaDrawer.onItemClicked(activity, item, user)) return true
        val navView = activity.findViewById<NavigationView>(R.id.nav_view) ?: return false
        val app = shown[navView]?.getOrNull(item.itemId - ITEM_BASE) ?: return false
        activity.closeDrawer()
        when (WorkspaceLinkRouter.routeApp(app.href, WorkspaceAccess.baseUrl(user))) {
            WorkspaceAppRoute.SIGNED_IN ->
                activity.startActivity(WorkspaceWebActivity.intent(activity, user, app.href, app.name))
            // Another origin (NoMail): the in-app browser panel, which has its own cookies; no NoDrive credential.
            WorkspaceAppRoute.CUSTOM_TAB -> WorkspaceLinks.openInCustomTab(activity, app.href.toUri())
            WorkspaceAppRoute.IGNORE -> Unit
        }
        return true
    }

    private fun show(activity: DrawerActivity, navView: NavigationView, apps: List<WorkspaceApp>) {
        shown[navView] = apps
        val menu = navView.menu
        menu.removeGroup(GROUP_ID)
        // NoAgenda is always the first item of the group, above the server's apps, also when the server sends none.
        AgendaDrawer.addEntry(activity, menu, GROUP_ID, MENU_ORDER)
        val color = ContextCompat.getColor(activity, R.color.drawer_menu_icon)
        val sizePx = (ICON_SIZE_DP * activity.resources.displayMetrics.density).toInt()
        apps.forEachIndexed { index, app ->
            val id = ITEM_BASE + index
            menu.add(GROUP_ID, id, MENU_ORDER, app.name).isCheckable = false
            setIcon(navView, id, ContextCompat.getDrawable(activity, R.drawable.ic_link), color)
            val icon = app.iconUrl ?: return@forEachIndexed
            try {
                WorkspaceIcons.load(activity, icon) { resource ->
                    setIcon(navView, id, WorkspaceIcons.rasterize(navView.context, resource, sizePx), color)
                }
            } catch (e: Exception) {
                Log_OC.w(TAG, "icon not loaded: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun setIcon(navView: NavigationView, id: Int, drawable: Drawable?, color: Int) {
        val item = navView.menu.findItem(id) ?: return
        if (drawable == null) return
        val tinted = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(tinted, color)
        item.icon = tinted
    }
}
