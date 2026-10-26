/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.account.UserAccountManagerImpl
import com.owncloud.android.MainApp
import com.owncloud.android.lib.common.accounts.AccountUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Account, polling, screenshots and web view access for WorkspaceAppsIT. */
class WorkspaceTestSupport {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    val context: Context = instrumentation.targetContext

    val serverUrl: String? get() = arguments.getString(ARG_URL)
    val userId: String? get() = arguments.getString(ARG_USER)
    val look: String get() = arguments.getString(ARG_LOOK) ?: "light"

    fun appPassword(removalTest: Boolean): String? =
        arguments.getString(if (removalTest) ARG_REMOVE_PASSWORD else ARG_PASSWORD)

    fun addAccount(password: String): Account {
        val manager = AccountManager.get(context)
        val type = MainApp.getAccountType(context)
        manager.getAccountsByType(type).forEach { manager.removeAccountExplicitly(it) }
        val account = Account("$userId@$serverUrl", type)
        check(manager.addAccountExplicitly(account, password, null)) { "account not added" }
        manager.setUserData(
            account,
            AccountUtils.Constants.KEY_OC_ACCOUNT_VERSION,
            UserAccountManager.ACCOUNT_VERSION.toString()
        )
        manager.setUserData(account, AccountUtils.Constants.KEY_OC_VERSION, SERVER_VERSION)
        manager.setUserData(account, AccountUtils.Constants.KEY_OC_BASE_URL, serverUrl)
        manager.setUserData(account, AccountUtils.Constants.KEY_USER_ID, userId)
        check(UserAccountManagerImpl.fromContext(context).setCurrentOwnCloudAccount(account.name))
        return account
    }

    fun currentAccountName(): String =
        AccountManager.get(context).getAccountsByType(MainApp.getAccountType(context)).firstOrNull()?.name.orEmpty()

    fun sleepForAnimation() = SystemClock.sleep(ANIMATION_MILLIS)

    fun <T : Any> waitFor(what: String, timeoutMillis: Long, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            probe()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("timed out after ${timeoutMillis / MILLIS_PER_SECOND} s: $what")
    }

    fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun workspaceActivity(): WorkspaceWebActivity? = onMainResult {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is WorkspaceWebActivity } as WorkspaceWebActivity?
    }

    fun webViewOf(activity: Activity): WebView = activity.findViewById(WEB_VIEW_ID)

    fun evaluate(web: WebView, script: String): String? {
        val latch = CountDownLatch(1)
        var value: String? = null
        onMain {
            web.evaluateJavascript(script) {
                value = it
                latch.countDown()
            }
        }
        latch.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return value
    }

    fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        val file = File(folder, "workspace-$name-$look.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    companion object {
        const val ARG_URL = "NODRIVE_WS_URL"
        const val ARG_USER = "NODRIVE_WS_USER"
        const val ARG_PASSWORD = "NODRIVE_WS_APP_PASSWORD"
        const val ARG_REMOVE_PASSWORD = "NODRIVE_WS_REMOVE_PASSWORD"
        const val ARG_LOOK = "NODRIVE_WS_LOOK"
        private const val SERVER_VERSION = "35.0.2.1"
        private const val POLL_MILLIS = 500L
        private const val ANIMATION_MILLIS = 1500L
        private const val MILLIS_PER_SECOND = 1000L
        private const val JS_TIMEOUT_SECONDS = 15L
        private const val PNG_QUALITY = 100
        private val WEB_VIEW_ID = com.owncloud.android.R.id.webView
    }
}
