package com.olcayertas.tripsplit.core.lan

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.GroupKey
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.SessionTimeouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A peer that DNS-SD resolved: where to dial it and what it publishes. */
class DiscoveredPeer(val host: String, val port: Int, val txt: TxtRecord)

/** The platform part of discovery. On Android it is `NsdManager`. */
interface PeerDiscovery {
    /** Registers the service and starts browsing. [onPeer] may be called from any thread. */
    fun start(port: Int, txt: Map<String, String>, instance: String, onPeer: (DiscoveredPeer) -> Unit)

    fun stop()
}

enum class Transport { WIFI, BLUETOOTH }

data class PeerStatus(
    val deviceId: String,
    val host: String,
    val groupIds: List<String>,
    val viaFallback: Boolean,
    val syncing: Boolean,
    val lastOutcome: SessionOutcome?,
    val lastSyncAt: Long?,
    val transport: Transport = Transport.WIFI,
)

data class NearbyState(
    val running: Boolean = false,
    val port: Int? = null,
    val peers: List<PeerStatus> = emptyList(),
    val lastOutcome: SessionOutcome? = null,
    val clockSkew: Boolean = false,
)

/** The cooldown of SPEC §10.1.4: wait 30 s after a session with a peer, unless a new local op exists. */
class Cooldown(private val now: () -> Long, private val windowMs: Long = 30_000) {
    private val last = HashMap<String, Pair<Long, Long>>()

    fun allowed(key: String, localSeq: Long): Boolean {
        val l = last[key] ?: return true
        return now() - l.first >= windowMs || localSeq > l.second
    }

    fun record(key: String, localSeq: Long) {
        last[key] = now() to localSeq
    }
}

/**
 * Runs LAN sync while the Nearby screen is open (SPEC §10.1): listens, advertises, browses, dials peers
 * that share a group tag, and falls back to the gateway when no service shows up in 5 s.
 */
class LanDirector(
    private val device: Device,
    private val discovery: PeerDiscovery,
    private val gateway: () -> String?,
    private val scope: CoroutineScope,
    private val preferredPort: Int = LAN_PORT,
    private val timeouts: SessionTimeouts = SessionTimeouts.LAN,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 2_000,
    private val fallbackAfterMs: Long = 5_000,
    private val fallbackEveryMs: Long = 15_000,
    private val gatewayPort: Int = LAN_PORT,
    private val bindAddress: java.net.InetAddress? = null,
) {
    private val admission = SessionAdmission()
    private val connector = LanConnector(device, admission, timeouts)
    private val cooldown = Cooldown(now)
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    private val known = HashMap<String, DiscoveredPeer>()
    private val busy = HashSet<String>()
    private val lock = Any()
    private var listener: LanListener? = null
    private var loop: Job? = null

    fun start() {
        if (loop != null) return
        val l = LanListener(device, admission, preferredPort, timeouts, { outcome -> record(null, outcome) }, bindAddress)
        val port = l.start(scope)
        listener = l
        _state.update { it.copy(running = true, port = port) }
        loop = scope.launch {
            val groups = device.store.groups()
            discovery.start(port, encodeTxt(device.deviceId, groups), instanceName(device.deviceId)) { peer ->
                synchronized(lock) { known[peer.txt.deviceId] = peer }
            }
            val startedAt = now()
            var lastFallback = 0L
            while (true) {
                tick()
                val noPeer = synchronized(lock) { known.isEmpty() }
                if (noPeer && now() - startedAt >= fallbackAfterMs && now() - lastFallback >= fallbackEveryMs) {
                    lastFallback = now()
                    gateway()?.let { host -> scope.launch { fallback(host) } }
                }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        discovery.stop()
        listener?.stop()
        listener = null
        synchronized(lock) { known.clear(); busy.clear() }
        _state.update { NearbyState() }
    }

    private suspend fun tick() {
        val groups = device.store.groups()
        val peers = synchronized(lock) { known.values.toList() }
        for (peer in peers) {
            if (peer.txt.deviceId == device.deviceId) continue
            for (g in sharedGroups(peer.txt, groups)) {
                val key = peer.txt.deviceId + "|" + g.groupId
                val localSeq = device.store.vector(g.groupId)[device.deviceId] ?: 0L
                val go = synchronized(lock) { key !in busy && cooldown.allowed(key, localSeq) && busy.add(key) }
                if (go) scope.launch { dial(peer, g, key, localSeq) }
            }
        }
        publish(peers, groups)
    }

    private suspend fun dial(peer: DiscoveredPeer, g: GroupKey, key: String, localSeq: Long) {
        publishBusy(peer.txt.deviceId, true)
        val outcome = connector.connect(peer.host, peer.port, g.groupId)
        synchronized(lock) {
            busy.remove(key)
            cooldown.record(key, localSeq)
        }
        record(peer.txt.deviceId, outcome)
        publishBusy(peer.txt.deviceId, false)
    }

    /** Port 47831 ([gatewayPort]) on the default gateway. It tries each local group until one session starts. */
    private suspend fun fallback(host: String) {
        for (g in device.store.groups()) {
            val key = "gateway|" + g.groupId
            val seq = device.store.vector(g.groupId)[device.deviceId] ?: 0L
            if (!synchronized(lock) { cooldown.allowed(key, seq) && busy.add(key) }) continue
            val outcome = connector.connect(host, gatewayPort, g.groupId)
            synchronized(lock) { busy.remove(key) }
            if (outcome is SessionOutcome.Done) {
                synchronized(lock) { cooldown.record(key, seq) }
                record(outcome.result.peerDeviceId, outcome, host, viaFallback = true)
                return
            }
        }
    }

    private val outcomes = HashMap<String, Pair<SessionOutcome, Long>>()
    private val fallbackPeers = HashMap<String, String>()
    private val syncing = HashSet<String>()

    private fun record(peerId: String?, outcome: SessionOutcome, host: String? = null, viaFallback: Boolean = false) {
        val id = peerId ?: (outcome as? SessionOutcome.Done)?.result?.peerDeviceId
        synchronized(lock) {
            if (id != null) {
                outcomes[id] = outcome to now()
                if (viaFallback && host != null) fallbackPeers[id] = host
            }
        }
        val skew = (outcome as? SessionOutcome.Done)?.result?.clockSkew == true
        _state.update { it.copy(lastOutcome = outcome, clockSkew = it.clockSkew || skew) }
        scope.launch { publish(synchronized(lock) { known.values.toList() }, device.store.groups()) }
    }

    private fun publishBusy(peerId: String, on: Boolean) {
        synchronized(lock) { if (on) syncing.add(peerId) else syncing.remove(peerId) }
        scope.launch { publish(synchronized(lock) { known.values.toList() }, device.store.groups()) }
    }

    private fun publish(peers: List<DiscoveredPeer>, groups: List<GroupKey>) {
        val list = synchronized(lock) {
            val rows = peers.filter { it.txt.deviceId != device.deviceId }.map { p ->
                val o = outcomes[p.txt.deviceId]
                PeerStatus(
                    p.txt.deviceId, p.host, sharedGroups(p.txt, groups).map { it.groupId }, false,
                    p.txt.deviceId in syncing, o?.first, o?.second,
                )
            }
            val ids = rows.map { it.deviceId }.toSet()
            rows + fallbackPeers.filter { it.key !in ids }.map { (id, host) ->
                val o = outcomes[id]
                PeerStatus(id, host, emptyList(), true, id in syncing, o?.first, o?.second)
            }
        }
        _state.update { it.copy(peers = list) }
    }
}
