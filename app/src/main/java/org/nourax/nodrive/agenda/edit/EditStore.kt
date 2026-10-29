/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

/**
 * The offline queue (design 9, plan 2.2): the changes that wait to be sent, and the ones that were not sent because the item
 * changed somewhere else. It survives a restart. Nothing leaves it without being sent or a tap of the person.
 */
interface EditStore {
    /** Stores a new command at the end of the queue and gives it back with its id. */
    fun add(command: EditCommand): EditCommand

    /** Replaces the stored command with the same id. */
    fun update(command: EditCommand)

    /** The waiting commands of this account, oldest first. */
    fun commands(account: String): List<EditCommand>

    fun remove(account: String, id: Long)

    fun addConflict(conflict: EditConflict): EditConflict

    fun conflicts(account: String): List<EditConflict>

    fun removeConflict(account: String, id: Long)

    /** Account removed: its unsent changes can not be sent without it. */
    fun forget(account: String)
}

class InMemoryEditStore : EditStore {
    private var nextId = 1L
    private val commands = ArrayList<EditCommand>()
    private val conflicts = ArrayList<EditConflict>()

    @Synchronized
    override fun add(command: EditCommand): EditCommand {
        val stored = command.copy(id = nextId++)
        commands += stored
        return stored
    }

    @Synchronized
    override fun update(command: EditCommand) {
        val i = commands.indexOfFirst { it.id == command.id && it.account == command.account }
        if (i >= 0) commands[i] = command
    }

    @Synchronized
    override fun commands(account: String): List<EditCommand> = commands.filter { it.account == account }.sortedBy { it.id }

    @Synchronized
    override fun remove(account: String, id: Long) {
        commands.removeAll { it.id == id && it.account == account }
    }

    @Synchronized
    override fun addConflict(conflict: EditConflict): EditConflict {
        val stored = conflict.copy(id = nextId++)
        conflicts += stored
        return stored
    }

    @Synchronized
    override fun conflicts(account: String): List<EditConflict> = conflicts.filter { it.account == account }.sortedBy { it.id }

    @Synchronized
    override fun removeConflict(account: String, id: Long) {
        conflicts.removeAll { it.id == id && it.account == account }
    }

    @Synchronized
    override fun forget(account: String) {
        commands.removeAll { it.account == account }
        conflicts.removeAll { it.account == account }
    }

    /** Everything stored as one text (tests look for secrets in it). */
    @Synchronized
    fun dump(): String = (commands.map { it.toString() } + conflicts.map { it.toString() }).joinToString("\n")
}
