/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.nourax.nodrive.agenda.dav.CalendarReadOptions
import org.nourax.nodrive.agenda.dav.CalendarReader
import org.nourax.nodrive.agenda.dav.CalendarSnapshot
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.deck.DeckFetchOptions
import org.nourax.nodrive.agenda.deck.DeckSnapshot
import org.nourax.nodrive.agenda.gantt.GanttClient
import org.nourax.nodrive.agenda.gantt.GanttFetchOptions
import org.nourax.nodrive.agenda.gantt.GanttMapper
import org.nourax.nodrive.agenda.gantt.GanttSnapshot
import org.nourax.nodrive.agenda.logic.AgendaBuilder
import org.nourax.nodrive.agenda.logic.BuildInput
import org.nourax.nodrive.agenda.logic.BuildOutput
import org.nourax.nodrive.agenda.logic.SourceSettings
import org.nourax.nodrive.agenda.logic.WorkMode
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.SourceState
import org.nourax.nodrive.agenda.model.SourceStatus
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.ocs.ServerInfo
import org.nourax.nodrive.agenda.ocs.ServerInfoClient
import org.nourax.nodrive.agenda.registry.AgentRegistry
import org.nourax.nodrive.agenda.registry.RegistrySource
import org.nourax.nodrive.agenda.registry.RegistrySources
import org.nourax.nodrive.agenda.registry.RegistryStatus
import org.nourax.nodrive.agenda.store.AgendaStore
import org.nourax.nodrive.agenda.store.Sources
import org.nourax.nodrive.agenda.store.StoredSource
import java.time.LocalDate
import java.time.ZoneId

/** The keys of [AgendaSnapshot.statuses]. */
object StatusKeys {
    const val USER = "user"
    const val REGISTRY = "registry"
    const val CALENDAR = "calendar"
    const val GANTT = "gantt"
    const val BOARD = "board"
}

/** Everything the screen needs about one account: the rows, the Sources screen and how each source stands. */
class AgendaSnapshot(
    val build: BuildOutput,
    val statuses: Map<String, SourceStatus>,
    val registryStatus: RegistryStatus,
    val registrySkipped: Int,
    val settings: SourceSettings,
    val today: LocalDate,
    val zone: ZoneId,
    /** When the newest shown data was read (epoch milliseconds); 0 = nothing yet. */
    val savedAtMs: Long
) {
    fun status(key: String): SourceStatus = statuses[key] ?: SourceStatus(key)
}

/** What the repository needs from the data layer (a test replaces it with canned data). */
interface AgendaEngine {
    fun snapshot(): AgendaSnapshot

    fun refresh(manual: Boolean): AgendaSnapshot

    fun updateSettings(change: (SourceSettings) -> SourceSettings): AgendaSnapshot
}

/**
 * One account's readers, merge and per-source status (plan 1.2). Blocking: call it from a background thread, one call at
 * a time (the repository does that). Sources are independent: one failing keeps its saved copy and does not stop the others.
 */
class AccountAgenda(
    private val account: String,
    private val http: AgendaHttp,
    private val store: AgendaStore,
    private val clock: AgendaClock,
    private val accountEmail: String = "",
    private val registryFactory: (AgendaHttp, String) -> RegistrySource = RegistrySources::create
) : AgendaEngine {
    private val gson = Gson()

    var settings: SourceSettings = SourceSettings()
        private set
    private var server = ServerInfo()
    private var calendar = CalendarSnapshot()
    private var gantt = GanttSnapshot()
    private var deck = DeckSnapshot()
    private var registry = AgentRegistry.MISSING
    private var registryEtag = ""
    private var registryRead = false
    private val statuses = LinkedHashMap<String, SourceStatus>()
    private val failures = HashMap<String, Int>()
    private val nextTryMs = HashMap<String, Long>()

    init {
        loadFromStore()
    }

    // ---- the saved copy ---------------------------------------------------------------------------------------------

    private fun <T> readStored(source: String, type: Class<T>): Pair<T, StoredSource>? {
        val stored = store.read(account, source) ?: return null
        return try {
            gson.fromJson(stored.json, type)?.let { it to stored }
        } catch (_: Exception) {
            null // a row that does not parse is ignored; a fresh read follows
        }
    }

    private fun loadFromStore() {
        store.loadSettings(account)?.let { json ->
            settings = try {
                SourceSettings.fromJson(JsonParser.parseString(json).asJsonObject)
            } catch (_: Exception) {
                SourceSettings()
            }
        }
        readStored(Sources.USER, ServerInfo::class.java)?.let { (value, stored) ->
            server = value
            statuses[StatusKeys.USER] = saved(StatusKeys.USER, stored)
        }
        readStored(Sources.CALENDAR, CalendarSnapshot::class.java)?.let { (value, stored) ->
            calendar = value
            statuses[StatusKeys.CALENDAR] = saved(StatusKeys.CALENDAR, stored)
        }
        readStored(Sources.GANTT, GanttSnapshot::class.java)?.let { (value, stored) ->
            gantt = value
            statuses[StatusKeys.GANTT] = savedGantt(stored)
        }
        readStored(Sources.DECK, DeckSnapshot::class.java)?.let { (value, stored) ->
            deck = value
            statuses[StatusKeys.BOARD] = savedDeck(stored)
        }
        store.read(account, Sources.REGISTRY)?.let { stored ->
            registryRead = true
            registryEtag = stored.etag
            registry = if (stored.status == REGISTRY_MISSING) AgentRegistry.MISSING else AgentRegistry.parse(stored.json.toByteArray(Charsets.UTF_8))
            statuses[StatusKeys.REGISTRY] = saved(StatusKeys.REGISTRY, stored)
        }
    }

    private fun saved(key: String, stored: StoredSource) = SourceStatus(key, SourceState.SAVED, stored.fetchedMs)

    private fun savedGantt(stored: StoredSource): SourceStatus =
        gantt.statusAfterRead(SourceState.SAVED, stored.fetchedMs)

    private fun savedDeck(stored: StoredSource): SourceStatus =
        deck.statusAfterRead(SourceState.SAVED, stored.fetchedMs)

    private fun GanttSnapshot.statusAfterRead(base: SourceState, atMs: Long): SourceStatus = when {
        unavailable -> SourceStatus(StatusKeys.GANTT, SourceState.UNAVAILABLE, atMs)
        !hasLinkedResource -> SourceStatus(StatusKeys.GANTT, SourceState.NOT_LINKED, atMs)
        !contractOk -> SourceStatus(StatusKeys.GANTT, SourceState.READ_ONLY, atMs, readOnlyByGuard = true)
        else -> SourceStatus(StatusKeys.GANTT, base, atMs)
    }

    private fun DeckSnapshot.statusAfterRead(base: SourceState, atMs: Long): SourceStatus = when {
        unavailable -> SourceStatus(StatusKeys.BOARD, SourceState.UNAVAILABLE, atMs)
        !DeckClient.versionIsTested(version) -> SourceStatus(StatusKeys.BOARD, SourceState.READ_ONLY, atMs, readOnlyByGuard = true)
        else -> SourceStatus(StatusKeys.BOARD, base, atMs)
    }

    // ---- the settings ------------------------------------------------------------------------------------------------

    /** Changes a setting, saves it and gives the rows again from what is already read (no request). */
    override fun updateSettings(change: (SourceSettings) -> SourceSettings): AgendaSnapshot {
        settings = change(settings)
        store.saveSettings(account, settings.toJson().toString())
        return snapshot()
    }

    // ---- the rows -----------------------------------------------------------------------------------------------------

    override fun snapshot(): AgendaSnapshot {
        val clockNow = clock.now()
        val zone = clock.zone()
        val today = clock.today()
        val davUser = server.uid.ifEmpty { account.substringBefore('@') }
        val build = AgendaBuilder.build(
            BuildInput(
                davUser = davUser,
                server = server,
                calendar = calendar,
                gantt = gantt,
                deck = deck,
                registry = registry,
                settings = settings,
                today = today,
                zone = zone,
                now = clockNow,
                accountEmail = accountEmail
            )
        )
        val savedAt = listOf(calendar.fetchedAtMs, gantt.fetchedAtMs, deck.fetchedAtMs).maxOrNull() ?: 0L
        return AgendaSnapshot(build, LinkedHashMap(statuses), registry.status, registry.skipped, settings, today, zone, savedAt)
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    private fun allowed(key: String, manual: Boolean): Boolean = manual || clock.now().toEpochMilli() >= (nextTryMs[key] ?: 0L)

    /** After server errors (5xx, no answer) a source backs off 5, 10, 20, then 30 minutes. A manual refresh ignores it. */
    private fun noteResult(key: String, ok: Boolean, status: Int, networkError: Boolean) {
        if (ok) {
            failures.remove(key)
            nextTryMs.remove(key)
            return
        }
        if (networkError || status >= 500) {
            val count = (failures[key] ?: 0) + 1
            failures[key] = count
            val minutes = BACKOFF_MINUTES[minOf(count, BACKOFF_MINUTES.size) - 1]
            nextTryMs[key] = clock.now().toEpochMilli() + minutes * 60_000L
        }
    }

    private fun failedState(hadData: Boolean, status: Int, authKey: Boolean): SourceState = when {
        status == 401 && !authKey -> SourceState.NEEDS_LOGIN
        authKey && (status == 401 || status == 403) -> SourceState.REFUSED
        hadData -> SourceState.CACHED
        else -> SourceState.FAILED
    }

    private fun failedStatus(key: String, hadData: Boolean, atMs: Long, status: Int, authKey: Boolean): SourceStatus {
        val state = failedState(hadData, status, authKey)
        // A refused answer or a sign-in problem still shows what was saved.
        return SourceStatus(key, state, if (hadData) atMs else 0L, httpStatus = status)
    }

    /** Reads every source once. [manual] (pull to refresh, the menu) ignores the back-off. */
    override fun refresh(manual: Boolean): AgendaSnapshot {
        val nowMs = clock.now().toEpochMilli()
        val today = clock.today()

        // The account itself: id, name, groups, Deck version. Once an hour, or when it was never read.
        val infoStale = !server.loaded || nowMs - server.fetchedAtMs > HOUR_MS
        if ((infoStale || manual) && allowed(StatusKeys.USER, manual)) {
            val result = ServerInfoClient.fetch(http, server, nowMs)
            noteResult(StatusKeys.USER, result.ok, result.status, result.networkError)
            if (result.ok) {
                server = result.info
                store.write(account, Sources.USER, StoredSource(gson.toJson(server), "", nowMs, "ok"))
                statuses[StatusKeys.USER] = SourceStatus(StatusKeys.USER, SourceState.FRESH, nowMs)
            } else {
                statuses[StatusKeys.USER] = failedStatus(StatusKeys.USER, server.loaded, server.fetchedAtMs, result.status, false)
            }
        }
        if (server.uid.isEmpty()) {
            // Nothing can be read without knowing who the account is.
            val user = statuses[StatusKeys.USER]
            val state = user?.state ?: SourceState.FAILED
            for (key in listOf(StatusKeys.CALENDAR, StatusKeys.GANTT, StatusKeys.BOARD, StatusKeys.REGISTRY)) {
                if (statuses[key] == null || statuses[key]?.savedAtMs == 0L) statuses[key] = SourceStatus(key, state, 0L, httpStatus = user?.httpStatus ?: 0)
            }
            return snapshot()
        }

        readRegistry(manual)
        readGantt(manual, nowMs)
        readCalendar(manual, nowMs, today)
        readDeck(manual, nowMs)
        return snapshot()
    }

    private fun readRegistry(manual: Boolean) {
        if (!allowed(StatusKeys.REGISTRY, manual)) return
        val nowMs = clock.now().toEpochMilli()
        val fetch = registryFactory(http, server.uid).fetch(registryEtag.ifEmpty { null })
        noteResult(StatusKeys.REGISTRY, fetch.status in 200..499, fetch.status, fetch.networkError)
        when {
            fetch.notModified -> {
                statuses[StatusKeys.REGISTRY] = SourceStatus(StatusKeys.REGISTRY, SourceState.FRESH, nowMs)
            }
            fetch.status == 200 && fetch.body != null -> {
                registry = AgentRegistry.parse(fetch.body)
                registryEtag = fetch.etag.orEmpty()
                registryRead = true
                // The text is kept as it came, so the next start parses it again with the same rules.
                store.write(account, Sources.REGISTRY, StoredSource(String(fetch.body, Charsets.UTF_8), registryEtag, nowMs, "ok"))
                statuses[StatusKeys.REGISTRY] = SourceStatus(StatusKeys.REGISTRY, SourceState.FRESH, nowMs)
            }
            fetch.status == 404 -> {
                registry = AgentRegistry.MISSING
                registryEtag = ""
                registryRead = true
                store.write(account, Sources.REGISTRY, StoredSource("", "", nowMs, REGISTRY_MISSING))
                statuses[StatusKeys.REGISTRY] = SourceStatus(StatusKeys.REGISTRY, SourceState.FRESH, nowMs)
            }
            else -> {
                val at = statuses[StatusKeys.REGISTRY]?.savedAtMs ?: 0L
                statuses[StatusKeys.REGISTRY] = failedStatus(StatusKeys.REGISTRY, registryRead, at, fetch.status, false)
            }
        }
    }

    private fun readGantt(manual: Boolean, nowMs: Long) {
        if (!allowed(StatusKeys.GANTT, manual)) return
        val options = GanttFetchOptions(
            davUser = server.uid,
            serverAdmin = server.isServerAdmin,
            groups = server.groups,
            nowMs = nowMs,
            forceSlow = manual && !gantt.loaded,
            wantProject = { settings.projectMode(it) != WorkMode.HIDDEN }
        )
        val result = GanttClient.fetch(http, options, gantt)
        noteResult(StatusKeys.GANTT, result.ok, result.status, result.networkError)
        if (result.ok) {
            gantt = result.snapshot
            store.write(account, Sources.GANTT, StoredSource(gson.toJson(gantt), "", nowMs, "ok"))
            statuses[StatusKeys.GANTT] = gantt.statusAfterRead(SourceState.FRESH, nowMs)
        } else {
            statuses[StatusKeys.GANTT] = failedStatus(StatusKeys.GANTT, gantt.loaded, gantt.fetchedAtMs, result.status, true)
        }
    }

    private fun readCalendar(manual: Boolean, nowMs: Long, today: LocalDate) {
        if (!allowed(StatusKeys.CALENDAR, manual)) return
        val (from, to) = CalendarReader.syncWindow(today)
        val copyUri = GanttMapper.taskCopyCalendarUri(gantt)
        val options = CalendarReadOptions(
            davUser = server.uid,
            from = from,
            to = to,
            zone = clock.zone(),
            now = clock.now(),
            forceFull = manual && !calendar.loaded,
            wanted = { settings.calendarMode(it, copyUri) != org.nourax.nodrive.agenda.logic.CalendarMode.HIDDEN }
        )
        val result = CalendarReader.read(http, options, calendar)
        noteResult(StatusKeys.CALENDAR, result.ok, result.status, result.networkError)
        if (result.ok) {
            calendar = result.snapshot
            store.write(account, Sources.CALENDAR, StoredSource(gson.toJson(calendar), "", nowMs, "ok"))
            statuses[StatusKeys.CALENDAR] = SourceStatus(StatusKeys.CALENDAR, SourceState.FRESH, nowMs)
        } else {
            statuses[StatusKeys.CALENDAR] = failedStatus(StatusKeys.CALENDAR, calendar.loaded, calendar.fetchedAtMs, result.status, false)
        }
    }

    private fun readDeck(manual: Boolean, nowMs: Long) {
        if (!allowed(StatusKeys.BOARD, manual)) return
        val options = DeckFetchOptions(
            davUser = server.uid,
            version = server.deckVersion,
            nowMs = nowMs,
            wantBoard = { settings.boardMode(it) != WorkMode.HIDDEN }
        )
        val result = DeckClient.fetch(http, options, deck)
        noteResult(StatusKeys.BOARD, result.ok, result.status, result.networkError)
        if (result.ok) {
            deck = result.snapshot
            store.write(account, Sources.DECK, StoredSource(gson.toJson(deck), "", nowMs, "ok"))
            statuses[StatusKeys.BOARD] = deck.statusAfterRead(SourceState.FRESH, nowMs)
        } else {
            statuses[StatusKeys.BOARD] = failedStatus(StatusKeys.BOARD, deck.loaded, deck.fetchedAtMs, result.status, true)
        }
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val REGISTRY_MISSING = "missing"
        val BACKOFF_MINUTES = listOf(5, 10, 20, 30)
    }
}
