/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.edit

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.google.gson.Gson

/** One waiting change. The command itself is one JSON (no password or token, only the semantic change). */
@Entity(tableName = "agenda_command")
class CommandEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "created_utc") val createdUtc: Long,
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "json") val json: String
)

/** One change that was not sent, with the reason. */
@Entity(tableName = "agenda_conflict")
class ConflictEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "created_utc") val createdUtc: Long,
    @ColumnInfo(name = "json") val json: String
)

@Dao
interface EditDao {
    @Insert
    fun insertCommand(row: CommandEntity): Long

    @Update
    fun updateCommand(row: CommandEntity)

    @Query("SELECT * FROM agenda_command WHERE account = :account ORDER BY id ASC")
    fun commands(account: String): List<CommandEntity>

    @Query("DELETE FROM agenda_command WHERE account = :account AND id = :id")
    fun removeCommand(account: String, id: Long)

    @Insert
    fun insertConflict(row: ConflictEntity): Long

    @Query("SELECT * FROM agenda_conflict WHERE account = :account ORDER BY id ASC")
    fun conflicts(account: String): List<ConflictEntity>

    @Query("DELETE FROM agenda_conflict WHERE account = :account AND id = :id")
    fun removeConflict(account: String, id: Long)

    @Query("DELETE FROM agenda_command WHERE account = :account")
    fun deleteCommands(account: String)

    @Query("DELETE FROM agenda_conflict WHERE account = :account")
    fun deleteConflicts(account: String)
}

/**
 * The offline queue `nodrive_agenda_edit.db` (app-private, never in Google's backup). A file of its own, so the cache of the
 * read step (`nodrive_agenda.db`) needs no migration and a problem with one never touches the other. Version 1.
 */
@Database(entities = [CommandEntity::class, ConflictEntity::class], version = 1, exportSchema = true)
abstract class EditDatabase : RoomDatabase() {
    abstract fun dao(): EditDao

    companion object {
        const val FILE_NAME = "nodrive_agenda_edit.db"

        @Volatile
        private var instance: EditDatabase? = null

        fun get(context: Context): EditDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EditDatabase::class.java, FILE_NAME).build().also { instance = it }
        }

        fun inMemory(context: Context): EditDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, EditDatabase::class.java).build()
    }
}

class RoomEditStore(private val db: EditDatabase) : EditStore {
    private val dao get() = db.dao()
    private val gson = Gson()

    private fun command(row: CommandEntity): EditCommand? = try {
        gson.fromJson(row.json, EditCommand::class.java)?.copy(id = row.id)
    } catch (_: Exception) {
        null
    }

    private fun conflict(row: ConflictEntity): EditConflict? = try {
        gson.fromJson(row.json, EditConflict::class.java)?.copy(id = row.id)
    } catch (_: Exception) {
        null
    }

    override fun add(command: EditCommand): EditCommand {
        val id = dao.insertCommand(CommandEntity(0, command.account, command.createdMs, command.itemKey, gson.toJson(command)))
        return command.copy(id = id)
    }

    override fun update(command: EditCommand) {
        dao.updateCommand(CommandEntity(command.id, command.account, command.createdMs, command.itemKey, gson.toJson(command)))
    }

    override fun commands(account: String): List<EditCommand> = dao.commands(account).mapNotNull { command(it) }

    override fun remove(account: String, id: Long) = dao.removeCommand(account, id)

    override fun addConflict(conflict: EditConflict): EditConflict {
        val id = dao.insertConflict(ConflictEntity(0, conflict.account, conflict.createdMs, gson.toJson(conflict)))
        return conflict.copy(id = id)
    }

    override fun conflicts(account: String): List<EditConflict> = dao.conflicts(account).mapNotNull { conflict(it) }

    override fun removeConflict(account: String, id: Long) = dao.removeConflict(account, id)

    override fun forget(account: String) {
        db.runInTransaction {
            dao.deleteCommands(account)
            dao.deleteConflicts(account)
        }
    }
}
