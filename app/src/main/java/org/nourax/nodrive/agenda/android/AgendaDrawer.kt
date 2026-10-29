/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.android

import android.view.Menu
import android.view.MenuItem
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.nextcloud.client.account.User
import com.owncloud.android.R
import com.owncloud.android.ui.activity.DrawerActivity
import org.nourax.nodrive.agenda.ui.AgendaActivity

/**
 * The "NoAgenda" entry of the side menu: the first item of the Apps group, above the server's Workspace apps. The group
 * itself (id, order) belongs to `WorkspaceAppsDrawer`, which calls this object when it builds the group.
 */
object AgendaDrawer {
    /** Below the first id of the Workspace apps (10,000), above every other id of the menu. */
    const val ITEM_ID = 9_999

    fun enabled(activity: DrawerActivity): Boolean = activity.resources.getBoolean(R.bool.nodrive_agenda)

    /** Adds the entry to [menu] in group [groupId] at [order]; call it before the apps are added, so it stays first. */
    fun addEntry(activity: DrawerActivity, menu: Menu, groupId: Int, order: Int) {
        if (!enabled(activity)) return
        val item = menu.add(groupId, ITEM_ID, order, R.string.nodrive_agenda_name)
        item.isCheckable = true
        ContextCompat.getDrawable(activity, R.drawable.nodrive_ic_agenda)?.let {
            val tinted = DrawableCompat.wrap(it.mutate())
            DrawableCompat.setTint(tinted, ContextCompat.getColor(activity, R.color.drawer_menu_icon))
            item.icon = tinted
        }
        // The menu is rebuilt from time to time; on the NoAgenda screen the entry stays highlighted.
        if (activity is AgendaActivity) item.isChecked = true
    }

    /** True when [item] is the NoAgenda entry (it has been handled). */
    fun onItemClicked(activity: DrawerActivity, item: MenuItem, user: User): Boolean {
        if (item.itemId != ITEM_ID || !enabled(activity)) return false
        activity.closeDrawer()
        if (activity !is AgendaActivity) {
            activity.startActivity(AgendaActivity.intent(activity, user))
        }
        return true
    }
}
