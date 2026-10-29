/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.deck

import androidx.annotation.Keep
import com.google.gson.JsonArray
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.logic.BoardRelation
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.getOcs
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.bool
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.objects

/** What is read from NoBoard (Deck), and its offline-cache form. Each board object carries its stacks as `_stacks`. */
@Keep
data class DeckSnapshot(
    val loaded: Boolean = false,
    /** The board app is not on the server. */
    val unavailable: Boolean = false,
    val version: String = "",
    val fetchedAtMs: Long = 0,
    val boards: JsonArray = JsonArray(),
    val failedBoards: Set<Int> = emptySet()
)

class DeckFetchOptions(
    val davUser: String,
    /** The installed Deck version from the server's capabilities; empty = not installed. */
    val version: String,
    val nowMs: Long,
    /** Which boards' cards are read (a Hidden board is not). */
    val wantBoard: (BoardRelation) -> Boolean = { true }
)

class DeckFetchResult(val ok: Boolean, val status: Int, val networkError: Boolean, val snapshot: DeckSnapshot)

/** The Deck REST calls of plan 1.5. Deck 1.19 is the tested version; another major.minor is read only. */
object DeckClient {
    private const val TAG = "AgendaDeck"
    const val API_BASE = "index.php/apps/deck/api/v1.0/"
    private const val TESTED_PREFIX = "1.19"

    fun versionIsTested(version: String): Boolean {
        val parts = version.split('.')
        return parts.size >= 2 && "${parts[0]}.${parts[1]}" == TESTED_PREFIX
    }

    fun fetch(http: AgendaHttp, options: DeckFetchOptions, previous: DeckSnapshot): DeckFetchResult {
        if (options.version.isEmpty()) {
            // No "deck" capability: the app is not on the server.
            return DeckFetchResult(true, 404, false, DeckSnapshot(loaded = true, unavailable = true, fetchedAtMs = options.nowMs))
        }
        // The OCS header keeps the server's CORS check on the path that does not log the session out.
        val boardsReply = http.getOcs("${API_BASE}boards?details=true")
        if (boardsReply.status == 404) {
            return DeckFetchResult(true, 404, false, DeckSnapshot(loaded = true, unavailable = true, fetchedAtMs = options.nowMs))
        }
        val list = OcsJson.parseElement(boardsReply.body) as? JsonArray
        if (!boardsReply.ok || list == null) {
            return DeckFetchResult(false, boardsReply.status, boardsReply.networkError, previous)
        }
        val result = JsonArray()
        val failed = HashSet<Int>()
        for (board in list.objects()) {
            if (board.bool("archived") || board.int("deletedAt") > 0) continue
            val id = intOf(board.get("id"))
            val relation = BoardRelation.of(board, options.davUser)
            if (!options.wantBoard(relation)) {
                // Not wanted (Hidden): its cards are not read, but the board stays, so the Sources screen lists it.
                result.add(board.deepCopy().also { it.remove("_stacks") })
                continue
            }
            val reply = http.getOcs("${API_BASE}boards/$id/stacks")
            val stacks = OcsJson.parseElement(reply.body) as? JsonArray
            if (reply.ok && stacks != null) {
                val copy = board.deepCopy()
                copy.add("_stacks", stacks)
                result.add(copy)
            } else {
                AgendaLog.i(TAG, "stacks answered ${reply.status}")
                failed += id
                // Keep the old copy of this board.
                val old = previous.boards.objects().firstOrNull { intOf(it.get("id")) == id }
                result.add(old ?: board)
            }
        }
        val snapshot = DeckSnapshot(
            loaded = true,
            unavailable = false,
            version = options.version,
            fetchedAtMs = options.nowMs,
            boards = result,
            failedBoards = failed
        )
        return DeckFetchResult(true, 200, false, snapshot)
    }
}
