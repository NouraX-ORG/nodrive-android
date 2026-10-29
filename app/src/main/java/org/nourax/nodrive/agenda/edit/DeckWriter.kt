/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.nourax.nodrive.agenda.deck.DeckClient
import org.nourax.nodrive.agenda.net.AgendaHttp
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
import java.time.format.DateTimeFormatter

/**
 * NoBoard (Deck) card changes: due date, done and my agent label (design 9, plan 2.4).
 *
 * Deck's card update is a FULL replace: whatever the request leaves out is cleared (and `done` is cleared when it is not sent).
 * So the request is always built from a card read right before, plus the one change, and always carries `done`. `color` is never
 * sent (it is left as it is). Labels are separate calls.
 */
class DeckWriter(private val http: AgendaHttp, private val zone: () -> ZoneId, private val now: () -> Instant) {
    private val base = DeckClient.API_BASE

    private fun cardPath(command: EditCommand) = "${base}boards/${command.locInt("boardId")}/stacks/${command.locInt("stackId")}/cards/${command.locInt("cardId")}"

    private fun versionOf(card: JsonObject): String = card.str("ETag").ifEmpty { card.int("lastModified").toString() }

    private sealed interface Read {
        class Ok(val card: JsonObject) : Read

        class Stop(val result: SendResult) : Read
    }

    private fun readCard(command: EditCommand): Read {
        val reply = http.getOcs(cardPath(command))
        reply.failure()?.let { return Read.Stop(it) }
        val card = OcsJson.parseElement(reply.body) as? JsonObject ?: return Read.Stop(SendResult.Conflict(ConflictReason.OTHER, "bad_card_answer"))
        val version = versionOf(card)
        if (!command.rebase && command.baseVersion.isNotEmpty() && version != command.baseVersion) {
            return Read.Stop(SendResult.Conflict(ConflictReason.CHANGED_ELSEWHERE, "lastModified", version))
        }
        return Read.Ok(card)
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
            is EditKeys.Value.Day -> due = com.google.gson.JsonPrimitive(dueText(due, value.date))
            EditKeys.Value.None -> due = JsonNull.INSTANCE
            is EditKeys.Value.Moment, null -> Unit
        }
        body.add("duedate", due)
        body.add("startdate", textOrNull(card, "startdate"))
        body.addProperty("archived", card.bool("archived"))
        var done: JsonElement = textOrNull(card, "done")
        val wanted = command.change[EditKeys.DONE]
        if (wanted == "true" && done.isJsonNull) {
            done = com.google.gson.JsonPrimitive(now().atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        } else if (wanted == "false") {
            done = JsonNull.INSTANCE
        } else if (card.get("done")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) {
            done = com.google.gson.JsonPrimitive(now().atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        body.add("done", done)
        return body
    }

    fun update(command: EditCommand): SendResult {
        val card = when (val r = readCard(command)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r.card
        }
        val put = http.sendJson("PUT", cardPath(command), updateBody(card, command))
        put.failure()?.let { return it }
        return SendResult.Done(newVersion = currentVersion(command))
    }

    private fun currentVersion(command: EditCommand): String {
        val reply = http.getOcs(cardPath(command))
        val card = if (reply.ok) OcsJson.parseElement(reply.body) as? JsonObject else null
        return card?.let { versionOf(it) }.orEmpty()
    }

    /** Puts the label of one of my agents on the card (or none, for Self) and takes my other agents' labels off. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun changeAgent(command: EditCommand, registry: AgentRegistry, me: String): SendResult {
        val card = when (val r = readCard(command)) {
            is Read.Stop -> return r.result
            is Read.Ok -> r.card
        }
        val agentId = command.change[EditKeys.AGENT].orEmpty()
        val target = if (agentId == EditKeys.SELF || agentId.isEmpty()) null else registry.byId(agentId)
        if (agentId != EditKeys.SELF && agentId.isNotEmpty() && (target == null || !target.person.equals(me, ignoreCase = true))) {
            return SendResult.Conflict(ConflictReason.REFUSED, "not_my_agent")
        }
        val boardReply = http.getOcs("${base}boards/${command.locInt("boardId")}")
        boardReply.failure()?.let { return it }
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
                made.failure()?.let { return it }
                targetId = (OcsJson.parseElement(made.body) as? JsonObject)?.let { intOf(it.get("id")) } ?: 0
                if (targetId == 0) return SendResult.Conflict(ConflictReason.OTHER, "label_not_made")
            }
        }

        // Take my other agents' labels off first, then put the wanted one on.
        val removed = ArrayList<Int>()
        for (label in onCard) {
            val id = intOf(label.get("id"))
            if (id == targetId || AgentRegistry.normalize(label.str("title")) !in mine) continue
            val reply = http.sendJson("PUT", "${cardPath(command)}/removeLabel", JsonObject().also { it.addProperty("labelId", id) })
            if (!reply.ok) {
                putBack(command, removed)
                return reply.failure() ?: SendResult.Conflict(ConflictReason.OTHER, "label_not_removed")
            }
            removed += id
        }
        if (targetId != 0 && onCard.none { intOf(it.get("id")) == targetId }) {
            val reply = http.sendJson("PUT", "${cardPath(command)}/assignLabel", JsonObject().also { it.addProperty("labelId", targetId) })
            if (!reply.ok) {
                putBack(command, removed)
                return reply.failure() ?: SendResult.Conflict(ConflictReason.OTHER, "label_not_assigned")
            }
        }
        return SendResult.Done(newVersion = currentVersion(command))
    }

    // The labels that were taken off are put on again, so a failed change leaves the card as it was.
    private fun putBack(command: EditCommand, removed: List<Int>) {
        for (id in removed) http.sendJson("PUT", "${cardPath(command)}/assignLabel", JsonObject().also { it.addProperty("labelId", id) })
    }

    private companion object {
        const val LABEL_COLOR = "2563EB"
    }
}
