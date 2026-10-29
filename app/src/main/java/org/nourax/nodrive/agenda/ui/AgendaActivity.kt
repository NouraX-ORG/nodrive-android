/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import com.nextcloud.client.account.User
import com.owncloud.android.R
import com.owncloud.android.databinding.NodriveAgendaActivityBinding
import com.owncloud.android.ui.activity.DrawerActivity
import org.nourax.nodrive.agenda.AgendaAccess
import org.nourax.nodrive.agenda.android.AgendaDrawer
import org.nourax.nodrive.agenda.android.AgendaRefreshWorker
import org.nourax.nodrive.agenda.ui.compose.AgendaScreen
import org.nourax.nodrive.agenda.ui.compose.OpenUrl
import org.nourax.nodrive.workspace.WorkspaceAccess
import org.nourax.nodrive.workspace.WorkspaceWebActivity

/**
 * NoAgenda (design 8): Day, Week and Month lists of everything the person is responsible for. Compose screens inside a
 * DrawerActivity, so the side menu works as everywhere else. In this step nothing can be changed here.
 */
class AgendaActivity : DrawerActivity() {
    private lateinit var binding: NodriveAgendaActivityBinding
    private var accountName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = NodriveAgendaActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val account: User? = this.user.orElse(null)
        if (account == null || account.isAnonymous) {
            finish()
            return
        }
        val user: User = account
        accountName = user.accountName

        setupDrawer(menuItemId)
        setupToolbarShowOnlyMenuButtonAndTitle(getString(R.string.nodrive_agenda_name)) { openDrawer() }

        val app = applicationContext
        val repository = AgendaAccess.repository(app, user)
        val model = ViewModelProvider(
            this,
            AgendaViewModel.Factory(repository, AgendaAccess.clock()) {
                // Once NoAgenda was opened for this account, the background refresh keeps its copy fresh (every 15 minutes).
                AgendaRefreshWorker.schedule(app, user.accountName)
            }
            // Keyed by the account: a ViewModel survives recreate(), and a new account must not get the old account's data.
        )[user.accountName, AgendaViewModel::class.java]
        val base = WorkspaceAccess.baseUrl(user)

        binding.nodriveAgendaCompose.setContent {
            MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(this)) {
                AgendaScreen(model, base, OpenUrl { url, title -> startActivity(WorkspaceWebActivity.intent(this, user, url, title)) })
            }
        }
    }

    override fun getMenuItemId(): Int = AgendaDrawer.ITEM_ID

    override fun onResume() {
        super.onResume()
        val current = user.orElse(null)
        if (current != null && accountName.isNotEmpty() && current.accountName != accountName) {
            // The person switched account in the side menu: show that account's agenda.
            recreate()
            return
        }
        highlightNavigationViewItem(menuItemId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            toggleDrawer()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        @JvmStatic
        fun intent(context: Context, user: User): Intent = Intent(context, AgendaActivity::class.java).putExtra(EXTRA_ACCOUNT_NAME, user.accountName)

        private const val EXTRA_ACCOUNT_NAME = "ACCOUNT_NAME"
    }
}
