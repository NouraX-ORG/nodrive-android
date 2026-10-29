/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import androidx.annotation.Keep
import org.nourax.nodrive.agenda.AgendaLog
import org.nourax.nodrive.agenda.model.AgendaClock
import org.nourax.nodrive.agenda.model.Home
import org.nourax.nodrive.agenda.net.AgendaHttp
import org.nourax.nodrive.agenda.registry.AgentRegistry

/** The last result, shown once (a Snackbar). */
@Keep
data class EditNotice(
    val id: Long,
    val title: String = "",
    /** Titles of the tasks that NoGantt moved along. */
    val alsoMoved: List<String> = emptyList(),
    /** Set: the change can be taken back for a few seconds. */
    val undoOp: String = "",
    val cardNotMoved: Boolean = false,
    val checkInHome: Boolean = false,
    /** The change is kept on the phone and goes out when the phone is online. */
    val queued: Boolean = false,
    /** The change was not sent; it is in the "Not sent" list. */
    val conflict: Boolean = false,
    /** The result of a tap on Undo. */
    val undone: Boolean = false,
    val undoFailed: Boolean = false
)

/** The edits of one account as the screen shows them. */
@Keep
data class EditState(
    val pending: List<EditCommand> = emptyList(),
    val conflicts: List<EditConflict> = emptyList(),
    val notice: EditNotice? = null
) {
    fun isPending(key: String): Boolean = pending.any { it.itemKey == key }
}

class RunReport(
    /** The homes whose data has to be read again. */
    val homes: Set<Home>,
    val notice: EditNotice?,
    val waiting: Int,
    val newConflicts: Int
)

/**
 * Sends the queue (design 9, plan 2.6), one command at a time, oldest first. A command that reaches the server is taken out of the
 * queue; one that finds the item changed somewhere else becomes a conflict for the person to decide; one that meets no network
 * stays, and so does everything behind it. Nothing is dropped without a send or a tap.
 */
class EditRunner(
    private val account: String,
    http: AgendaHttp,
    private val store: EditStore,
    private val clock: AgendaClock,
    private val registry: () -> AgentRegistry,
    private val me: () -> String,
    private val myName: () -> String,
    /** Gantt or Deck read only by the version guard: nothing is written there. */
    private val guarded: (Home) -> Boolean = { false }
) {
    private val calDav = CalDavWriter(http)
    private val gantt = GanttWriter(http, registry, me, myName)
    private val deck = DeckWriter(http, { clock.zone() }, { clock.now() })
    private var noticeSeq = 0L

    // The kinds that change the same item in the same way can be folded into one command.
    private fun group(kind: CommandKind): Int = when (kind) {
        CommandKind.CAL_PATCH -> 1
        CommandKind.GANTT_UPDATE -> 2
        CommandKind.GANTT_AGENT -> 3
        CommandKind.DECK_UPDATE -> 4
        CommandKind.DECK_AGENT -> 5
        CommandKind.CAL_CREATE -> 6
        CommandKind.CAL_DELETE -> 7
    }

    /** Puts a command at the end of the queue. A second change of the same thing waiting there is folded into it (later values win). */
    fun enqueue(command: EditCommand): EditCommand {
        val stamped = command.copy(account = account, createdMs = clock.now().toEpochMilli(), state = CommandState.WAITING)
        val waiting = store.commands(account)
        if (stamped.kind == CommandKind.CAL_DELETE) {
            // The item is going away: the changes that wait for it are not needed. A new item that was never sent is just forgotten.
            val own = waiting.filter { it.itemKey == stamped.itemKey }
            own.forEach { store.remove(account, it.id) }
            if (own.any { it.kind == CommandKind.CAL_CREATE }) return stamped
        } else if (stamped.kind != CommandKind.CAL_CREATE) {
            val same = waiting.firstOrNull { it.itemKey == stamped.itemKey && group(it.kind) == group(stamped.kind) && it.state == CommandState.WAITING }
            if (same != null) {
                val merged = same.copy(change = same.change + stamped.change, attempts = 0)
                store.update(merged)
                return merged
            }
        }
        return store.add(stamped)
    }

    private fun nextNotice(base: EditNotice): EditNotice = base.copy(id = ++noticeSeq)

    /** Sends what waits. Stops at the first sign that there is no network. */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    fun run(): RunReport {
        val homes = HashSet<Home>()
        var notice: EditNotice? = null
        var conflicts = 0
        var offline = false
        for (stored in store.commands(account)) {
            if (offline) break
            // A command that was being sent when the app stopped may have reached the server: it is applied on the newest version.
            val command = if (stored.state == CommandState.SENDING) stored.copy(rebase = true) else stored
            store.update(command.copy(state = CommandState.SENDING))
            val result = try {
                send(command)
            } catch (e: Exception) {
                AgendaLog.w(TAG, "send failed: ${e.javaClass.simpleName}")
                SendResult.Offline
            }
            when (result) {
                is SendResult.Done -> {
                    store.remove(account, command.id)
                    homes += command.home
                    rebase(command, result.newVersion)
                    notice = nextNotice(
                        EditNotice(
                            0, command.title, result.alsoMoved, result.undoOp, result.cardNotMoved, result.checkInHome
                        )
                    )
                }
                SendResult.Offline -> {
                    store.update(command.copy(state = CommandState.WAITING, attempts = command.attempts + 1))
                    offline = true
                    notice = nextNotice(EditNotice(0, command.title, queued = true))
                }
                is SendResult.Conflict -> {
                    store.remove(account, command.id)
                    store.addConflict(
                        EditConflict(
                            0, account, command.copy(state = CommandState.WAITING, attempts = 0, rebase = false),
                            result.reason, result.detail, result.serverVersion, clock.now().toEpochMilli()
                        )
                    )
                    homes += command.home
                    conflicts++
                    notice = nextNotice(EditNotice(0, command.title, conflict = true))
                }
            }
        }
        val waiting = store.commands(account).size
        return RunReport(homes, notice, waiting, conflicts)
    }

    // The next change of the same item starts from the version this change made, so a person's own two edits never clash.
    private fun rebase(done: EditCommand, newVersion: String) {
        for (other in store.commands(account)) {
            if (other.itemKey != done.itemKey || other.id == done.id) continue
            store.update(if (newVersion.isEmpty()) other.copy(rebase = true) else other.copy(baseVersion = newVersion))
        }
    }

    private fun send(command: EditCommand): SendResult {
        if (command.home == Home.GANTT || command.home == Home.BOARD) {
            if (guarded(command.home)) return SendResult.Conflict(ConflictReason.REFUSED, "version_guard")
        }
        val now = clock.now()
        return when (command.kind) {
            CommandKind.CAL_PATCH, CommandKind.CAL_CREATE, CommandKind.CAL_DELETE -> calDav.send(command, now)
            CommandKind.GANTT_UPDATE -> gantt.update(command)
            CommandKind.GANTT_AGENT -> gantt.changeAgent(command)
            CommandKind.DECK_UPDATE -> deck.update(command)
            CommandKind.DECK_AGENT -> deck.changeAgent(command, registry(), me())
        }
    }

    /** The person's decision on a change that was not sent. [applyAgain]: send it again on top of the newest version. */
    fun resolve(conflictId: Long, applyAgain: Boolean): Boolean {
        val conflict = store.conflicts(account).firstOrNull { it.id == conflictId } ?: return false
        store.removeConflict(account, conflictId)
        if (!applyAgain) return false
        store.add(conflict.command.copy(id = 0, account = account, rebase = true, attempts = 0, state = CommandState.WAITING))
        return true
    }

    /** Takes a date change back (NoGantt's own undo). */
    fun undo(opId: String): EditNotice {
        val ok = gantt.undo(opId)
        return nextNotice(EditNotice(0, undone = ok, undoFailed = !ok))
    }

    fun state(notice: EditNotice?): EditState = EditState(store.commands(account), store.conflicts(account), notice)

    private companion object {
        const val TAG = "AgendaEdit"
    }
}
