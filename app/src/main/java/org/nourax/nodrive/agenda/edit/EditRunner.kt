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
import java.time.LocalDate

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
    val undoFailed: Boolean = false,
    /** A date change from the Timeline: the dates the item has now (a to-do or card: the ones sent; a NoGantt task: the ones saved). */
    val movedStart: LocalDate? = null,
    val movedEnd: LocalDate? = null,
    /** NoGantt saved other dates than the ones sent (working days or dependencies). */
    val datesAdjusted: Boolean = false
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
 * stays, and so does everything behind it that goes to the same place. The places are CalDAV (events and to-dos), NoGantt and
 * NoBoard, and they do not hold each other back: a NoBoard that does not answer never stops a calendar edit. A command that meets
 * a server error again and again moves to "Not sent" after [MAX_SERVER_ERRORS] tries. Nothing is dropped without a send or a tap.
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
    private val guarded: (Home) -> Boolean = { false },
    /**
     * Called once per run before the first NoGantt or NoBoard command is sent: it reads the version guard again (the server may have
     * been updated while the changes waited). Null = go on; otherwise this is what the commands of that place get instead (the guard
     * could not be read now: wait; or the place refuses the sign-in), so nothing is written there on an old guard.
     */
    private val prepare: (Home) -> SendResult? = { null }
) {
    private val calDav = CalDavWriter(http) { clock.zone() }
    private val gantt = GanttWriter(http, registry, me, myName)
    private val deck = DeckWriter(http, { clock.zone() }, { clock.now() })
    private var noticeSeq = 0L

    // The queue is changed from two threads: the screen stores a change while a refresh may be sending. This lock keeps a read and the
    // write that follows it together, so a change that is folded into a waiting one is never lost.
    private val queueLock = Any()

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

    // The three places a change goes to. A wait in one of them stops only the commands of that place.
    private fun lane(home: Home): Int = when (home) {
        Home.CALENDAR, Home.TASKS -> LANE_CALDAV
        Home.GANTT -> LANE_GANTT
        Home.BOARD -> LANE_BOARD
    }

    /** Whether any change waits (also one that is being sent). */
    fun hasPending(): Boolean = store.commands(account).isNotEmpty()

    /** Puts a command at the end of the queue. A second change of the same thing waiting there is folded into it (later values win). */
    fun enqueue(command: EditCommand): EditCommand = synchronized(queueLock) {
        val stamped = command.copy(account = account, createdMs = clock.now().toEpochMilli(), state = CommandState.WAITING)
        val waiting = store.commands(account)
        if (stamped.kind == CommandKind.CAL_DELETE) {
            // The item is going away: the changes that wait for it are not needed. A new item that was never sent is just forgotten.
            val own = waiting.filter { it.itemKey == stamped.itemKey }
            own.forEach { store.remove(account, it.id) }
            if (own.any { it.kind == CommandKind.CAL_CREATE }) return@synchronized stamped
        } else if (stamped.kind != CommandKind.CAL_CREATE) {
            val same = waiting.firstOrNull { it.itemKey == stamped.itemKey && group(it.kind) == group(stamped.kind) && it.state == CommandState.WAITING }
            if (same != null) {
                // A newer date change decides how the dates are sent (its own "move" mark, or none). A newer change without any date
                // keeps the older move's mark, so the move still goes out with its dates as the person asked.
                val newerHasDates = stamped.change.keys.any { it == EditKeys.START || it == EditKeys.END || it == EditKeys.DUE }
                val change = (same.change + stamped.change).let { if (EditKeys.MOVE in stamped.change || !newerHasDates) it else it - EditKeys.MOVE }
                val merged = same.copy(change = change, attempts = 0)
                store.update(merged)
                return@synchronized merged
            }
        }
        store.add(stamped)
    }

    private fun nextNotice(base: EditNotice): EditNotice = base.copy(id = ++noticeSeq)

    /** The Timeline's date editor: the notice says the dates the item has now, and whether NoGantt changed what was sent. */
    private fun movedDates(notice: EditNotice, command: EditCommand, result: SendResult.Done): EditNotice {
        if (command.change[EditKeys.MOVE] == null) return notice
        fun day(key: String): LocalDate? = command.change[key]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = day(EditKeys.MOVE_END)
        val start = day(EditKeys.MOVE_START)
        val savedStart = result.savedStart ?: start
        val savedEnd = result.savedEnd ?: end
        val adjusted = (result.savedStart != null && start != null && result.savedStart != start) ||
            (result.savedEnd != null && end != null && result.savedEnd != end)
        return notice.copy(movedStart = savedStart, movedEnd = savedEnd, datesAdjusted = adjusted)
    }

    /** Sends what waits. A place that does not answer is skipped for the rest of the run; the other places go on. */
    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "LongMethod")
    fun run(): RunReport {
        val homes = HashSet<Home>()
        var notice: EditNotice? = null
        var conflicts = 0
        val stopped = HashSet<Int>()
        val prepared = HashMap<Home, SendResult?>()
        // Each command is read again from the queue when its turn comes: a command that was sent before it has already given it the
        // version it made (see rebase).
        for (id in store.commands(account).map { it.id }) {
            val command = take(id, stopped) ?: continue
            val result = try {
                send(command, prepared)
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
                        movedDates(
                            EditNotice(0, command.title, result.alsoMoved, result.undoOp, result.cardNotMoved, result.checkInHome),
                            command,
                            result
                        )
                    )
                }
                SendResult.Offline -> {
                    store.update(command.copy(state = CommandState.WAITING, attempts = command.attempts + 1))
                    stopped += lane(command.home)
                    notice = nextNotice(EditNotice(0, command.title, queued = true))
                }
                is SendResult.HalfDone -> {
                    store.update(
                        command.copy(
                            state = CommandState.WAITING,
                            attempts = command.attempts + 1,
                            baseVersion = result.newBase.ifEmpty { command.baseVersion },
                            rebase = result.newBase.isEmpty()
                        )
                    )
                    stopped += lane(command.home)
                    notice = nextNotice(EditNotice(0, command.title, queued = true))
                }
                is SendResult.ServerError -> {
                    val failures = command.serverErrors + 1
                    if (failures >= MAX_SERVER_ERRORS) {
                        // The server keeps answering with an error: the change moves to "Not sent" and no longer holds the others back.
                        moveToNotSent(command, SendResult.Conflict(ConflictReason.OTHER, "http${result.status}"))
                        homes += command.home
                        conflicts++
                        notice = nextNotice(EditNotice(0, command.title, conflict = true))
                    } else {
                        store.update(command.copy(state = CommandState.WAITING, attempts = command.attempts + 1, serverErrors = failures))
                        stopped += lane(command.home)
                        notice = nextNotice(EditNotice(0, command.title, queued = true))
                    }
                }
                is SendResult.Conflict -> {
                    moveToNotSent(command, result)
                    homes += command.home
                    conflicts++
                    notice = nextNotice(EditNotice(0, command.title, conflict = true))
                }
            }
        }
        val waiting = store.commands(account).size
        return RunReport(homes, notice, waiting, conflicts)
    }

    // The command with this id as it stands now, marked as being sent; null when it is gone or its place is stopped in this run.
    private fun take(id: Long, stopped: Set<Int>): EditCommand? = synchronized(queueLock) {
        val stored = store.commands(account).firstOrNull { it.id == id } ?: return@synchronized null
        if (lane(stored.home) in stopped) return@synchronized null
        // A command that was being sent when the app stopped may have reached the server: it is checked against the server's
        // version, and counts as done when the server already has exactly this change. It is never written without that check.
        val command = if (stored.state == CommandState.SENDING) stored.copy(recovered = true) else stored
        store.update(command.copy(state = CommandState.SENDING))
        command
    }

    private fun moveToNotSent(command: EditCommand, result: SendResult.Conflict) {
        store.remove(account, command.id)
        store.addConflict(
            EditConflict(
                0, account, command.copy(state = CommandState.WAITING, attempts = 0, serverErrors = 0, rebase = false, recovered = false),
                result.reason, result.detail, result.serverVersion, clock.now().toEpochMilli()
            )
        )
    }

    // The next change of the same item starts from the version this change made, so a person's own two edits never clash.
    private fun rebase(done: EditCommand, newVersion: String) = synchronized(queueLock) {
        for (other in store.commands(account)) {
            if (other.itemKey != done.itemKey || other.id == done.id) continue
            store.update(if (newVersion.isEmpty()) other.copy(rebase = true) else other.copy(baseVersion = newVersion))
        }
    }

    private fun send(command: EditCommand, prepared: MutableMap<Home, SendResult?>): SendResult {
        if (command.home == Home.GANTT || command.home == Home.BOARD) {
            // The version guard is read again once per run before anything is written to NoGantt or NoBoard.
            if (!prepared.containsKey(command.home)) prepared[command.home] = prepare(command.home)
            prepared[command.home]?.let { return it }
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
        store.add(
            conflict.command.copy(id = 0, account = account, rebase = true, attempts = 0, serverErrors = 0, recovered = false, state = CommandState.WAITING)
        )
        return true
    }

    /**
     * "Send again" on a change the server could not take (see [EditConflict.canSendAgain]). Unlike [resolve] it puts the command back
     * with the version the person saw and without `rebase`, so the check for a change made elsewhere still runs.
     */
    fun sendAgain(conflictId: Long): Boolean {
        val conflict = store.conflicts(account).firstOrNull { it.id == conflictId } ?: return false
        store.removeConflict(account, conflictId)
        store.add(
            conflict.command.copy(id = 0, account = account, rebase = false, attempts = 0, serverErrors = 0, recovered = false, state = CommandState.WAITING)
        )
        return true
    }

    /** Takes a date change back (NoGantt's own undo). */
    fun undo(opId: String): EditNotice {
        val ok = gantt.undo(opId)
        return nextNotice(EditNotice(0, undone = ok, undoFailed = !ok))
    }

    fun state(notice: EditNotice?): EditState = EditState(store.commands(account), store.conflicts(account), notice)

    companion object {
        private const val TAG = "AgendaEdit"
        private const val LANE_CALDAV = 0
        private const val LANE_GANTT = 1
        private const val LANE_BOARD = 2

        /** After this many server errors (see [EditCommand.serverErrors]) a change moves to "Not sent". */
        const val MAX_SERVER_ERRORS = 5
    }
}
