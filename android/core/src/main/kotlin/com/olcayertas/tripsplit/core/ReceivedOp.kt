package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** SPEC §8.3 step 3. [HASH] closes the session. [GROUP] and [SHAPE] drop only the op. */
enum class OpCheck { OK, GROUP, SHAPE, HASH }

private val ENVELOPE_KEYS = setOf("v", "group", "author", "seq", "hlc", "type", "body", "hash")
private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val HEX64 = Regex("[0-9a-f]{64}")
private const val MAX_SAFE = (1L shl 53) - 1

fun checkReceivedOp(op: JsonElement, groupId: String): OpCheck {
    if (op !is JsonObject || op.keys != ENVELOPE_KEYS) return OpCheck.SHAPE
    val v = intOrNull(op["v"])
    if (v == null || v < 1) return OpCheck.SHAPE
    if (strOrNull(op["group"]) != groupId) return OpCheck.GROUP
    val author = strOrNull(op["author"])
    if (author == null || !ID.matches(author)) return OpCheck.SHAPE
    val seq = intOrNull(op["seq"])
    if (seq == null || seq < 1 || seq > MAX_SAFE) return OpCheck.SHAPE
    val hlc = strOrNull(op["hlc"]) ?: return OpCheck.SHAPE
    val stamp = try {
        hlcParse(hlc)
    } catch (_: IllegalArgumentException) {
        return OpCheck.SHAPE
    }
    if (stamp.device != author) return OpCheck.SHAPE
    if (strOrNull(op["type"]) == null || op["body"] !is JsonObject) return OpCheck.SHAPE
    val hash = strOrNull(op["hash"])
    if (hash == null || !HEX64.matches(hash)) return OpCheck.SHAPE
    // Values the canonical writer rejects (a float, a too large integer) are a shape error.
    val computed = try {
        opHash(op)
    } catch (_: CanonicalJsonException) {
        return OpCheck.SHAPE
    }
    return if (computed == hash) OpCheck.OK else OpCheck.HASH
}
