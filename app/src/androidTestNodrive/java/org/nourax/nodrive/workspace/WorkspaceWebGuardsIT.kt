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
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.navigation.NavigationView
import com.nextcloud.client.preferences.AppPreferencesImpl
import com.nextcloud.test.GrantTestPermissionRule
import com.owncloud.android.R
import com.owncloud.android.ui.activity.FileDisplayActivity
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Against the same throwaway Nextcloud 35 server as WorkspaceAppsIT: the guards of the in-app page. Loads that
 * skip the link check, windows opened by the page and an expired session.
 */
class WorkspaceWebGuardsIT {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantTestPermissionRule.grantStorageAndNotification()

    private val support = WorkspaceTestSupport()

    @Before
    fun requireServer() {
        assumeTrue("needs the CI test server", support.serverUrl != null && support.userId != null)
        AppPreferencesImpl.fromContext(support.context).setShowEcosystemApps(false)
    }

    @After
    fun cleanUp() {
        runCatching { Intents.release() }
    }

    @Test
    fun aLoadThatSkipsTheLinkCheckStillStaysOutOfTheApp() {
        withDashboard { web, _ ->
            val before = support.onMainResult { web.url }
            val foreign = "http://10.0.2.2:$FOREIGN_PORT/direct"
            stubBrowser(foreign)
            support.onMain { web.loadUrl(foreign) }
            awaitBrowserIntent(foreign)
            SystemClock.sleep(SETTLE_MILLIS)
            assertEquals("the in-app page must stay where it was", before, support.onMainResult { web.url })
        }
    }

    @Test
    fun aWindowOpenedByThePageGoesToTheBrowser() {
        withDashboard { web, _ ->
            val before = support.onMainResult { web.url }
            val foreign = "http://10.0.2.2:$FOREIGN_PORT/popup"
            stubBrowser(foreign)
            support.onMain { web.settings.javaScriptCanOpenWindowsAutomatically = true }
            support.evaluate(web, "window.open('$foreign'); 1")
            awaitBrowserIntent(foreign)
            SystemClock.sleep(SETTLE_MILLIS)
            assertEquals("the in-app page must stay where it was", before, support.onMainResult { web.url })
        }
    }

    @Test
    fun anExpiredSessionSignsInAgainOnce() {
        withDashboard { web, signedInAt ->
            val wait = RELOGIN_GUARD_MILLIS - (SystemClock.elapsedRealtime() - signedInAt)
            if (wait > 0) SystemClock.sleep(wait)
            support.onMain {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            support.waitFor("the session cookies gone", PAGE_TIMEOUT_MILLIS) {
                true.takeIf { CookieManager.getInstance().getCookie(support.serverUrl).isNullOrEmpty() }
            }
            support.evaluate(web, "window.staleDocument = true; location.reload(); 1")
            support.waitFor("signed in again as ${support.userId}", PAGE_TIMEOUT_MILLIS) {
                val fresh = support.evaluate(web, "typeof window.staleDocument === 'undefined'") == "true"
                val cookies = CookieManager.getInstance().getCookie(support.serverUrl).orEmpty()
                signedInPath(web).takeIf { fresh && cookies.isNotEmpty() }
            }
        }
    }

    private fun withDashboard(body: (web: WebView, signedInAt: Long) -> Unit) {
        support.addAccount(checkNotNull(support.appPassword(removalTest = false)))
        Intents.init()
        ActivityScenario.launch(FileDisplayActivity::class.java).use { scenario ->
            scenario.onActivity { it.openDrawer() }
            support.waitFor("Workspace apps in the side menu", MENU_TIMEOUT_MILLIS) { dashboardItem(scenario) }
            val itemId = checkNotNull(dashboardItem(scenario))
            scenario.onActivity { activity ->
                activity.findViewById<NavigationView>(R.id.nav_view).menu.performIdentifierAction(itemId, 0)
            }
            val activity = support.waitFor("the in-app page", MENU_TIMEOUT_MILLIS) { support.workspaceActivity() }
            val web = support.webViewOf(activity)
            support.waitFor("page signed in as ${support.userId}", PAGE_TIMEOUT_MILLIS) { signedInPath(web) }
            val signedInAt = SystemClock.elapsedRealtime()
            try {
                body(web, signedInAt)
            } finally {
                support.onMain { activity.finish() }
            }
        }
    }

    private fun dashboardItem(scenario: ActivityScenario<FileDisplayActivity>): Int? {
        var id: Int? = null
        scenario.onActivity { activity ->
            val menu = activity.findViewById<NavigationView>(R.id.nav_view).menu
            id = (0 until menu.size()).map(menu::getItem)
                .firstOrNull { it.groupId == WorkspaceAppsDrawer.GROUP_ID && it.title.toString() == "Dashboard" }?.itemId
        }
        return id
    }

    private fun signedInPath(web: WebView): String? {
        val user = support.evaluate(web, "(document.head && document.head.dataset.user) || ''")
        val path = support.onMainResult { Uri.parse(web.url ?: "").path.orEmpty() }
        val onPage = !path.contains("login") && !path.contains("unsupported")
        return path.takeIf { user == "\"${support.userId}\"" && onPage }
    }

    private fun stubBrowser(url: String) {
        intending(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(url))))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    private fun awaitBrowserIntent(url: String) {
        support.waitFor("a browser intent for $url", INTENT_TIMEOUT_MILLIS) {
            Intents.getIntents().firstOrNull { it.action == Intent.ACTION_VIEW && it.data == Uri.parse(url) }
        }
        assertTrue(Intents.getIntents().any { it.data == Uri.parse(url) })
    }

    private companion object {
        const val MENU_TIMEOUT_MILLIS = 90_000L
        const val PAGE_TIMEOUT_MILLIS = 120_000L
        const val INTENT_TIMEOUT_MILLIS = 20_000L
        const val SETTLE_MILLIS = 2_000L
        const val RELOGIN_GUARD_MILLIS = WorkspaceNavigationPolicy.RELOGIN_MIN_INTERVAL_MILLIS + 2_000L
        const val FOREIGN_PORT = 8081
    }
}
