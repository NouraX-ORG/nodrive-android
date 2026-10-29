/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import androidx.test.core.app.ActivityScenario
import com.google.android.material.navigation.NavigationView
import com.nextcloud.test.GrantTestPermissionRule
import com.owncloud.android.R
import com.owncloud.android.ui.activity.FileDisplayActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.nourax.nodrive.agenda.android.AgendaDrawer
import org.nourax.nodrive.agenda.ui.AgendaActivity
import org.nourax.nodrive.workspace.WorkspaceAppsDrawer

/**
 * Against the throwaway server: the side menu shows "NoAgenda" as the first item of the Apps group, above the apps that the server
 * sends; a tap opens NoAgenda, and it is highlighted there.
 */
class AgendaDrawerIT {
    @get:Rule
    val permissions = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = AgendaTestSupport()

    @Before
    fun setUp() {
        assumeTrue("needs the throwaway server", support.hasServer)
        support.reset()
    }

    @After
    fun tearDown() {
        support.reset()
    }

    private fun <A : android.app.Activity> appsGroup(scenario: ActivityScenario<A>): List<Pair<Int, String>> {
        var titles = emptyList<Pair<Int, String>>()
        scenario.onActivity { activity ->
            val menu = activity.findViewById<NavigationView>(R.id.nav_view).menu
            titles = (0 until menu.size()).map(menu::getItem).filter { it.groupId == WorkspaceAppsDrawer.GROUP_ID }.map { it.itemId to it.title.toString() }
        }
        return titles
    }

    @Test
    fun noAgendaIsTheFirstItemOfTheAppsGroupAndOpensTheScreen() {
        support.addServerAccount("ali")
        ActivityScenario.launch(FileDisplayActivity::class.java).use { scenario ->
            scenario.onActivity { it.openDrawer() }
            // the apps of the server come after NoAgenda
            val items = support.backup.waitFor("NoAgenda and the server's apps in the side menu", MENU_TIMEOUT_MILLIS) {
                appsGroup(scenario).takeIf { list -> list.size >= 2 }
            }
            assertEquals(items.toString(), "NoAgenda", items.first().second)
            assertEquals(AgendaDrawer.ITEM_ID, items.first().first)
            assertTrue(items.toString(), items.drop(1).any { it.second == "Dashboard" })
            assertFalse(items.toString(), items.drop(1).any { it.second == "NoAgenda" })
            support.backup.sleepForAnimation()
            support.screenshot("menu")

            // a tap opens NoAgenda for the current account
            scenario.onActivity { activity ->
                activity.findViewById<NavigationView>(R.id.nav_view).menu.performIdentifierAction(AgendaDrawer.ITEM_ID, 0)
            }
            val agenda = support.backup.waitFor("the NoAgenda screen", MENU_TIMEOUT_MILLIS) { support.backup.resumed<AgendaActivity>() }
            assertNotNull(agenda)
            // it is highlighted in its own side menu
            val checked = support.backup.waitFor("NoAgenda highlighted", MENU_TIMEOUT_MILLIS) {
                support.backup.onMainResult {
                    agenda.findViewById<NavigationView>(R.id.nav_view).menu.findItem(AgendaDrawer.ITEM_ID)?.isChecked?.takeIf { it }
                }
            }
            assertTrue(checked)
            support.backup.onMain { agenda.finish() }
        }
    }

    private companion object {
        const val MENU_TIMEOUT_MILLIS = 90_000L
    }
}
