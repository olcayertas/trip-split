package com.olcayertas.tripsplit.data

import androidx.room.withTransaction
import com.olcayertas.tripsplit.core.ClockState
import com.olcayertas.tripsplit.core.Fork
import com.olcayertas.tripsplit.core.GroupKey
import com.olcayertas.tripsplit.core.Invalid
import com.olcayertas.tripsplit.core.KNOWN_TYPES
import com.olcayertas.tripsplit.core.OpStore
import com.olcayertas.tripsplit.core.StoreResult
import com.olcayertas.tripsplit.core.canonical
import com.olcayertas.tripsplit.core.checkBody
import com.olcayertas.tripsplit.core.opAuthorOf
import com.olcayertas.tripsplit.core.opSeqOf
import com.olcayertas.tripsplit.core.versionVectorOf
import com.olcayertas.tripsplit.data.db.ForkEntity
import com.olcayertas.tripsplit.data.db.GroupEntity
import com.olcayertas.tripsplit.data.db.OpEntity
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Room implementation of the sync engine's store. Every call that writes runs in one transaction. */
class RoomOpStore(
    private val db: TripSplitDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : OpStore {
    override suspend fun groups(): List<GroupKey> = db.groups().all().map { GroupKey(it.groupId, it.groupKey) }

    override suspend fun group(groupId: String): GroupKey? =
        db.groups().get(groupId)?.let { GroupKey(it.groupId, it.groupKey) }

    override suspend fun addGroup(groupId: String, key: ByteArray) {
        val t = now()
        db.groups().insert(GroupEntity(groupId, key, t, t))
    }

    override suspend fun ops(groupId: String): List<JsonObject> =
        db.ops().opJson(groupId).map { Json.parseToJsonElement(it).jsonObject }

    override suspend fun clock(): ClockState =
        db.device().get()?.let { ClockState(it.hlcMillis, it.hlcCounter) } ?: ClockState()

    override suspend fun vector(groupId: String): Map<String, Long> =
        versionVectorOf(db.ops().authorSeqs(groupId).map { it.author to it.seq })

    override suspend fun insert(
        groupId: String,
        ops: List<JsonObject>,
        clockAfter: (List<JsonObject>) -> ClockState?,
    ): StoreResult = db.withTransaction {
        val t = now()
        val stored = ArrayList<JsonObject>()
        val forks = ArrayList<Fork>()
        var skipped = 0
        for (op in ops) {
            val author = opAuthorOf(op)
            val seq = opSeqOf(op)
            val hash = op.getValue("hash").jsonPrimitive.content
            val have = db.ops().hashOf(groupId, author, seq)
            when {
                have == null -> {
                    db.ops().insert(
                        OpEntity(
                            groupId, author, seq, op.getValue("hlc").jsonPrimitive.content,
                            op.getValue("type").jsonPrimitive.content, canonical(op), hash, statusOf(op), t,
                        ),
                    )
                    stored.add(op)
                }
                have == hash -> skipped++
                else -> {
                    db.forks().insert(ForkEntity(groupId = groupId, author = author, seq = seq, foreignHash = hash, seenAt = t))
                    forks.add(Fork(groupId, author, seq, hash))
                }
            }
        }
        clockAfter(stored)?.let { db.device().setClock(it.millis, it.counter) }
        if (stored.isNotEmpty()) db.groups().touch(groupId, t)
        StoreResult(stored, skipped, forks)
    }

    override suspend fun forks(groupId: String): List<Fork> =
        db.forks().forAll(groupId).map { Fork(it.groupId, it.author, it.seq, it.foreignHash) }

    /** PLAN §4.5: `invalid` for a known type with a bad body. Unknown types are kept as `ok` and relayed. */
    private fun statusOf(op: JsonObject): String {
        val type = op.getValue("type").jsonPrimitive.content
        if (type !in KNOWN_TYPES) return "ok"
        return try {
            checkBody(type, op["body"])
            "ok"
        } catch (_: Invalid) {
            "invalid"
        }
    }
}
