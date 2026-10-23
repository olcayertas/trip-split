package com.olcayertas.tripsplit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** `op_json` holds the canonical text of the op as received, so a relayed op keeps its hash. */
@Entity(
    tableName = "ops",
    primaryKeys = ["group_id", "author", "seq"],
    indices = [Index("group_id", "hlc")],
)
data class OpEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    val author: String,
    val seq: Long,
    val hlc: String,
    val type: String,
    @ColumnInfo(name = "op_json") val opJson: String,
    val hash: String,
    /** `ok` or `invalid` (PLAN §4.5). */
    val status: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "group_key", typeAffinity = ColumnInfo.BLOB) val groupKey: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    /** SPEC §10.4.4: the highest relay cursor that the device has read. */
    @ColumnInfo(name = "relay_cursor", defaultValue = "0") val relayCursor: Long = 0,
    /** SPEC §10.4.4: JSON object, author to the highest seq that the relay holds. */
    @ColumnInfo(name = "relay_vector", defaultValue = "{}") val relayVector: String = "{}",
) {
    override fun equals(other: Any?) = other is GroupEntity && other.groupId == groupId &&
        other.groupKey.contentEquals(groupKey) && other.createdAt == createdAt && other.lastUsedAt == lastUsedAt &&
        other.relayCursor == relayCursor && other.relayVector == relayVector

    override fun hashCode() = groupId.hashCode()
}

/** The one row of the device: identity and HLC state. */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "hlc_millis") val hlcMillis: Long,
    @ColumnInfo(name = "hlc_counter") val hlcCounter: Int,
    /** Earlier device ids of this install, comma separated. Set when a restore made a new id. */
    @ColumnInfo(name = "previous_ids") val previousIds: String = "",
)

@Entity(
    tableName = "forks",
    indices = [Index("group_id", "author", "seq", "foreign_hash", unique = true)],
)
data class ForkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: String,
    val author: String,
    val seq: Long,
    @ColumnInfo(name = "foreign_hash") val foreignHash: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)
