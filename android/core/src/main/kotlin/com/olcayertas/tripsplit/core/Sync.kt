package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject

/** SPEC §7. Highest n such that seq 1..n of the author is present. Authors with n = 0 are left out. */
fun versionVector(ops: List<JsonObject>): Map<String, Long> =
    versionVectorOf(ops.map { opAuthor(it) to opSeq(it) })

/** The same from `(author, seq)` pairs, so a store can answer without it loading every op. */
fun versionVectorOf(pairs: Iterable<Pair<String, Long>>): Map<String, Long> {
    val seqs = HashMap<String, MutableSet<Long>>()
    for ((author, seq) in pairs) seqs.getOrPut(author) { HashSet() }.add(seq)
    val vector = LinkedHashMap<String, Long>()
    for ((author, s) in seqs) {
        var n = 0L
        while ((n + 1) in s) n++
        if (n > 0) vector[author] = n
    }
    return vector
}

/** Refs ("author:seq") of the ops the peer lacks, ordered by author bytes then seq. */
fun missingFor(peerVector: Map<String, Long>, ops: List<JsonObject>): List<String> {
    val missing = ops.filter { opSeq(it) > (peerVector[opAuthor(it)] ?: 0L) }
    return missing
        .sortedWith { a, b ->
            val c = byteCompare(opAuthor(a), opAuthor(b))
            if (c != 0) c else opSeq(a).compareTo(opSeq(b))
        }
        .map { opRef(it) }
}
