/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import com.google.android.material.navigation.NavigationView
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.test.GrantTestPermissionRule
import com.owncloud.android.R
import com.owncloud.android.ui.activity.FileDisplayActivity
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Against a real Nextcloud 35 server (CI only; skipped without NODRIVE_WS_* arguments): the Workspace apps
 * appear in the side menu without Files, a tap opens the page inside the app already signed in, a link to
 * another host goes to the browser and never carries the account, and removing the account clears the web data.
 */
class WorkspaceAppsIT {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = WorkspaceTestSupport()

    @Before
    fun requireServer() {
        assumeTrue("needs the CI test server", support.serverUrl != null && support.userId != null)
        assertTrue(support.context.resources.getBoolean(R.bool.nodrive_workspace_apps))
        AppPreferencesImpl.fromContext(support.context).setShowEcosystemApps(false)
    }

    @After
    fun cleanUp() {
        runCatching { Intents.release() }
    }

    @Test
    fun menuListsTheWorkspaceAppsAndTapOpensASignedInPage() {
        support.addAccount(checkNotNull(support.appPassword(removalTest = false)))
        Intents.init()
        ActivityScenario.launch(FileDisplayActivity::class.java).use { scenario ->
            scenario.onActivity { it.openDrawer() }
            val titles = support.waitFor("Workspace apps in the side menu", MENU_TIMEOUT_MILLIS) {
                workspaceTitles(scenario).takeIf { it.isNotEmpty() }
            }
            assertTrue(titles.toString(), titles.contains("Dashboard"))
            assertFalse(titles.toString(), titles.any { it.equals("Files", ignoreCase = true) })
            support.sleepForAnimation()
            support.screenshot("menu")

            tap(scenario, "Dashboard")
            val activity = support.waitFor("the in-app page", MENU_TIMEOUT_MILLIS) { support.workspaceActivity() }
            val web = support.webViewOf(activity)
            support.waitFor("page signed in as ${support.userId}", PAGE_TIMEOUT_MILLIS) {
                val user = support.evaluate(web, "(document.head && document.head.dataset.user) || ''")
                val path = support.onMainResult { Uri.parse(web.url ?: "").path.orEmpty() }
                val onPage = !path.contains("login") && !path.contains("unsupported")
                path.takeIf { user == "\"${support.userId}\"" && onPage }
            }
            // Only for a screenshot that shows the page itself: the dashboard greets the user by display name.
            runCatching {
                support.waitFor("the dashboard greeting", CONTENT_TIMEOUT_MILLIS) {
                    support.evaluate(web, "document.body.innerText.indexOf('Demo User') >= 0").takeIf { it == "true" }
                }
            }
            support.sleepForAnimation()
            support.screenshot("page")

            assertOtherHostGoesToTheBrowser(web)
            support.onMain { activity.finish() }
        }
    }

    @Test
    fun removingTheAccountClearsTheWebData() {
        support.addAccount(checkNotNull(support.appPassword(removalTest = true)))
        ActivityScenario.launch(FileDisplayActivity::class.java).use { scenario ->
            scenario.onActivity { it.openDrawer() }
            support.waitFor("Workspace apps in the side menu", MENU_TIMEOUT_MILLIS) {
                workspaceTitles(scenario).takeIf { it.isNotEmpty() }
            }
            tap(scenario, "Dashboard")
            val activity = support.waitFor("the in-app page", MENU_TIMEOUT_MILLIS) { support.workspaceActivity() }
            val web = support.webViewOf(activity)
            support.waitFor("session cookies in the web view", PAGE_TIMEOUT_MILLIS) {
                CookieManager.getInstance().getCookie(support.serverUrl)?.takeIf { it.isNotEmpty() }
            }
            support.onMain { activity.finish() }
            assertNotNull(web)
        }

        val account = support.currentAccountName()
        val jobs = BackgroundJobManagerImpl(
            WorkManager.getInstance(support.context),
            ClockImpl(),
            AppPreferencesImpl.fromContext(support.context)
        )
        jobs.startAccountRemovalJob(account, false)
        support.waitFor("web data cleared after the account removal", REMOVAL_TIMEOUT_MILLIS) {
            true.takeIf { CookieManager.getInstance().getCookie(support.serverUrl).isNullOrEmpty() }
        }
        support.waitFor("the account is gone", REMOVAL_TIMEOUT_MILLIS) {
            true.takeIf { support.currentAccountName() == "" }
        }
    }

    private fun workspaceTitles(scenario: ActivityScenario<FileDisplayActivity>): List<String> {
        var titles = emptyList<String>()
        scenario.onActivity { activity ->
            val menu = activity.findViewById<NavigationView>(R.id.nav_view).menu
            titles = (0 until menu.size()).map(menu::getItem)
                .filter { it.groupId == WorkspaceAppsDrawer.GROUP_ID }
                .map { it.title.toString() }
        }
        return titles
    }

    private fun tap(scenario: ActivityScenario<FileDisplayActivity>, title: String) {
        scenario.onActivity { activity ->
            val menu = activity.findViewById<NavigationView>(R.id.nav_view).menu
            val item = (0 until menu.size()).map(menu::getItem)
                .first { it.groupId == WorkspaceAppsDrawer.GROUP_ID && it.title.toString() == title }
            menu.performIdentifierAction(item.itemId, 0)
        }
    }

    private fun assertOtherHostGoesToTheBrowser(web: WebView) {
        val before = support.onMainResult { web.url }
        val foreign = "http://localhost:$FOREIGN_PORT/probe"
        intending(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(foreign))))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        support.evaluate(web, "location.href='$foreign'")
        support.waitFor("a browser intent for $foreign", INTENT_TIMEOUT_MILLIS) {
            Intents.getIntents().firstOrNull { it.action == Intent.ACTION_VIEW && it.data == Uri.parse(foreign) }
        }
        assertEquals("the in-app page must stay where it was", before, support.onMainResult { web.url })
    }

    private companion object {
        const val MENU_TIMEOUT_MILLIS = 90_000L
        const val PAGE_TIMEOUT_MILLIS = 120_000L
        const val CONTENT_TIMEOUT_MILLIS = 60_000L
        const val REMOVAL_TIMEOUT_MILLIS = 60_000L
        const val INTENT_TIMEOUT_MILLIS = 20_000L
        const val FOREIGN_PORT = 8081
    }
}
