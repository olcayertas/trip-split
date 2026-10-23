package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.GroupKey
import com.olcayertas.tripsplit.core.LinkClosedException
import com.olcayertas.tripsplit.core.Role
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.SessionException
import com.olcayertas.tripsplit.core.SessionTimeouts
import com.olcayertas.tripsplit.core.SyncSession
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** What the platform learned in steps 2 to 5 of SPEC §10.2.3. */
class CentralSetup(val info: ByteArray, val mtu: Int)

/** One GATT connection as central. The platform implements it. A new instance serves one attempt. */
interface BleCentralConnection : ChunkPipe {
    /** The platform calls it for each notification on `tx`. Set after [setup]. */
    var chunkSink: (ByteArray) -> Unit
    var disconnectSink: () -> Unit

    /** Step 1. */
    suspend fun connect(address: String)

    /** Steps 2 to 5: request MTU 512, discover the service, read `info`, enable notifications on `tx`. */
    suspend fun setup(): CentralSetup
}

const val BLE_CONNECT_TIMEOUT_MS = 10_000L
const val BLE_SETUP_TIMEOUT_MS = 10_000L

sealed interface CentralResult {
    data class Synced(val outcomes: List<SessionOutcome>, val peerDeviceId: String) : CentralResult
    data class Skipped(val decision: CentralDecision, val peerDeviceId: String?) : CentralResult
    data class Failed(val reason: String) : CentralResult
}

/** Runs the central side for one scan hit (SPEC §10.2.3). One group for each connection. */
class BleCentralRunner(
    private val device: Device,
    private val admission: SessionAdmission,
    private val scope: CoroutineScope,
    private val newConnection: () -> BleCentralConnection,
    private val timeouts: SessionTimeouts = SessionTimeouts.BLE,
    private val unfinishedMessageMs: Long = 15_000,
) {
    suspend fun run(address: String, forceCentral: Boolean = false): CentralResult {
        val localGroups = device.store.groups()
        val outcomes = ArrayList<SessionOutcome>()
        var pending: List<GroupKey>? = null
        var peerId: String? = null
        var index = 0
        while (true) {
            val attempt = attempt(address, localGroups, forceCentral, pending?.get(index))
            when (attempt) {
                is Attempt.Fail -> return if (outcomes.isEmpty()) CentralResult.Failed(attempt.reason) else CentralResult.Synced(outcomes, peerId ?: "")
                is Attempt.Skip -> return CentralResult.Skipped(attempt.decision, attempt.peerId)
                is Attempt.Done -> {
                    outcomes.add(attempt.outcome)
                    peerId = attempt.peerId
                    pending = attempt.groups
                    index++
                    if (index >= attempt.groups.size) return CentralResult.Synced(outcomes, peerId)
                }
            }
        }
    }

    private sealed interface Attempt {
        data class Fail(val reason: String) : Attempt
        data class Skip(val decision: CentralDecision, val peerId: String?) : Attempt
        data class Done(val outcome: SessionOutcome, val peerId: String, val groups: List<GroupKey>) : Attempt
    }

    private suspend fun attempt(address: String, localGroups: List<GroupKey>, force: Boolean, only: GroupKey?): Attempt {
        val conn = newConnection()
        var link: BleLink? = null
        try {
            try {
                withTimeout(BLE_CONNECT_TIMEOUT_MS) { conn.connect(address) }
            } catch (e: TimeoutCancellationException) {
                return Attempt.Fail("connect timeout")
            }
            val setup = try {
                withTimeout(BLE_SETUP_TIMEOUT_MS) { conn.setup() }
            } catch (e: TimeoutCancellationException) {
                return Attempt.Fail("setup timeout")
            }
            val info = parseInfo(setup.info) ?: return Attempt.Fail("bad info")
            val decision = centralDecision(device.deviceId, localGroups, info, force)
            val groups = (decision as? CentralDecision.Go)?.groups ?: return Attempt.Skip(decision, info.deviceId)
            val group = only ?: groups.first()
            val ownLimit = chunkSizeForMtu(setup.mtu)
            val l = BleLink(conn, receiveLimit = ownLimit, scope = scope, isPeripheral = false,
                initialSendChunk = minOf(ownLimit, info.maxChunk), unfinishedMessageMs = unfinishedMessageMs)
            link = l
            conn.chunkSink = l::onChunk
            conn.disconnectSink = l::onDisconnected
            val outcome = try {
                SessionOutcome.Done(
                    SyncSession(device, l, Role.INITIATOR, group.groupId, timeouts, maxChunk = ownLimit.toLong(), admission = admission).run(),
                )
            } catch (e: SessionException) {
                SessionOutcome.Failed(e.reason, info.deviceId)
            } catch (e: LinkClosedException) {
                SessionOutcome.Failed(e.message ?: "closed", info.deviceId)
            }
            return Attempt.Done(outcome, info.deviceId, groups)
        } catch (e: LinkClosedException) {
            return Attempt.Fail(e.message ?: "closed")
        } finally {
            link?.close() ?: conn.disconnect()
        }
    }
}
