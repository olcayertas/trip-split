package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonObject

/** SHA-256 hex of the canonical op without its own `hash` key (SPEC §4.2). */
fun opHash(op: JsonObject): String {
    val body = JsonObject(op.filterKeys { it != "hash" })
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes(body))
    return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
}
