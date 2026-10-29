/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.store

/** One saved home snapshot: the JSON, when it was read and how the last read ended. No password or token, ever. */
class StoredSource(val json: String, val etag: String, val fetchedMs: Long, val status: String)

object Sources {
    const val REGISTRY = "registry"
    const val USER = "user"
    const val CALENDAR = "calendar"
    const val GANTT = "gantt"
    const val DECK = "deck"
}

/**
 * The offline cache (design 9, plan 1.3). The repository writes through this seam; plain JVM tests use [InMemoryAgendaStore].
 * A source is replaced as a whole, and only after it was read completely.
 */
interface AgendaStore {
    fun loadSettings(account: String): String?

    fun saveSettings(account: String, json: String)

    fun read(account: String, source: String): StoredSource?

    fun write(account: String, source: String, stored: StoredSource)

    /** Deletes the rows of this account (NoAgenda's own copies of server data only, never a user file). */
    fun forget(account: String)
}

class InMemoryAgendaStore : AgendaStore {
    private val settings = HashMap<String, String>()
    private val sources = HashMap<Pair<String, String>, StoredSource>()

    @Synchronized
    override fun loadSettings(account: String): String? = settings[account]

    @Synchronized
    override fun saveSettings(account: String, json: String) {
        settings[account] = json
    }

    @Synchronized
    override fun read(account: String, source: String): StoredSource? = sources[account to source]

    @Synchronized
    override fun write(account: String, source: String, stored: StoredSource) {
        sources[account to source] = stored
    }

    @Synchronized
    override fun forget(account: String) {
        settings.remove(account)
        sources.keys.removeAll { it.first == account }
    }

    /** Everything stored, as one text (tests look for secrets in it). */
    @Synchronized
    fun dump(): String = (settings.values + sources.values.map { it.json }).joinToString("\n")
}
