/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.net.HttpResult
import org.nourax.nodrive.agenda.net.getOcs
import org.nourax.nodrive.agenda.ocs.OcsJson
import org.nourax.nodrive.agenda.ocs.arr
import org.nourax.nodrive.agenda.ocs.bool
import org.nourax.nodrive.agenda.ocs.int
import org.nourax.nodrive.agenda.ocs.intOf
import org.nourax.nodrive.agenda.ocs.isString
import org.nourax.nodrive.agenda.ocs.obj
import org.nourax.nodrive.agenda.ocs.objects
import org.nourax.nodrive.agenda.ocs.str
import org.nourax.nodrive.agenda.registry.AgentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * NoBoard (Deck) card changes: due date, done and my agent label (design 9, plan 2.4).
 *
 * Deck's card update is a FULL replace: whatever the request leaves out is cleared (and `done` is cleared when it is not sent).
 * So the request is always built from a card read right before, plus the one change, and always carries `done`. `color` is never
 * sent (it is left as it is). Labels are separate calls.
 *
 * Deck's card update also sets the card's list from the stack in its address. So every write goes to the stack the FRESH card is in,
 * never to the one saved with the change: a card that someone moved to another list while the change waited stays where it was
 * moved. A card that is on another board now is not written at all.
 */
class DeckWriter(private val http: AgendaHttp, private val zone: () -> ZoneId, private val now: () -> Instant) {
    private val base = DeckClient.API_BASE

    private fun cardPath(boardId: Int, stackId: Int, cardId: Int) = "${base}boards/$boardId/stacks/$stackId/cards/$cardId"

    // The address as the change was made. Only used to READ the card: Deck finds a card by its id, whatever list the address names.
    private fun savedPath(command: EditCommand) = cardPath(command.locInt("boardId"), command.locInt("stackId"), command.locInt("cardId"))

    private fun versionOf(card: JsonObject): String = card.str("ETag").ifEmpty { card.int("lastModified").toString() }

    private sealed interface Read {
        /** [path] is the address of the card in the list it is in now: the only one a write may use. */
        class Ok(val card: JsonObject, val path: String) : Read

        class Stop(val result: SendResult) : Read
    }

    private fun readCard(command: EditCommand): Read {
        val reply = http.getOcs(savedPath(command))
        reply.failure(appPassword = true)?.let { return Read.Stop(it) }
        val card = OcsJson.parseElement(reply.body) as? JsonObject ?: return Read.Stop(SendResult.Conflict(ConflictReason.OTHER, "bad_card_answer"))
        val version = versionOf(card)
        if (!command.rebase && command.baseVersion.isNotEmpty() && version != command.baseVersion) {
            return Read.Stop(SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "lastModified", version))
        }
        val boardId = command.locInt("boardId")
        val stackId = card.int("stackId")
        if (stackId <= 0 || stackId == command.locInt("stackId")) return Read.Ok(card, savedPath(command))
        // Someone moved the card to another list. It is written there, not back where it was; on another board it is not written.
        val stacks = http.getOcs("${base}boards/$boardId/stacks")
        stacks.failure(appPassword = true)?.let { return Read.Stop(it) }
        val onBoard = (OcsJson.parseElement(stacks.body) as? JsonArray)?.objects().orEmpty().any { intOf(it.get("id")) == stackId }
        if (!onBoard) return Read.Stop(SendResult.Conflict(ConflictReason.REFUSED, CARD_MOVED_BOARD))
        return Read.Ok(card, cardPath(boardId, stackId, command.locInt("cardId")))
    }

    private fun ownerOf(card: JsonObject): String {
        val owner = card.get("owner")
        return when {
            owner == null || owner.isJsonNull -> ""
            owner.isJsonObject -> owner.asJsonObject.str("uid").ifEmpty { owner.asJsonObject.str("primaryKey") }
            owner.isString() -> owner.asString
            else -> ""
        }
    }

    private fun textOrNull(card: JsonObject, key: String): JsonElement {
        val v = card.get(key)
        return if (v != null && v.isString() && v.asString.isNotEmpty()) v else JsonNull.INSTANCE
    }

    /** The new due date in the person's zone, with the time of day the card had (or midnight). */
    private fun dueText(old: JsonElement, date: LocalDate): String {
        val z = zone()
        val oldInstant = if (old.isString()) runCatching { OffsetDateTime.parse(old.asString).toInstant() }.getOrNull() else null
        val time = oldInstant?.atZone(z)?.toLocalTime()
        val at = if (time != null) date.atTime(time) else date.atStartOfDay()
        return at.atZone(z).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /** The request of a full card update, from the fresh card plus the changes. */
    fun updateBody(card: JsonObject, command: EditCommand): JsonObject {
        val body = JsonObject()
        body.addProperty("title", card.str("title"))
        body.addProperty("type", card.str("type").ifEmpty { "plain" })
        body.addProperty("owner", ownerOf(card))
        body.addProperty("description", card.str("description"))
        body.addProperty("order", card.int("order"))
        var due: JsonElement = textOrNull(card, "duedate")
        when (val value = EditKeys.parse(command.change[EditKeys.DUE])) {
            is EditKeys.Value.Day -> due = JsonPrimitive(dueText(due, value.date))
            EditKeys.Value.None -> due = JsonNull.INSTANCE
            is EditKeys.Value.Moment, null -> Unit
        }
        body.add("duedate", due)
        body.add("startdate", textOrNull(card, "startdate"))
        body.addProperty("archived", card.bool("archived"))
        var done: JsonElement = textOrNull(card, "done")
        val wanted = command.change[EditKeys.DONE]
        if (wanted == "true" && done.isJsonNull) {
            done = JsonPrimitive(now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        } else if (wanted == "false") {
            done = JsonNull.INSTANCE
        } else if (card.get("done")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) {
            done = JsonPrimitive(now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        body.add("done", done)
        return body
    }

    fun update(command: EditCommand): SendResult {
        val read = when (val r = readCard(command)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r
        }
        val put = http.sendJson("PUT", read.path, updateBody(read.card, command))
        put.failure(appPassword = true)?.let { return it }
        return SendResult.Done(newVersion = currentVersion(read.path))
    }

    private fun currentVersion(path: String): String {
        val reply = http.getOcs(path)
        val card = if (reply.ok) OcsJson.parseElement(reply.body) as? JsonObject else null
        return card?.let { versionOf(it) }.orEmpty()
    }

    /** Puts the label of one of my agents on the card (or none, for Self) and takes my other agents' labels off. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun changeAgent(command: EditCommand, registry: AgentRegistry, me: String): SendResult {
        val read = when (val r = readCard(command)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r
        }
        val card = read.card
        val agentId = command.change[EditKeys.AGENT].orEmpty()
        val target = if (agentId == EditKeys.SELF || agentId.isEmpty()) null else registry.byId(agentId)
        if (agentId != EditKeys.SELF && agentId.isNotEmpty() && (target == null || !target.person.equals(me, ignoreCase = true))) {
            return SendResult.Conflict(ConflictReason.REFUSED, "not_my_agent")
        }
        val boardReply = http.getOcs("${base}boards/${command.locInt("boardId")}")
        boardReply.failure(appPassword = true)?.let { return it }
        val board = OcsJson.parseElement(boardReply.body) as? JsonObject ?: return SendResult.Conflict(ConflictReason.OTHER, "bad_board_answer")
        val mine = registry.ofPerson(me).map { AgentRegistry.normalize(it.boardLabel) }.filter { it.isNotEmpty() }.toSet()
        val labels = board.arr("labels").objects()
        val onCard = card.arr("labels").objects()

        var targetId = 0
        if (target != null) {
            val title = AgentRegistry.normalize(target.boardLabel)
            if (title.isEmpty()) return SendResult.Conflict(ConflictReason.OTHER, "no_label")
            targetId = labels.firstOrNull { AgentRegistry.normalize(it.str("title")) == title }?.let { intOf(it.get("id")) } ?: 0
            if (targetId == 0) {
                // A missing label is created, if the person may manage the board.
                if (!board.obj("permissions").bool("PERMISSION_MANAGE")) return SendResult.Conflict(ConflictReason.LABEL_NEEDS_MANAGE, target.boardLabel)
                val create = JsonObject()
                create.addProperty("title", target.boardLabel)
                create.addProperty("color", LABEL_COLOR)
                val made = http.sendJson("POST", "${base}boards/${command.locInt("boardId")}/labels", create)
                made.failure(appPassword = true)?.let { return it }
                targetId = (OcsJson.parseElement(made.body) as? JsonObject)?.let { intOf(it.get("id")) } ?: 0
                if (targetId == 0) return SendResult.Conflict(ConflictReason.OTHER, "label_not_made")
            }
        }

        // Take my other agents' labels off first, then put the wanted one on.
        val removed = ArrayList<Int>()
        for (label in onCard) {
            val id = intOf(label.get("id"))
            if (id == targetId || AgentRegistry.normalize(label.str("title")) !in mine) continue
            val reply = http.sendJson("PUT", "${read.path}/removeLabel", JsonObject().also { it.addProperty("labelId", id) })
            if (!reply.ok) return stoppedHalfway(read.path, removed, reply, "label_not_removed")
            removed += id
        }
        if (targetId != 0 && onCard.none { intOf(it.get("id")) == targetId }) {
            val reply = http.sendJson("PUT", "${read.path}/assignLabel", JsonObject().also { it.addProperty("labelId", targetId) })
            if (!reply.ok) return stoppedHalfway(read.path, removed, reply, "label_not_assigned")
        }
        return SendResult.Done(newVersion = currentVersion(read.path))
    }

    // The change stopped after some labels were already taken off: they are put on again, so a failed change leaves the card as it was.
    // The card is different now (this very change touched it), so a lost network is reported as "half done" with the card's new
    // version: the next try starts from it and does not call NoAgenda's own change "changed somewhere else".
    private fun stoppedHalfway(path: String, removed: List<Int>, reply: HttpResult, code: String): SendResult {
        val failure = reply.failure(appPassword = true) ?: SendResult.Conflict(ConflictReason.OTHER, code)
        if (removed.isEmpty()) return failure
        putBack(path, removed)
        return if (failure is SendResult.Offline || failure is SendResult.ServerError) SendResult.HalfDone(currentVersion(path)) else failure
    }

    private fun putBack(path: String, removed: List<Int>) {
        for (id in removed) http.sendJson("PUT", "$path/assignLabel", JsonObject().also { it.addProperty("labelId", id) })
    }

    companion object {
        /** The detail of the conflict for a card that is on another board now. */
        const val CARD_MOVED_BOARD = "card_moved_board"

        private const val LABEL_COLOR = "2563EB"
    }
}
