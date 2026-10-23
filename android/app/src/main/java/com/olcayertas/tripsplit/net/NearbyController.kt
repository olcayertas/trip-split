package com.olcayertas.tripsplit.net

import android.content.Context
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.ble.BleDirector
import com.olcayertas.tripsplit.core.lan.LanDirector
import com.olcayertas.tripsplit.core.lan.NearbyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs sync while the Nearby screen is open (PLAN §9.3): Wi-Fi LAN always, Bluetooth when the permissions and the
 * adapter allow it. The screen calls [start] on resume and [stop] on pause. Each start makes new directors, so the
 * advertised tags match the groups the device holds now.
 */
class NearbyController(private val context: Context, private val device: () -> Device) {
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state.asStateFlow()
    private val _ble = MutableStateFlow(BlePermissions.status(context))

    /** Why Bluetooth is not running, or [BleStatus.READY]. */
    val bleStatus: StateFlow<BleStatus> = _ble.asStateFlow()
    private var scope: CoroutineScope? = null
    private var lan: LanDirector? = null
    private var ble: BleDirector? = null

    @Synchronized
    fun start() {
        if (lan != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val d = device()
        val l = LanDirector(d, NsdDiscovery(context), { defaultGateway(context) }, s)
        lan = l
        val status = BlePermissions.status(context)
        _ble.value = status
        val adapter = BlePermissions.adapter(context)
        val b = if (status == BleStatus.READY && adapter != null) {
            BleDirector(d, AndroidBleScanner(adapter), AndroidBleHost(context, adapter), { AndroidGattCentral(context, adapter) }, s)
        } else {
            null
        }
        ble = b
        s.launch {
            val bleFlow = b?.state ?: MutableStateFlow(NearbyState())
            combine(l.state, bleFlow) { a, c ->
                a.copy(
                    peers = a.peers + c.peers,
                    lastOutcome = c.lastOutcome ?: a.lastOutcome,
                    clockSkew = a.clockSkew || c.clockSkew,
                )
            }.collect { _state.value = it }
        }
        l.start()
        b?.start()
    }

    /** The user granted the permissions or switched Bluetooth on: start again. */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    fun stop() {
        ble?.stop()
        lan?.stop()
        scope?.cancel()
        ble = null
        lan = null
        scope = null
        _state.value = NearbyState()
    }
}
