/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.owncloud.android.MainApp
import org.junit.Assert.assertFalse
import org.nourax.nodrive.agenda.android.AgendaRefreshWorker
import org.nourax.nodrive.agenda.edit.EditDatabase
import org.nourax.nodrive.agenda.model.FixedAgendaClock
import org.nourax.nodrive.agenda.store.AgendaDatabase
import org.nourax.nodrive.backup.BackupTestSupport
import org.nourax.nodrive.workspace.WorkspaceTestSupport
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Accounts, the clock, screenshots and the texts on screen for the NoAgenda device tests. */
class AgendaTestSupport {
    val backup = BackupTestSupport()
    val workspace = WorkspaceTestSupport()
    val context: Context get() = backup.context
    private val arguments = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    val look: String get() = backup.look
    val serverUrl: String? get() = arguments.getString(ARG_URL)?.takeIf { it.isNotEmpty() }
    fun token(user: String): String? = arguments.getString("NODRIVE_AG_${user.uppercase()}_TOKEN")?.takeIf { it.isNotEmpty() }
    val hasServer: Boolean get() = serverUrl != null && token("ali") != null

    fun addLocalAccount(): Account = backup.addLocalAccount()

    fun addServerAccount(user: String): Account = backup.addAccount(user, checkNotNull(serverUrl), checkNotNull(token(user)))

    /** The screens run on a fixed clock in UTC, like the seeded data of the throwaway server. */
    fun useClock(instant: Instant) {
        AgendaAccess.clockOverride = FixedAgendaClock(instant)
    }

    /** Starts clean: no canned data, no repository, no saved copy, no background refresh. */
    fun reset(keepSavedCopy: Boolean = false) {
        AgendaAccess.factory = null
        AgendaAccess.clockOverride = null
        AgendaAccess.resetForTests()
        runCatching {
            AccountManager.get(context).getAccountsByType(MainApp.getAccountType(context)).forEach { AgendaRefreshWorker.cancel(context, it.name) }
        }
        if (!keepSavedCopy) {
            AgendaDatabase.get(context).clearAllTables()
            // the changes that wait to be sent (the offline queue) are cleared with the rest
            EditDatabase.get(context).clearAllTables()
        }
        backup.wipeAllAccountsAndRowsCiEmulatorOnly()
    }

    fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = checkNotNull(context.getExternalFilesDir(null))
        FileOutputStream(File(folder, "agenda-$name-$look.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    /** Every text and description that the Compose tree shows, in all windows. */
    fun allTexts(compose: SemanticsNodeInteractionsProvider): List<String> {
        val out = ArrayList<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    /** No visible text, in any language, names the server software (the no-Nextcloud rule). */
    fun assertNoServerSoftwareName(compose: ComposeTestRule) {
        for (text in allTexts(compose)) {
            assertFalse("\"$text\" names the server software", text.contains("nextcloud", ignoreCase = true))
            assertFalse("\"$text\" names the server software", text.contains("owncloud", ignoreCase = true))
        }
    }

    companion object {
        const val ARG_URL = "NODRIVE_AG_URL"
        private const val PNG_QUALITY = 100
    }
}
