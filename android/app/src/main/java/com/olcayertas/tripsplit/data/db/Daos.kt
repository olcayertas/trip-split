package com.olcayertas.tripsplit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class AuthorSeq(val author: String, val seq: Long)

@Dao
interface OpDao {
    /** Returns the row id, or -1 when the `(group, author, seq)` is already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: OpEntity): Long

    @Query("SELECT hash FROM ops WHERE group_id = :groupId AND author = :author AND seq = :seq")
    suspend fun hashOf(groupId: String, author: String, seq: Long): String?

    @Query("SELECT op_json FROM ops WHERE group_id = :groupId ORDER BY author, seq")
    suspend fun opJson(groupId: String): List<String>

    @Query("SELECT op_json FROM ops WHERE group_id = :groupId")
    fun observeOpJson(groupId: String): Flow<List<String>>

    @Query("SELECT author, seq FROM ops WHERE group_id = :groupId")
    suspend fun authorSeqs(groupId: String): List<AuthorSeq>

    @Query("SELECT COUNT(*) FROM ops WHERE group_id = :groupId")
    suspend fun count(groupId: String): Int
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: GroupEntity): Long

    @Query("SELECT * FROM groups WHERE group_id = :groupId")
    suspend fun get(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups ORDER BY last_used_at DESC, group_id")
    suspend fun all(): List<GroupEntity>

    @Query("SELECT * FROM groups ORDER BY last_used_at DESC, group_id")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("UPDATE groups SET last_used_at = :at WHERE group_id = :groupId")
    suspend fun touch(groupId: String, at: Long)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device WHERE id = 1")
    suspend fun get(): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(device: DeviceEntity)

    @Query("UPDATE device SET hlc_millis = :millis, hlc_counter = :counter WHERE id = 1")
    suspend fun setClock(millis: Long, counter: Int)

    @Query("UPDATE device SET display_name = :name WHERE id = 1")
    suspend fun setName(name: String)

    @Query("SELECT * FROM device WHERE id = 1")
    fun observe(): Flow<DeviceEntity?>
}

@Dao
interface ForkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(fork: ForkEntity): Long

    @Query("SELECT * FROM forks WHERE group_id = :groupId")
    suspend fun forAll(groupId: String): List<ForkEntity>
}
