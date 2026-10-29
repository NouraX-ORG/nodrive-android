/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.owncloud.android.R
import com.nextcloud.client.account.User
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.SystemAgendaClock
import org.nourax.nodrive.agenda.net.AccountAgendaHttp
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.store.AgendaDatabase
import org.nourax.nodrive.agenda.store.AgendaStore
import org.nourax.nodrive.agenda.store.RoomAgendaStore
import org.nourax.nodrive.workspace.WorkspaceAccess
import java.util.concurrent.ConcurrentHashMap

/** Wires the plain parts (readers, store, repository) to the account of the running app, like WorkspaceAccess. */
object AgendaAccess {
    private const val TAG = "AgendaAccess"
    private val repositories = ConcurrentHashMap<String, AgendaRepository>()

    /** A test replaces how a repository is made (a canned snapshot, a fixed clock). Null in the app. */
    @VisibleForTesting
    @Volatile
    var factory: ((Context, User) -> AgendaRepository)? = null

    fun enabled(context: Context): Boolean = context.resources.getBoolean(R.bool.nodrive_agenda)

    /** The account's own client, asked for at every request, so a changed credential is used at once. */
    private class LiveHttp(private val context: Context, private val user: User) : AgendaHttp {
        override fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, contentType: String?): HttpResult {
            return try {
                AccountAgendaHttp.of(WorkspaceAccess.client(context, user)).send(method, path, headers, body, contentType)
            } catch (e: Exception) {
                AgendaLog.w(TAG, "no client: ${e.javaClass.simpleName}")
                HttpResult(0, networkError = true)
            }
        }
    }

    fun store(context: Context): AgendaStore = RoomAgendaStore(AgendaDatabase.get(context))

    fun repository(context: Context, user: User, clock: AgendaClock = SystemAgendaClock): AgendaRepository {
        factory?.let { return it(context, user) }
        val app = context.applicationContext
        return repositories.getOrPut(user.accountName) {
            AgendaRepository(AccountAgenda(user.accountName, LiveHttp(app, user), store(app), clock))
        }
    }

    /** Drops the in-memory repository of an account (account removed). */
    fun forgetRepository(accountName: String) {
        repositories.remove(accountName)
    }
}
