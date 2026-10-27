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
import android.provider.Browser
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
import com.google.gson.JsonParser
import com.nextcloud.client.account.UserAccountManagerImpl
import com.nextcloud.client.core.ClockImpl
import com.nextcloud.client.jobs.BackgroundJobManagerImpl
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.test.GrantTestPermissionRule
import com.owncloud.android.R
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
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
            // NoDrive has its own Activity and Photos screens: the menu leaves the server's entries out. The positive
            // control: the server did send both (the raw answer is cached), so the filter did the hiding.
            val raw = checkNotNull(
                WorkspaceAppsDataStore(support.currentAccountName(), ArbitraryDataProviderImpl(support.context)).load()
            ).json
            val hasEntry = { id: String -> Regex("\"id\"\\s*:\\s*\"$id\"").containsMatchIn(raw) }
            assertTrue("the server answer has an activity entry", hasEntry("activity"))
            assertTrue("the server answer has a photos entry", hasEntry("photos"))
            assertFalse(titles.toString(), titles.any { it.equals("Activity", ignoreCase = true) })
            assertFalse(titles.toString(), titles.any { it.equals("Photos", ignoreCase = true) })
            // The throwaway server has no nourax_branding app: add its NoMail entry (another origin) to the cached
            // answer, the way the production server lists it, and show the menu again from that cache.
            addNoMailToTheCachedAnswer()
            scenario.onActivity { activity ->
                WorkspaceAppsDrawer.update(
                    activity,
                    activity.findViewById(R.id.nav_view),
                    UserAccountManagerImpl.fromContext(support.context).user
                )
            }
            val withNoMail = support.waitFor("NoMail in the side menu", MENU_TIMEOUT_MILLIS) {
                workspaceTitles(scenario).takeIf { NOMAIL_TITLE in it }
            }
            assertEquals("NoMail comes last, as in the server's list", NOMAIL_TITLE, withNoMail.last())
            assertTrue(withNoMail.toString(), withNoMail.containsAll(titles))
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

            // Last, so that nothing can come between the page checks above and a stubbed browser panel.
            assertNoMailOpensInACustomTab(scenario)
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

    private fun addNoMailToTheCachedAnswer() {
        val store = WorkspaceAppsDataStore(support.currentAccountName(), ArbitraryDataProviderImpl(support.context))
        val cache = checkNotNull(store.load())
        val root = JsonParser.parseString(cache.json).asJsonObject
        val data = root.getAsJsonObject("ocs").getAsJsonArray("data")
        if (data.none { it.asJsonObject.get("id")?.asString == "nourax_nomail" }) {
            data.add(
                JsonParser.parseString(
                    """{"id":"nourax_nomail","order":9,"href":"$NOMAIL_URL","type":"link","name":"$NOMAIL_TITLE",
                    "icon":"/apps/dashboard/img/app.svg","app":"nourax_branding","active":false,"classes":"","unread":0}"""
                )
            )
        }
        store.save(cache.copy(json = root.toString(), fetchedAtMillis = System.currentTimeMillis()))
    }

    /** A tap on NoMail opens its URL in the browser panel with nothing of the account, and not in the signed-in page. */
    private fun assertNoMailOpensInACustomTab(scenario: ActivityScenario<FileDisplayActivity>) {
        val target = Uri.parse(NOMAIL_URL)
        intending(allOf(hasAction(Intent.ACTION_VIEW), hasData(target)))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        // The drawer runs a tap after it has closed, so it has to be open first.
        scenario.onActivity { it.openDrawer() }
        support.sleepForAnimation()
        tap(scenario, NOMAIL_TITLE)
        val launched = support.waitFor("a Custom Tab intent for $NOMAIL_URL", INTENT_TIMEOUT_MILLIS) {
            Intents.getIntents().firstOrNull { it.action == Intent.ACTION_VIEW && it.data == target }
        }
        // The Custom Tabs library adds a language header itself; nothing of the account may be in the headers.
        val headers = launched.getBundleExtra(Browser.EXTRA_HEADERS)?.keySet().orEmpty().map { it.lowercase() }
        assertTrue("no credential header for NoMail: $headers", headers.none { it == "authorization" || it == "cookie" })
        assertFalse("not an in-app page", launched.component?.className.orEmpty().contains("WorkspaceWebActivity"))
        assertEquals("no NoDrive page was opened for NoMail", null, support.workspaceActivity())
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
        const val NOMAIL_TITLE = "NoMail"
        const val NOMAIL_URL = "https://nomail.invalid/" // never resolves: the test must not reach any real site
    }
}
