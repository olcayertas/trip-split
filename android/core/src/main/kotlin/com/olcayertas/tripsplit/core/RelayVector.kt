package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject

/**
 * SPEC §10.4.4. Adds [ops] to a relay vector. An op extends its author only when it is the next seq, so a gap
 * in what the device knows never makes the vector claim more than the relay holds. [ops] come in §7 order.
 */
fun advanceRelayVector(vector: Map<String, Long>, ops: List<JsonObject>): Map<String, Long> {
    val out = LinkedHashMap(vector)
    for (op in ops.sortedBy { opSeq(it) }) {
        val author = opAuthor(op)
        if (opSeq(op) == (out[author] ?: 0L) + 1) out[author] = opSeq(op)
    }
    return out
}
