package com.olcayertas.tripsplit.core.lan

import com.olcayertas.tripsplit.core.GroupKey
import com.olcayertas.tripsplit.core.toHex

const val LAN_PORT = 47831
const val LAN_SERVICE_TYPE = "_tripsplit._tcp"
const val MAX_TXT_TAGS = 8
const val MAX_TXT_VALUE_BYTES = 199

private val TAG_HEX = Regex("[0-9a-f]{24}")

/** The DNS-SD TXT record of SPEC §10.1.1. */
class TxtRecord(val version: Int, val deviceId: String, val tags: List<String>)

fun instanceName(deviceId: String): String = "TripSplit-" + deviceId.take(8)

/**
 * The TXT keys to publish. [groups] must be ordered by last use, newest first: a device with more than 8
 * groups publishes the 8 it used last. The `t` value is at most 199 bytes (8 tags of 24 hex digits and
 * 7 commas make 199).
 */
fun encodeTxt(deviceId: String, groups: List<GroupKey>): Map<String, String> {
    val tags = groups.take(MAX_TXT_TAGS).map { toHex(it.tag) }
    return mapOf("v" to "1", "d" to deviceId, "t" to tags.joinToString(","))
}

/** Returns null when `d` or `v` is missing or bad. Unknown keys are ignored. A bad tag is dropped. */
fun parseTxt(txt: Map<String, ByteArray?>): TxtRecord? {
    fun text(key: String) = txt[key]?.toString(Charsets.UTF_8)
    val version = text("v")?.toIntOrNull() ?: return null
    val device = text("d")?.takeIf { it.isNotEmpty() } ?: return null
    val tags = (text("t") ?: "").split(',').filter { TAG_HEX.matches(it) }.distinct().take(MAX_TXT_TAGS)
    return TxtRecord(version, device, tags)
}

/** The local groups whose tag the peer publishes (SPEC §10.1.2 step 2). */
fun sharedGroups(peer: TxtRecord, local: List<GroupKey>): List<GroupKey> =
    local.filter { toHex(it.tag) in peer.tags }
