package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.GroupKey
import com.olcayertas.tripsplit.core.byteCompare
import com.olcayertas.tripsplit.core.toHex

/** What the central does after it read `info` (SPEC §10.2.3 step 6). */
sealed interface CentralDecision {
    /** Continue with a session for each of these groups (one group for each connection). */
    data class Go(val groups: List<GroupKey>) : CentralDecision
    data object NoSharedTag : CentralDecision
    data object BadVersion : CentralDecision

    /** The peer has the higher deviceId and can be central. Disconnect and wait up to 15 s for it. */
    data object YieldToPeer : CentralDecision
}

const val YIELD_WAIT_MS = 15_000L

/**
 * SPEC §10.2.3. When both devices can be central, the lower deviceId (byte order) is central. With
 * [forceCentral], which is the retry after the 15 s wait, this device acts as central anyway.
 */
fun centralDecision(
    localDeviceId: String,
    localGroups: List<GroupKey>,
    info: BleInfo,
    forceCentral: Boolean = false,
): CentralDecision {
    if (info.protocolVersion < 1) return CentralDecision.BadVersion
    val shared = localGroups.filter { toHex(it.tag) in info.tags }
    if (shared.isEmpty()) return CentralDecision.NoSharedTag
    if (!forceCentral && "central" in info.roles && byteCompare(localDeviceId, info.deviceId) > 0) {
        return CentralDecision.YieldToPeer
    }
    return CentralDecision.Go(shared)
}

/** The `info` value this device serves. [canCentral] is true when the hardware can scan and connect. */
suspend fun buildInfo(deviceId: String, groups: List<GroupKey>, canCentral: Boolean, maxChunk: Int = MAX_CHUNK): ByteArray =
    infoValue(
        deviceId, maxChunk,
        if (canCentral) setOf("central", "peripheral") else setOf("peripheral"),
        groups.take(8).map { toHex(it.tag) },
    )
