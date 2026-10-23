package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 1
const val MAX_OPS_PER_MESSAGE = 64
const val MAX_OPS_PLAINTEXT = 256 * 1024

/** SPEC §8.2. */
sealed interface Message

data class Hello(
    val protocolVersion: Long,
    val deviceId: String,
    val appVersion: String,
    val groupId: String,
    val vector: Map<String, Long>,
    val wallMillis: Long,
    val maxChunk: Long,
) : Message

/** The ops are raw elements: the receiver checks each one (SPEC §8.3 step 3). */
data class OpsMessage(val ops: List<JsonElement>) : Message

data class Done(val vector: Map<String, Long>) : Message

private fun vectorJson(v: Map<String, Long>) = JsonObject(v.mapValues { JsonPrimitive(it.value) })

fun encodeMessage(m: Message): ByteArray = canonicalBytes(
    when (m) {
        is Hello -> buildJsonObject {
            put("t", "hello")
            put("protocolVersion", m.protocolVersion)
            put("deviceId", m.deviceId)
            put("appVersion", m.appVersion)
            put("groupId", m.groupId)
            put("vector", vectorJson(m.vector))
            put("wallMillis", m.wallMillis)
            put("maxChunk", m.maxChunk)
        }
        is OpsMessage -> buildJsonObject {
            put("t", "ops")
            put("ops", JsonArray(m.ops))
        }
        is Done -> buildJsonObject {
            put("t", "done")
            put("vector", vectorJson(m.vector))
        }
    },
)

private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun parseVector(e: JsonElement?): Map<String, Long> {
    if (e !is JsonObject) throw WireException("bad vector")
    return e.mapValues { intOrNull(it.value) ?: throw WireException("bad vector") }
}

/** Throws [WireException] for bytes that are not a message. A key the receiver does not know is ignored. */
fun decodeMessage(plaintext: ByteArray): Message {
    val root = try {
        Json.parseToJsonElement(String(plaintext, Charsets.UTF_8))
    } catch (_: Exception) {
        throw WireException("frame is not JSON")
    }
    if (root !is JsonObject) throw WireException("message is not an object")
    return when (val t = strOrNull(root["t"])) {
        "hello" -> {
            val device = strOrNull(root["deviceId"])
            val group = strOrNull(root["groupId"])
            if (device == null || !ID.matches(device) || group == null) throw WireException("bad hello")
            Hello(
                protocolVersion = intOrNull(root["protocolVersion"]) ?: throw WireException("bad hello"),
                deviceId = device,
                appVersion = strOrNull(root["appVersion"]) ?: "",
                groupId = group,
                vector = parseVector(root["vector"]),
                wallMillis = intOrNull(root["wallMillis"]) ?: throw WireException("bad hello"),
                maxChunk = intOrNull(root["maxChunk"]) ?: 0L,
            )
        }
        "ops" -> {
            val ops = root["ops"] as? JsonArray
            if (ops == null || ops.size !in 1..MAX_OPS_PER_MESSAGE || plaintext.size > MAX_OPS_PLAINTEXT) {
                throw WireException("bad ops size")
            }
            OpsMessage(ops.toList())
        }
        "done" -> Done(parseVector(root["vector"]))
        else -> throw WireException("unknown message $t")
    }
}
