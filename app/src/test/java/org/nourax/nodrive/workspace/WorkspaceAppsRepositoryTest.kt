/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceAppsRepositoryTest {
    private val base = "https://drive.nourax.org"
    private val json = """{"ocs":{"data":[{"id":"mail","type":"link","name":"Mail","href":"/apps/mail/"}]}}"""
    private val now = 1_000_000L

    private class MemoryStore(var cache: WorkspaceAppsCache? = null) : WorkspaceAppsStore {
        override fun load() = cache

        override fun save(cache: WorkspaceAppsCache) {
            this.cache = cache
        }

        override fun clear() {
            cache = null
        }
    }

    private class FakeTransport(val answer: () -> WorkspaceAppsResponse) : WorkspaceAppsTransport {
        val sentEtags = mutableListOf<String?>()

        override fun get(etag: String?): WorkspaceAppsResponse {
            sentEtags += etag
            return answer()
        }
    }

    private fun repo(store: MemoryStore, transport: FakeTransport) =
        WorkspaceAppsRepository(base, store, transport) { now }

    private fun answer(code: Int, body: String = "", etag: String? = null) =
        FakeTransport { WorkspaceAppsResponse(code, body, etag) }

    @Test
    fun firstFetchStoresJsonAndEtag() {
        val store = MemoryStore()
        val repo = repo(store, answer(200, json, "\"e1\""))
        assertEquals(WorkspaceRefresh.UPDATED, repo.refresh())
        assertEquals("\"e1\"", store.cache?.etag)
        assertEquals(listOf("mail"), repo.cached().map { it.id })
    }

    @Test
    fun notModifiedKeepsTheCacheAndSendsTheEtag() {
        val old = now - WorkspaceAppsRepository.MIN_INTERVAL_MILLIS
        val store = MemoryStore(WorkspaceAppsCache(json, "\"e1\"", old))
        val transport = answer(304)
        assertEquals(WorkspaceRefresh.UNCHANGED, repo(store, transport).refresh())
        assertEquals(listOf<String?>("\"e1\""), transport.sentEtags)
        assertEquals(json, store.cache?.json)
        assertEquals(now, store.cache?.fetchedAtMillis)
    }

    @Test
    fun refreshIsThrottledToTenMinutesUnlessForced() {
        val store = MemoryStore(WorkspaceAppsCache(json, null, now - 1000))
        val transport = answer(200, json)
        assertEquals(WorkspaceRefresh.THROTTLED, repo(store, transport).refresh())
        assertTrue(transport.sentEtags.isEmpty())
        assertEquals(WorkspaceRefresh.UPDATED, repo(store, transport).refresh(force = true))
    }

    @Test
    fun notFoundClearsTheCache() {
        val store = MemoryStore(WorkspaceAppsCache(json, null, 0))
        assertEquals(WorkspaceRefresh.CLEARED, repo(store, answer(404)).refresh())
        assertNull(store.cache)
    }

    @Test
    fun malformedAnswerKeepsTheCache() {
        val store = MemoryStore(WorkspaceAppsCache(json, "\"e1\"", 0))
        assertEquals(WorkspaceRefresh.KEPT, repo(store, answer(200, "not json")).refresh())
        assertEquals(json, store.cache?.json)
    }

    @Test
    fun networkAndServerErrorsKeepTheCache() {
        val store = MemoryStore(WorkspaceAppsCache(json, null, 0))
        assertEquals(WorkspaceRefresh.KEPT, repo(store, FakeTransport { throw IOException("offline") }).refresh())
        assertEquals(WorkspaceRefresh.KEPT, repo(store, answer(401)).refresh())
        assertEquals(WorkspaceRefresh.KEPT, repo(store, answer(500)).refresh())
        assertEquals(json, store.cache?.json)
    }

    @Test
    fun brokenCacheShowsNothing() {
        val store = MemoryStore(WorkspaceAppsCache("broken", null, 0))
        assertTrue(repo(store, answer(500)).cached().isEmpty())
    }
}
