package com.olcayertas.tripsplit.core.relay

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.RelayState
import com.olcayertas.tripsplit.core.advanceRelayVector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** What one relay sync did. */
class RelaySyncResult(val storedOps: Int, val pushedOps: Int, val pushedBatches: Int)

/**
 * SPEC §10.4.4. Pull until `more` is false, then push what the relay lacks. It throws [RelayException]
 * when a request fails. The work done before the failure stays: the cursor and the vector are saved
 * after each page and each batch, so the next try goes on from there.
 */
class RelaySync(
    private val device: Device,
    private val api: RelayApi,
    private val pageLimit: Int = 50,
) {
    // One sync for each group at a time. The timer and a save can ask for a sync together.
    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Mutex()

    private suspend fun lockOf(groupId: String): Mutex = locksGuard.withLock { locks.getOrPut(groupId) { Mutex() } }

    suspend fun sync(groupId: String): RelaySyncResult = lockOf(groupId).withLock {
        val group = device.store.group(groupId) ?: return@withLock RelaySyncResult(0, 0, 0)
        val mailbox = mailboxId(group.key)
        val token = relayToken(group.key)
        val key = batchKey(group.key, groupId)

        var stored = 0
        while (true) {
            val after = device.store.relayState(groupId).cursor
            val page = api.pull(mailbox, token, after, pageLimit)
            for (batch in page.batches) {
                if (batch.cursor <= after) continue
                val ops = openBatch(key, mailbox, batch.blob)
                stored += device.receiveFromRelay(groupId, ops, batch.cursor).stored
            }
            // A relay that says "more" with no batch would make this loop forever.
            if (!page.more || page.batches.isEmpty()) break
        }

        var pushedOps = 0
        var pushedBatches = 0
        var state = device.store.relayState(groupId)
        for (batch in splitIntoBatches(device.store.missingFor(groupId, state.vector))) {
            api.push(mailbox, token, sealBatch(key, mailbox, batch))
            state = RelayState(state.cursor, advanceRelayVector(state.vector, batch))
            device.store.saveRelayState(groupId, state)
            pushedOps += batch.size
            pushedBatches++
        }
        RelaySyncResult(stored, pushedOps, pushedBatches)
    }
}

/** At most [MAX_BATCH_OPS] ops and [MAX_BATCH_PLAINTEXT] bytes of plaintext in a batch. [ops] keep their order. */
fun splitIntoBatches(ops: List<JsonObject>): List<List<JsonObject>> {
    val out = ArrayList<List<JsonObject>>()
    var current = ArrayList<JsonObject>()
    var size = batchPlaintextSize(emptyList())
    for (op in ops) {
        val opSize = batchPlaintextSize(listOf(op)) - batchPlaintextSize(emptyList()) + 1 // plus the comma
        if (current.isNotEmpty() && (current.size >= MAX_BATCH_OPS || size + opSize > MAX_BATCH_PLAINTEXT)) {
            out.add(current)
            current = ArrayList()
            size = batchPlaintextSize(emptyList())
        }
        current.add(op)
        size += opSize
    }
    if (current.isNotEmpty()) out.add(current)
    return out
}
