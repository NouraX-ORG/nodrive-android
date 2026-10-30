/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.devicesync.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Per account: whether device sync is on, when the backup before the first sync was done, and how the last sync went. */
@Entity(tableName = "ds_account", primaryKeys = ["account"])
class AccountEntity(
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "backup_done_utc") val backupDoneUtc: Long,
    @ColumnInfo(name = "last_sync_utc") val lastSyncUtc: Long,
    @ColumnInfo(name = "last_message") val lastMessage: String,
    @ColumnInfo(name = "waiting") val waiting: Int
)

/** A calendar or an address book of the account and what the sync knows about it. */
@Entity(tableName = "ds_collection", primaryKeys = ["account", "href"])
class CollectionEntity(
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "href") val href: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "read_only") val readOnly: Boolean,
    @ColumnInfo(name = "off_reason") val offReason: String,
    @ColumnInfo(name = "chosen") val chosen: Boolean,
    @ColumnInfo(name = "sync_token") val syncToken: String,
    @ColumnInfo(name = "android_id") val androidId: Long,
    @ColumnInfo(name = "first_sync_done") val firstSyncDone: Boolean,
    @ColumnInfo(name = "object_count") val objectCount: Int
)

/** One object of a collection: its ETag and the text the server last had (a phone edit patches this text). */
@Entity(tableName = "ds_object", primaryKeys = ["account", "href"])
class ObjectEntity(
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "href") val href: String,
    @ColumnInfo(name = "collection") val collection: String,
    @ColumnInfo(name = "etag") val etag: String,
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "original") val original: String,
    @ColumnInfo(name = "android_row_id") val androidRowId: Long,
    /** "synced", or "skipped" for an object that is not an event (a to-do): remembered by ETag, never shown on the phone. */
    @ColumnInfo(name = "state") val state: String
)

/** A phone version that lost a conflict (plan 4.5). */
@Entity(tableName = "ds_not_saved")
class NotSavedEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "account") val account: String,
    @ColumnInfo(name = "collection") val collection: String,
    @ColumnInfo(name = "href") val href: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "phone_text") val phoneText: String,
    /** The server's text that the phone's edit started from (so "Apply my change again" knows what the phone changed). */
    @ColumnInfo(name = "base_text") val baseText: String,
    @ColumnInfo(name = "created_utc") val createdUtc: Long
)

@Dao
interface DeviceSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAccount(row: AccountEntity)

    @Query("SELECT * FROM ds_account WHERE account = :account")
    fun account(account: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putCollection(row: CollectionEntity)

    @Query("SELECT * FROM ds_collection WHERE account = :account ORDER BY kind, name")
    fun collections(account: String): List<CollectionEntity>

    @Query("SELECT * FROM ds_collection WHERE account = :account AND href = :href")
    fun collection(account: String, href: String): CollectionEntity?

    @Query("DELETE FROM ds_collection WHERE account = :account AND href = :href")
    fun deleteCollection(account: String, href: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putObject(row: ObjectEntity)

    @Query("SELECT * FROM ds_object WHERE account = :account AND collection = :collection")
    fun objects(account: String, collection: String): List<ObjectEntity>

    @Query("SELECT * FROM ds_object WHERE account = :account AND href = :href")
    fun obj(account: String, href: String): ObjectEntity?

    @Query("SELECT COUNT(*) FROM ds_object WHERE account = :account AND collection = :collection AND state = 'synced'")
    fun syncedCount(account: String, collection: String): Int

    @Query("DELETE FROM ds_object WHERE account = :account AND href = :href")
    fun deleteObject(account: String, href: String)

    @Query("DELETE FROM ds_object WHERE account = :account AND collection = :collection")
    fun deleteObjects(account: String, collection: String)

    @Insert
    fun addNotSaved(row: NotSavedEntity): Long

    @Query("SELECT * FROM ds_not_saved WHERE account = :account ORDER BY created_utc")
    fun notSaved(account: String): List<NotSavedEntity>

    @Query("SELECT * FROM ds_not_saved WHERE id = :id")
    fun notSavedById(id: Long): NotSavedEntity?

    @Query("DELETE FROM ds_not_saved WHERE id = :id")
    fun deleteNotSaved(id: Long)

    @Query("DELETE FROM ds_account WHERE account = :account")
    fun deleteAccount(account: String)

    @Query("DELETE FROM ds_collection WHERE account = :account")
    fun deleteCollections(account: String)

    @Query("DELETE FROM ds_object WHERE account = :account")
    fun deleteAllObjects(account: String)

    @Query("DELETE FROM ds_not_saved WHERE account = :account")
    fun deleteAllNotSaved(account: String)
}

/**
 * The device sync's own database `nodrive_devicesync.db` (app-private, never in Google's backup). Version 1; later versions get real
 * migrations (no destructive fallback). It holds only copies of what the server has, plus the phone versions that lost a conflict.
 */
@Database(
    entities = [AccountEntity::class, CollectionEntity::class, ObjectEntity::class, NotSavedEntity::class],
    version = 1,
    exportSchema = true
)
abstract class DeviceSyncDatabase : RoomDatabase() {
    abstract fun dao(): DeviceSyncDao

    companion object {
        const val FILE_NAME = "nodrive_devicesync.db"

        @Volatile
        private var instance: DeviceSyncDatabase? = null

        fun get(context: Context): DeviceSyncDatabase = instance ?: synchronized(this) {
            // Settings shows "On, 2 calendars" from a few small rows on the main thread, like it reads its other values.
            instance ?: Room.databaseBuilder(context.applicationContext, DeviceSyncDatabase::class.java, FILE_NAME)
                .allowMainThreadQueries().build().also { instance = it }
        }
    }
}
