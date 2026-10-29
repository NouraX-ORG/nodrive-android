/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.agenda.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** The settings of one account (per phone; design 6.1). One JSON. */
@Entity(tableName = "agenda_settings", primaryKeys = ["account"])
class SettingsEntity(
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "json") val json: String
)

/** One home snapshot of one account. [cacheSchema] lets a later version ignore rows it can not read. */
@Entity(tableName = "agenda_source", primaryKeys = ["account", "source"])
class SourceEntity(
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "etag") val etag: String,
    @ColumnInfo(name = "fetched_utc") val fetchedUtc: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "cache_schema") val cacheSchema: Int
)

@Dao
interface AgendaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSettings(row: SettingsEntity)

    @Query("SELECT * FROM agenda_settings WHERE account = :account")
    fun settings(account: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSource(row: SourceEntity)

    @Query("SELECT * FROM agenda_source WHERE account = :account AND source = :source")
    fun source(account: String, source: String): SourceEntity?

    @Query("DELETE FROM agenda_settings WHERE account = :account")
    fun deleteSettings(account: String)

    @Query("DELETE FROM agenda_source WHERE account = :account")
    fun deleteSources(account: String)
}

/**
 * NoAgenda's own offline cache `nodrive_agenda.db` (app-private, never in Google's backup). Version 1; later versions get
 * real migrations (no destructive fallback). It holds only copies of data that the server already has.
 */
@Database(entities = [SettingsEntity::class, SourceEntity::class], version = 1, exportSchema = true)
abstract class AgendaDatabase : RoomDatabase() {
    abstract fun dao(): AgendaDao

    companion object {
        const val FILE_NAME = "nodrive_agenda.db"

        @Volatile
        private var instance: AgendaDatabase? = null

        fun get(context: Context): AgendaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgendaDatabase::class.java, FILE_NAME).build().also { instance = it }
        }

        fun inMemory(context: Context): AgendaDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AgendaDatabase::class.java).build()
    }
}

class RoomAgendaStore(private val db: AgendaDatabase) : AgendaStore {
    private val dao get() = db.dao()

    override fun loadSettings(account: String): String? = dao.settings(account)?.json

    override fun saveSettings(account: String, json: String) = dao.putSettings(SettingsEntity(account, json))

    override fun read(account: String, source: String): StoredSource? {
        val row = dao.source(account, source) ?: return null
        // A row written by another version of the cache is ignored; a fresh read follows.
        if (row.cacheSchema != CACHE_SCHEMA) return null
        return StoredSource(row.json, row.etag, row.fetchedUtc, row.status)
    }

    override fun write(account: String, source: String, stored: StoredSource) =
        dao.putSource(SourceEntity(account, source, stored.json, stored.etag, stored.fetchedMs, stored.status, CACHE_SCHEMA))

    override fun forget(account: String) {
        db.runInTransaction {
            dao.deleteSettings(account)
            dao.deleteSources(account)
        }
    }

    companion object {
        const val CACHE_SCHEMA = 1
    }
}
