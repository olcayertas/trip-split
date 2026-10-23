package com.olcayertas.tripsplit.net

import android.content.Context
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.lan.LanDirector
import com.olcayertas.tripsplit.core.lan.NearbyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs sync while the Nearby screen is open (PLAN §9.3). The screen calls [start] on resume and [stop] on
 * pause. Each start makes a new director, so the advertised tags match the groups the device holds now.
 */
class NearbyController(private val context: Context, private val device: () -> Device) {
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state.asStateFlow()
    private var scope: CoroutineScope? = null
    private var director: LanDirector? = null
    private var forward: Job? = null

    @Synchronized
    fun start() {
        if (director != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val d = LanDirector(device(), NsdDiscovery(context), { defaultGateway(context) }, s)
        scope = s
        director = d
        forward = s.launch { d.state.collect { _state.value = it } }
        d.start()
    }

    @Synchronized
    fun stop() {
        director?.stop()
        scope?.cancel()
        director = null
        scope = null
        forward = null
        _state.value = NearbyState()
    }
}
