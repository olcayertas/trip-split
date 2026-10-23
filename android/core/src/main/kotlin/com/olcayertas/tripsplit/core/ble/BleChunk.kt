package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.MAX_FRAME
import com.olcayertas.tripsplit.core.PROTOCOL_VERSION
import com.olcayertas.tripsplit.core.canonicalBytes
import com.olcayertas.tripsplit.core.intOrNull
import com.olcayertas.tripsplit.core.strOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The frozen UUIDs of SPEC §10.2.1. Compare without regard to case. */
object BleUuids {
    const val SERVICE = "717ed7c0-0ed4-4251-a5b1-606fa1771f42"
    const val RX = "33f56749-2c8f-4bb9-9d1b-3b27fa731a8f"
    const val TX = "bd1e5af2-f0f9-49f7-9128-63c490a78986"
    const val INFO = "c9698bc0-ddbd-4f16-8bd3-9c7d34135fba"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

const val CHUNK_HEADER = 3
const val MIN_CHUNK = 20
const val MAX_CHUNK = 512
const val FLAG_FIRST = 0x01
const val FLAG_LAST = 0x02
const val FLAG_ABORT = 0x04
const val MAX_BLE_MESSAGE = MAX_FRAME + 4

/** SPEC §10.2.5: `max(20, min(512, ATT MTU - 3))`. */
fun chunkSizeForMtu(mtu: Int): Int = maxOf(MIN_CHUNK, minOf(MAX_CHUNK, mtu - 3))

/** Splits one message into chunks. `seq` runs from [firstSeq] and wraps at 65536. */
fun chunkMessage(message: ByteArray, chunkSize: Int, firstSeq: Int = 0): List<ByteArray> {
    require(message.isNotEmpty()) { "empty message" }
    require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "bad chunk size" }
    val room = chunkSize - CHUNK_HEADER
    val out = ArrayList<ByteArray>()
    var start = 0
    while (start < message.size) {
        val end = minOf(start + room, message.size)
        var flags = 0
        if (start == 0) flags = flags or FLAG_FIRST
        if (end == message.size) flags = flags or FLAG_LAST
        val seq = (firstSeq + out.size) and 0xFFFF
        val chunk = ByteArray(CHUNK_HEADER + end - start)
        chunk[0] = flags.toByte()
        chunk[1] = (seq shr 8).toByte()
        chunk[2] = seq.toByte()
        System.arraycopy(message, start, chunk, CHUNK_HEADER, end - start)
        out.add(chunk)
        start = end
    }
    return out
}

fun abortChunk(seq: Int): ByteArray = byteArrayOf(FLAG_ABORT.toByte(), (seq shr 8).toByte(), seq.toByte())

/** A receive error of SPEC §10.2.4. [code] is the name the vectors use. The link closes. */
class ReassemblyException(val code: String) : Exception(code)

/** The receiver side of SPEC §10.2.4. One per direction and connection. Not thread safe. */
class Reassembler(private val limit: Int) {
    private var expected = 0
    private var buffer = java.io.ByteArrayOutputStream()

    /** True while a message is open (a chunk with FIRST and no LAST arrived). */
    val partial: Boolean get() = buffer.size() > 0

    /** Returns a complete message, or null. Throws [ReassemblyException]. */
    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.size < CHUNK_HEADER) throw ReassemblyException("short_chunk")
        if (chunk.size > limit) throw ReassemblyException("too_large")
        val flags = chunk[0].toInt() and 0xFF
        val seq = ((chunk[1].toInt() and 0xFF) shl 8) or (chunk[2].toInt() and 0xFF)
        val payloadSize = chunk.size - CHUNK_HEADER
        if (flags and 0xF8 != 0 || (flags and FLAG_ABORT != 0 && (flags != FLAG_ABORT || payloadSize > 0))) {
            throw ReassemblyException("bad_flags")
        }
        if (seq != expected) throw ReassemblyException("bad_seq")
        if (flags and FLAG_ABORT != 0) throw ReassemblyException("abort")
        if (payloadSize == 0) throw ReassemblyException("empty_chunk")
        val first = flags and FLAG_FIRST != 0
        if (first && buffer.size() > 0) throw ReassemblyException("unexpected_first")
        if (!first && buffer.size() == 0) throw ReassemblyException("unexpected_continuation")
        if (buffer.size() + payloadSize > MAX_BLE_MESSAGE) throw ReassemblyException("message_too_big")
        buffer.write(chunk, CHUNK_HEADER, payloadSize)
        expected = (seq + 1) and 0xFFFF
        if (flags and FLAG_LAST != 0) {
            val message = buffer.toByteArray()
            buffer = java.io.ByteArrayOutputStream()
            return message
        }
        return null
    }
}

/** The value of the `info` characteristic (SPEC §10.2.2): canonical JSON, at most 512 bytes. */
class BleInfo(
    val protocolVersion: Long,
    val deviceId: String,
    val maxChunk: Int,
    val roles: Set<String>,
    val tags: List<String>,
)

private val TAG_HEX = Regex("[0-9a-f]{24}")

fun infoValue(deviceId: String, maxChunk: Int, roles: Set<String>, tags: List<String>): ByteArray {
    val bytes = canonicalBytes(
        buildJsonObject {
            put("deviceId", deviceId)
            put("maxChunk", maxChunk)
            put("protocolVersion", PROTOCOL_VERSION)
            put("roles", JsonArray(roles.sorted().map { JsonPrimitive(it) }))
            put("tags", JsonArray(tags.take(8).map { JsonPrimitive(it) }))
        },
    )
    check(bytes.size <= 512)
    return bytes
}

/** Returns null when the value cannot be parsed. Unknown keys are ignored. The reader then disconnects. */
fun parseInfo(bytes: ByteArray): BleInfo? {
    val root = try {
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    val version = intOrNull(root["protocolVersion"]) ?: return null
    val device = strOrNull(root["deviceId"]) ?: return null
    val maxChunk = intOrNull(root["maxChunk"])?.toInt() ?: return null
    if (maxChunk !in MIN_CHUNK..MAX_CHUNK) return null
    val roles = (root["roles"] as? JsonArray)?.mapNotNull { strOrNull(it) }?.toSet() ?: return null
    val tags = (root["tags"] as? JsonArray)?.mapNotNull { strOrNull(it) }?.filter { TAG_HEX.matches(it) }?.take(8) ?: return null
    return BleInfo(version, device, maxChunk, roles, tags)
}
