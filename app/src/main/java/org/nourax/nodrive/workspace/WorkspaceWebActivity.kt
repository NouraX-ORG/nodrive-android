/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.nextcloud.client.account.User
import com.owncloud.android.MainApp
import com.owncloud.android.R
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.ExternalSiteWebView
import com.owncloud.android.utils.DisplayUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Shows a page of the account's own server inside the app, already signed in. The app password never enters the
 * web view: the app asks the server for a session itself and hands over only the session cookies (design 1.4).
 */
class WorkspaceWebActivity : ExternalSiteWebView() {
    private var base = ""
    private var currentTarget = ""
    private var sessionStartedAt = 0L

    override fun postOnCreate() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val web = getWebView()
                    if (web.canGoBack()) web.goBack() else finish()
                }
            }
        )
        super.postOnCreate()
    }

    override fun loadInitialUrl(url: String) {
        val current = getUser().orElse(null)
        val requestedAccount = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        if (current == null || current.accountName != requestedAccount) {
            finish()
            return
        }
        base = WorkspaceAccess.baseUrl(current)
        if (WorkspaceNavigationPolicy.decide(url, base) != WorkspaceDecision.LOAD) {
            finish()
            return
        }
        val settings = getWebView().settings
        settings.userAgentString = WebSettings.getDefaultUserAgent(this) + USER_AGENT_SUFFIX
        settings.allowContentAccess = false
        openSigned(url, current)
    }

    override fun handleUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        return when (WorkspaceNavigationPolicy.decide(url, base)) {
            WorkspaceDecision.LOAD -> {
                currentTarget = url
                false
            }
            WorkspaceDecision.RELOGIN -> {
                relogin()
                true
            }
            WorkspaceDecision.LOGOUT -> {
                finish()
                true
            }
            WorkspaceDecision.CUSTOM_TAB -> {
                WorkspaceLinks.openInCustomTab(this, request.url)
                true
            }
            WorkspaceDecision.SYSTEM -> {
                DisplayUtils.startLinkIntent(this, request.url)
                true
            }
            WorkspaceDecision.BLOCK -> true
        }
    }

    private fun relogin() {
        val current = getUser().orElse(null)
        val sinceSession = SystemClock.elapsedRealtime() - sessionStartedAt
        if (current == null || sinceSession < RELOGIN_MIN_INTERVAL_MILLIS) {
            fail("login-loop")
            return
        }
        openSigned(currentTarget, current)
    }

    private fun openSigned(url: String, user: User) {
        currentTarget = url
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { bootstrap(user) }
            when (result) {
                is WorkspaceSessionResult.Success ->
                    if (hand(result.cookies)) {
                        sessionStartedAt = SystemClock.elapsedRealtime()
                        getWebView().loadUrl(url)
                    } else {
                        fail("cookie-store")
                    }
                is WorkspaceSessionResult.Failure -> fail(result.reason)
            }
        }
    }

    private fun bootstrap(user: User): WorkspaceSessionResult = try {
        WorkspaceWebData.prepareFor(applicationContext, user.accountName)
        WorkspaceSessionBootstrap(WorkspaceAccess.client(applicationContext, user), MainApp.getUserAgent()).run()
    } catch (e: Exception) {
        Log_OC.w(TAG, "no client for the session: ${e.javaClass.simpleName}")
        WorkspaceSessionResult.Failure("client")
    }

    private suspend fun hand(cookies: List<WorkspaceCookie>): Boolean {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val webroot = WorkspaceOrigin.parse(base)?.path.orEmpty().trimEnd('/')
        val target = WorkspaceOrigin.origin(base) + webroot + "/"
        var stored = true
        for (cookie in cookies) {
            val ok = suspendCancellableCoroutine<Boolean> { continuation ->
                manager.setCookie(target, cookie.setCookie) { continuation.resume(it) }
            }
            stored = stored && ok
        }
        manager.flush()
        return stored
    }

    private fun fail(reason: String) {
        Log_OC.w(TAG, "workspace page not opened: $reason")
        DisplayUtils.showSnackMessage(this, R.string.common_error_unknown)
        finish()
    }

    companion object {
        private const val TAG = "WorkspaceWebActivity"
        private const val EXTRA_ACCOUNT_NAME = "ACCOUNT_NAME"
        private const val USER_AGENT_SUFFIX = " NoDrive"
        private const val RELOGIN_MIN_INTERVAL_MILLIS = 60_000L

        @JvmStatic
        fun intent(context: Context, user: User, url: String, title: String?): Intent =
            Intent(context, WorkspaceWebActivity::class.java)
                .putExtra(ExternalSiteWebView.EXTRA_URL, url)
                .putExtra(ExternalSiteWebView.EXTRA_TITLE, title.orEmpty())
                .putExtra(EXTRA_ACCOUNT_NAME, user.accountName)
    }
}
