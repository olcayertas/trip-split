package com.olcayertas.tripsplit.core

import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What [Device.receive] did with the ops of one `ops` message. */
class ReceiveResult(val stored: Int, val skipped: Int, val dropped: Int, val forks: Int, val skew: Boolean)

/** A bad hash in a received batch. The session closes (SPEC §8.3 step 3). */
class BadHashException : Exception("bad op hash")

/**
 * The state that all sessions of one device share: the device id, the HLC and the store. One mutex
 * serialises every change, so two sessions cannot use the same HLC value or the same `seq`.
 */
class Device(
    val deviceId: String,
    val store: OpStore,
    val wall: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()
    private var hlc: Hlc? = null

    private suspend fun clockLocked(): Hlc =
        hlc ?: store.clock().let { Hlc(deviceId, it.millis, it.counter) }.also { hlc = it }

    private fun Hlc.state() = ClockState(millis, counter)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Writes one op of this device into [groupId]. The `seq` is the next one of this author. */
    suspend fun append(groupId: String, type: String, body: JsonObject): JsonObject = mutex.withLock {
        val clock = clockLocked()
        val next = Hlc(deviceId, clock.millis, clock.counter)
        val seq = (store.vector(groupId)[deviceId] ?: 0L) + 1
        val stamp = next.send(wall())
        val unsigned = buildJsonObject {
            put("v", 1)
            put("group", groupId)
            put("author", deviceId)
            put("seq", seq)
            put("hlc", stamp)
            put("type", type)
            put("body", body)
        }
        val op = JsonObject(unsigned + ("hash" to JsonPrimitive(opHash(unsigned))))
        store.insert(groupId, listOf(op)) { next.state() }
        hlc = next
        op
    }

    /** Makes a group: id, random key, and the `group.create` op. Returns the invite for the key. */
    suspend fun createGroup(name: String, baseCurrency: String, creatorName: String): CreatedGroup {
        val groupId = newId()
        val key = randomBytes(32)
        store.addGroup(groupId, key)
        val creatorMember = newId()
        append(
            groupId, "group.create",
            buildJsonObject {
                put("name", name)
                put("baseCurrency", baseCurrency)
                put("creatorMemberId", creatorMember)
                put("creatorName", creatorName)
            },
        )
        return CreatedGroup(groupId, key, creatorMember)
    }

    /** SPEC §9 / PLAN §7: store id and key. The first sync downloads the log. */
    suspend fun join(invite: Invite) = store.addGroup(invite.groupId, invite.groupKey)

    /**
     * SPEC §8.3 steps 3 to 5 for the ops of one message. Throws [BadHashException] before it stores
     * anything when one op has a wrong hash.
     */
    suspend fun receive(groupId: String, raw: List<JsonElement>): ReceiveResult = mutex.withLock {
        val accepted = ArrayList<JsonObject>()
        var dropped = 0
        for (op in raw) {
            when (checkReceivedOp(op, groupId)) {
                OpCheck.OK -> accepted.add(op as JsonObject)
                OpCheck.HASH -> throw BadHashException()
                else -> dropped++
            }
        }
        val clock = clockLocked()
        val next = Hlc(deviceId, clock.millis, clock.counter)
        var skew = false
        val result = store.insert(groupId, accepted) { stored ->
            val now = wall()
            for (op in stored) if (next.receive(strOrNull(op["hlc"])!!, now)) skew = true
            if (stored.isEmpty()) null else next.state()
        }
        if (result.stored.isNotEmpty()) hlc = next
        ReceiveResult(result.stored.size, result.skipped, dropped, result.forks.size, skew)
    }
}

class CreatedGroup(val groupId: String, val key: ByteArray, val creatorMemberId: String) {
    fun invite(): String = makeInvite(groupId, key)
}
