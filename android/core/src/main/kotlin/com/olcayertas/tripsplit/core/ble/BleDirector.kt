package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.lan.NearbyState
import com.olcayertas.tripsplit.core.lan.PeerStatus
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import com.olcayertas.tripsplit.core.lan.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Scans for the TripSplit service UUID. One long scan: Android allows 5 scan starts in 30 s (PLAN §9.3). */
interface BleScanner {
    /** False when the hardware or the permissions do not allow a scan. */
    fun start(onFound: (address: String) -> Unit): Boolean

    fun stop()
}

/** The GATT server and the advertisement. */
interface BleHost {
    /** Serves the GATT service and advertises the service UUID. False when this hardware cannot advertise. */
    fun start(info: ByteArray, peripheral: BlePeripheral): Boolean

    fun stop()
}

/**
 * Runs BLE sync while the Nearby screen is open (SPEC §10.2.3): serve and advertise when the hardware
 * allows, scan, and dial scan hits one at a time.
 */
class BleDirector(
    private val device: Device,
    private val scanner: BleScanner,
    private val host: BleHost,
    private val centralFactory: () -> BleCentralConnection,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
    /** Wait after a session with a peer (SPEC §10.2.7). */
    private val syncedWaitMs: Long = 30_000,
    private val skippedWaitMs: Long = 60_000,
    private val failedWaitMs: Long = 10_000,
    private val yieldWaitMs: Long = YIELD_WAIT_MS,
) {
    private class Seen(var nextAllowed: Long = 0, var force: Boolean = false, var peer: String? = null)

    private val admission = SessionAdmission()
    private val seen = HashMap<String, Seen>()
    private val lock = Any()
    private val outcomes = HashMap<String, Pair<SessionOutcome, Long>>()
    private val busy = HashSet<String>()
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state.asStateFlow()
    private var loop: Job? = null
    private var canScan = false
    private var canServe = false

    /** Hardware or permission limits, for the Nearby screen. */
    val canScanNow: Boolean get() = canScan
    val canServeNow: Boolean get() = canServe

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            val peripheral = BlePeripheral(device, admission, scope) { outcome -> record(null, outcome) }
            val groups = device.store.groups()
            // The scan comes first: the roles in `info` must say whether this device can be central.
            canScan = scanner.start { address -> synchronized(lock) { seen.getOrPut(address) { Seen() } } }
            canServe = host.start(buildInfo(device.deviceId, groups, canCentral = canScan), peripheral)
            _state.update { it.copy(running = true) }
            while (true) {
                step()
                delay(tickMs)
            }
        }
    }

    private suspend fun step() {
        val next = synchronized(lock) {
            if (busy.isNotEmpty()) return
            val t = now()
            seen.entries.filter { it.value.nextAllowed <= t }.minByOrNull { it.value.nextAllowed }
                ?.also { busy.add(it.key) }
        } ?: return
        val address = next.key
        val entry = next.value
        val runner = BleCentralRunner(device, admission, scope, centralFactory)
        publish(syncingAddress = address)
        val result = runner.run(address, entry.force)
        val t = now()
        synchronized(lock) {
            busy.remove(address)
            entry.force = false
            when (result) {
                is CentralResult.Synced -> {
                    entry.peer = result.peerDeviceId
                    entry.nextAllowed = t + syncedWaitMs
                    result.outcomes.lastOrNull()?.let { outcomes[result.peerDeviceId] = it to t }
                }
                is CentralResult.Skipped -> {
                    entry.peer = result.peerDeviceId
                    if (result.decision == CentralDecision.YieldToPeer) {
                        // The peer is central. If no session from it starts in 15 s, connect again and act as central.
                        entry.nextAllowed = t + yieldWaitMs
                        entry.force = true
                    } else {
                        entry.nextAllowed = t + skippedWaitMs
                    }
                }
                is CentralResult.Failed -> {
                    entry.nextAllowed = t + failedWaitMs
                    outcomes[address] = SessionOutcome.Failed(result.reason, null) to t
                }
            }
        }
        (result as? CentralResult.Synced)?.outcomes?.lastOrNull()?.let { o -> _state.update { it.copy(lastOutcome = o) } }
        publish()
    }

    /** A session from a peer that dialled us counts as the arrival of the peer: it ends the 15 s wait. */
    private fun record(peerId: String?, outcome: SessionOutcome) {
        val id = peerId ?: (outcome as? SessionOutcome.Done)?.result?.peerDeviceId
        val t = now()
        synchronized(lock) {
            if (id != null) {
                outcomes[id] = outcome to t
                seen.values.filter { it.peer == id }.forEach { it.force = false; it.nextAllowed = t + syncedWaitMs }
            }
        }
        val skew = (outcome as? SessionOutcome.Done)?.result?.clockSkew == true
        _state.update { it.copy(lastOutcome = outcome, clockSkew = it.clockSkew || skew) }
        publish()
    }

    private fun publish(syncingAddress: String? = null) {
        val rows = synchronized(lock) {
            val byPeer = HashSet<String>()
            val list = seen.entries.mapNotNull { (address, s) ->
                val peer = s.peer ?: return@mapNotNull null
                byPeer.add(peer)
                val o = outcomes[peer]
                PeerStatus(peer, address, emptyList(), false, address == syncingAddress || address in busy, o?.first, o?.second, Transport.BLUETOOTH)
            }
            list + outcomes.filterKeys { it !in byPeer && it.length == 36 }.map { (peer, o) ->
                PeerStatus(peer, "", emptyList(), false, false, o.first, o.second, Transport.BLUETOOTH)
            }
        }
        _state.update { it.copy(peers = rows) }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        scanner.stop()
        host.stop()
        synchronized(lock) { seen.clear(); busy.clear(); outcomes.clear() }
        _state.update { NearbyState() }
    }
}
