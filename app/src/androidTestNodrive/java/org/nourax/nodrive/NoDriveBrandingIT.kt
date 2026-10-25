/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.webkit.URLUtil
import androidx.test.platform.app.InstrumentationRegistry
import com.owncloud.android.BuildConfig
import com.owncloud.android.MainApp
import com.owncloud.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Checks the NoDrive branding of the installed app at run time: name, ids that keep NoDrive apart
 * from the Nextcloud app, the fixed server, the switched-off Nextcloud services and the user agent.
 */
class NoDriveBrandingIT {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nameAndVersion() {
        assertEquals(PACKAGE, context.packageName)
        assertEquals(APP_NAME, context.getString(R.string.app_name))
        assertEquals(APP_NAME, context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        assertTrue(BuildConfig.VERSION_NAME, VERSION_RE.matches(BuildConfig.VERSION_NAME))
    }

    @Test
    fun accountTypeIsOurs() {
        assertEquals(PACKAGE, MainApp.getAccountType(context))
        val authenticator = AccountManager.get(context).authenticatorTypes.single { it.type == PACKAGE }
        assertEquals(PACKAGE, authenticator.packageName)
        val label = context.packageManager.getResourcesForApplication(PACKAGE).getString(authenticator.labelId)
        assertEquals(APP_NAME, label)
    }

    @Test
    fun providerAuthoritiesAreOurs() {
        assertEquals("$PACKAGE.provider", MainApp.getAuthority())
        val authorities = listOf(
            R.string.authority,
            R.string.document_provider_authority,
            R.string.file_provider_authority,
            R.string.image_cache_provider_authority,
            R.string.users_and_groups_search_authority
        ).map(context::getString)
        for (authority in authorities) {
            assertTrue(authority, authority.startsWith("$PACKAGE."))
            val provider = context.packageManager.resolveContentProvider(authority, 0)
            assertNotNull("no provider for $authority", provider)
            assertEquals(authority, PACKAGE, provider!!.packageName)
        }
    }

    @Test
    fun serverIsFixed() {
        assertEquals(LOGIN_URL, context.getString(R.string.webview_login_url))
        assertFalse(context.resources.getBoolean(R.bool.show_server_url_input))
        assertFalse(context.resources.getBoolean(R.bool.show_provider_or_own_installation))
        assertTrue(context.resources.getBoolean(R.bool.is_branded_client))
        assertEquals("nodrive", context.getString(R.string.login_data_own_scheme))
        assertEquals("", context.getString(R.string.provider_registration_server))
    }

    @Test
    fun linksOpenOnlyForDriveNouraxOrg() {
        assertTrue(opensInApp("https://$HOST/f/123"))
        assertTrue(opensInApp("https://$HOST/index.php/f/123"))
        assertFalse(opensInApp("https://cloud.example.org/f/123"))
        assertFalse(opensInApp("http://$HOST/f/123"))
        assertFalse(opensInApp("nc://login/server:https://cloud.example.org&user:a&password:b"))
        // DeepLinkLoginActivity, the only handler of login links, is removed: no link can add a server.
        assertFalse(opensInApp("nodrive://login/server:https://cloud.example.org&user:a&password:b"))
    }

    @Test
    fun nextcloudServicesAreOff() {
        assertEquals("", context.getString(R.string.push_server_url))
        assertEquals("", context.getString(R.string.report_issue_link))
        assertEquals("", context.getString(R.string.dev_latest))
        for (flag in listOf(
            R.bool.is_beta,
            R.bool.dev_version_direct_download_enabled,
            R.bool.show_whats_new,
            R.bool.recommend_enabled,
            R.bool.help_enabled,
            R.bool.participate_enabled,
            R.bool.sourcecode_enabled
        )) {
            assertFalse(context.resources.getResourceEntryName(flag), context.resources.getBoolean(flag))
        }
        // Settings > About hides the privacy row while NOURAX_PRIVACY_URL is empty.
        assertFalse(URLUtil.isValidUrl(context.getString(R.string.privacy_url)))
    }

    @Test
    fun userAgentMatchesServerRule() {
        val userAgent = MainApp.getUserAgent()
        val match = SERVER_UA_ANDROID.matchEntire(userAgent)
        assertNotNull(userAgent, match)
        assertEquals(userAgent, BuildConfig.VERSION_NAME, match!!.groupValues[1])
        assertTrue(userAgent, userAgent.endsWith(" $APP_NAME"))
    }

    @Test
    fun noStringNamesNextcloud() {
        val leftovers = mutableListOf<String>()
        for (tag in LANGUAGES) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val resources = context.createConfigurationContext(config).resources
            for (field in R.string::class.java.fields) {
                val name = field.name
                if (name in INTERNAL_STRINGS) continue
                val text = resources.getString(field.getInt(null))
                if (NAME_RE.containsMatchIn(text)) leftovers += "$tag $name: $text"
            }
        }
        assertTrue(leftovers.joinToString("\n"), leftovers.isEmpty())
    }

    private fun opensInApp(uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .any { it.activityInfo.packageName == PACKAGE }
    }

    companion object {
        private const val PACKAGE = "org.nourax.nodrive"
        private const val APP_NAME = "NoDrive"
        private const val HOST = "drive.nourax.org"
        private const val LOGIN_URL = "https://$HOST/index.php/login/v2"
        private val VERSION_RE = Regex("""\d+\.\d+\.\d+-nourax\.\d+""")

        // nextcloud/server v35.0.2, lib/public/IRequest.php, USER_AGENT_CLIENT_ANDROID.
        private val SERVER_UA_ANDROID = Regex("""^Mozilla/5\.0 \(Android\) (?:ownCloud|Nextcloud)-android/([^ ]*).*$""")

        // The name and its transliterations, as in tools/rebrand.py.
        private val NAME_RE = Regex(
            "next\\s*cloud|owncloud|نكست\\s*كلاود|نکست[\\u200c\\s]*کلود|некстклауд|nekstklaud|nexcloud|next\\s+bulut",
            RegexOption.IGNORE_CASE
        )

        // Internal values the server or the database depend on (see docs/branding-sweep.md).
        private val INTERNAL_STRINGS = setOf("db_file", "db_name", "nextcloud_user_agent", "office_user_agent")
        private val LANGUAGES = listOf("en", "de", "ar", "fa", "sr", "fr", "es", "ru", "zh-CN", "ja")
    }
}
