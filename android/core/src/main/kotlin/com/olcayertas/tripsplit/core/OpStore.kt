package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject

/** A group the device holds: id and 32-byte key. */
class GroupKey(val groupId: String, val key: ByteArray) {
    val tag: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { discoveryTag(key) }
}

/** Persisted `(millis, counter)` of the device HLC (SPEC §3). */
data class ClockState(val millis: Long = 0, val counter: Int = 0)

/** An op that arrived with an `(author, seq)` the device holds with another hash (SPEC §4.2). */
data class Fork(val groupId: String, val author: String, val seq: Long, val foreignHash: String)

/**
 * SPEC §10.4.4. [cursor] is the highest relay cursor the device has read. [vector] maps an author to the
 * highest `n` such that the relay holds seq `1..n` of that author, as far as the device knows.
 */
data class RelayState(val cursor: Long = 0, val vector: Map<String, Long> = emptyMap())

class StoreResult(val stored: List<JsonObject>, val skipped: Int, val forks: List<Fork>)

/**
 * The persistence port of the sync engine. Every method is one atomic unit. The in-memory store
 * ([InMemoryOpStore]) and the Room store (phase 3) both implement it.
 */
interface OpStore {
    suspend fun groups(): List<GroupKey>

    suspend fun group(groupId: String): GroupKey?

    /** Idempotent: the same id with the same key changes nothing. */
    suspend fun addGroup(groupId: String, key: ByteArray)

    suspend fun ops(groupId: String): List<JsonObject>

    suspend fun clock(): ClockState

    /**
     * Stores the ops that are new, skips an `(author, seq)` held with the same hash and records a fork for
     * one held with another hash. In the same transaction it asks [clockAfter] for the new clock, with the
     * ops that were stored, and persists it when the answer is not null. Safe to call again with the same ops.
     */
    suspend fun insert(groupId: String, ops: List<JsonObject>, clockAfter: (stored: List<JsonObject>) -> ClockState?): StoreResult

    suspend fun forks(groupId: String): List<Fork>

    /** The relay position of a group. Empty for a group that never synced with the relay. */
    suspend fun relayState(groupId: String): RelayState

    /** Writes the relay position alone, after a push. */
    suspend fun saveRelayState(groupId: String, state: RelayState)

    /**
     * Like [insert], and in the same transaction it saves [relay]. A crash cannot leave the cursor ahead of
     * the ops that it covers (SPEC §10.4.4 step 1). [ops] can be empty: the cursor moves past a bad blob.
     */
    suspend fun insertFromRelay(
        groupId: String,
        ops: List<JsonObject>,
        relay: RelayState,
        clockAfter: (stored: List<JsonObject>) -> ClockState?,
    ): StoreResult

    suspend fun vector(groupId: String): Map<String, Long> = versionVector(ops(groupId))

    suspend fun missingFor(groupId: String, peerVector: Map<String, Long>): List<JsonObject> {
        val all = ops(groupId)
        val refs = missingFor(peerVector, all).toHashSet()
        return all.filter { opRef(it) in refs }.sortedWith { a, b ->
            val c = byteCompare(opAuthor(a), opAuthor(b))
            if (c != 0) c else opSeq(a).compareTo(opSeq(b))
        }
    }
}

/** A store in memory, for tests and fake devices. Operations take a lock, so each one is atomic. */
class InMemoryOpStore : OpStore {
    private val lock = Any()
    private val groupKeys = LinkedHashMap<String, GroupKey>()
    private val byGroup = HashMap<String, LinkedHashMap<Pair<String, Long>, JsonObject>>()
    private val forkList = ArrayList<Fork>()
    private var clock = ClockState()
    private val relayStates = HashMap<String, RelayState>()

    override suspend fun groups(): List<GroupKey> = synchronized(lock) { groupKeys.values.toList() }

    override suspend fun group(groupId: String): GroupKey? = synchronized(lock) { groupKeys[groupId] }

    override suspend fun addGroup(groupId: String, key: ByteArray) {
        synchronized(lock) { groupKeys.getOrPut(groupId) { GroupKey(groupId, key.copyOf()) } }
    }

    override suspend fun ops(groupId: String): List<JsonObject> =
        synchronized(lock) { byGroup[groupId]?.values?.toList() ?: emptyList() }

    override suspend fun clock(): ClockState = synchronized(lock) { clock }

    override suspend fun relayState(groupId: String): RelayState = synchronized(lock) { relayStates[groupId] ?: RelayState() }

    override suspend fun saveRelayState(groupId: String, state: RelayState) {
        synchronized(lock) { relayStates[groupId] = state }
    }

    override suspend fun insertFromRelay(
        groupId: String,
        ops: List<JsonObject>,
        relay: RelayState,
        clockAfter: (List<JsonObject>) -> ClockState?,
    ): StoreResult = synchronized(lock) {
        val result = insertLocked(groupId, ops, clockAfter)
        relayStates[groupId] = relay
        result
    }

    override suspend fun insert(
        groupId: String,
        ops: List<JsonObject>,
        clockAfter: (List<JsonObject>) -> ClockState?,
    ): StoreResult = synchronized(lock) { insertLocked(groupId, ops, clockAfter) }

    private fun insertLocked(
        groupId: String,
        ops: List<JsonObject>,
        clockAfter: (List<JsonObject>) -> ClockState?,
    ): StoreResult {
        val map = byGroup.getOrPut(groupId) { LinkedHashMap() }
        val stored = ArrayList<JsonObject>()
        val forks = ArrayList<Fork>()
        var skipped = 0
        for (op in ops) {
            val key = opAuthor(op) to opSeq(op)
            val have = map[key]
            when {
                have == null -> {
                    map[key] = op
                    stored.add(op)
                }
                have["hash"] == op["hash"] -> skipped++
                else -> forks.add(Fork(groupId, key.first, key.second, strOrNull(op["hash"]) ?: ""))
            }
        }
        for (f in forks) if (forkList.none { it == f }) forkList.add(f)
        clockAfter(stored)?.let { clock = it }
        return StoreResult(stored, skipped, forks)
    }

    override suspend fun forks(groupId: String): List<Fork> =
        synchronized(lock) { forkList.filter { it.groupId == groupId } }
}
