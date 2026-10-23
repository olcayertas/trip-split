package com.olcayertas.tripsplit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.relay.HttpRelayApi
import com.olcayertas.tripsplit.core.relay.RelayCoordinator
import com.olcayertas.tripsplit.core.relay.RelaySync
import com.olcayertas.tripsplit.core.relay.isRelayConfigured
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The per-device switch "Sync over the internet". On by default (PLAN §9.4). */
class RelaySettings(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    private companion object {
        const val KEY = "enabled"
    }
}

/**
 * Runs the relay sync (SPEC §10.4.4): when a group screen opens, after a save ([requestSync]), and every
 * [intervalMs] while the app is in the foreground. The activity calls [setForeground].
 */
class RelayController(
    context: Context,
    val baseUrl: String,
    val settings: RelaySettings,
    private val intervalMs: Long = 60_000,
    private val device: suspend () -> Device,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    /** False while the build has no relay URL. The switch then says "Not set up yet". */
    val configured: Boolean = isRelayConfigured(baseUrl)

    private fun online(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Ask for a sync now. Many calls in a short time make one sync. */
    fun requestSync() {
        triggers.trySend(Unit)
    }

    @Synchronized
    fun setForeground(on: Boolean) {
        if (!on) {
            loop?.cancel()
            loop = null
            return
        }
        if (loop != null || !configured) return
        loop = scope.launch {
            val d = device()
            val coordinator = RelayCoordinator(
                RelaySync(d, HttpRelayApi(baseUrl)),
                enabled = { settings.enabled.value },
                online = ::online,
            )
            while (isActive) {
                coordinator.syncAll(d.store.groups().map { it.groupId })
                withTimeoutOrNull(intervalMs) { triggers.receive() }
            }
        }
    }
}
