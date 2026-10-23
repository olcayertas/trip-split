@file:OptIn(ExperimentalMaterial3Api::class)

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.olcayertas.tripsplit.R
import com.olcayertas.tripsplit.core.lan.PeerStatus
import com.olcayertas.tripsplit.core.lan.Transport
import com.olcayertas.tripsplit.net.BlePermissions
import com.olcayertas.tripsplit.net.BleStatus
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import com.olcayertas.tripsplit.net.NearbyController
import java.text.DateFormat
import java.util.Date

/**
 * The Nearby screen (PLAN §11.9). Sync runs only while it is in the foreground, and the display stays on
 * (PLAN §9.3).
 */
@Composable
fun NearbyScreen(controller: NearbyController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.start()
                Lifecycle.Event.ON_PAUSE -> controller.stop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.stop()
            view.keepScreenOn = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nearby_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.nearby_searching), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.nearby_help), style = MaterialTheme.typography.bodyMedium)
                        state.port?.let { Text(stringResource(R.string.nearby_port, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { BleCard(controller) }
            if (state.clockSkew) {
                item {
                    Text(stringResource(R.string.nearby_clock_skew), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.peers.isEmpty()) {
                item {
                    Text(stringResource(R.string.nearby_no_peers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.peers, key = { it.deviceId }) { PeerRow(it) }
        }
    }
}

@Composable
private fun PeerRow(p: PeerStatus) {
    val df = DateFormat.getTimeInstance(DateFormat.SHORT)
    ListItem(
        leadingContent = {
            if (p.transport == Transport.WIFI) Icon(Icons.Filled.Wifi, stringResource(R.string.nearby_transport_wifi))
            else Icon(Icons.Filled.Bluetooth, stringResource(R.string.nearby_transport_bluetooth))
        },
        headlineContent = { Text("TripSplit-" + p.deviceId.take(8)) },
        supportingContent = {
            val text = when {
                p.syncing -> stringResource(R.string.nearby_syncing)
                p.lastOutcome is SessionOutcome.Done -> {
                    val r = (p.lastOutcome as SessionOutcome.Done).result
                    stringResource(R.string.nearby_result_ok, r.sentOps, r.receivedOps) + p.lastSyncAt?.let { " · " + df.format(Date(it)) }.orEmpty()
                }
                p.lastOutcome is SessionOutcome.Failed -> stringResource(R.string.nearby_result_failed, (p.lastOutcome as SessionOutcome.Failed).reason)
                else -> stringResource(R.string.nearby_waiting)
            }
            Text(text)
        },
        trailingContent = { if (p.syncing) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) },
    )
}

/** Tells the user what Bluetooth needs: the permissions, the adapter, and on Android 11 and older the location switch. */
@Composable
private fun BleCard(controller: NearbyController) {
    val status by controller.bleStatus.collectAsState()
    if (status == BleStatus.READY) return
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { controller.restart() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = when (status) {
                BleStatus.NO_PERMISSION -> R.string.ble_need_permission
                BleStatus.BLUETOOTH_OFF -> R.string.ble_off
                BleStatus.LOCATION_OFF -> R.string.ble_location_off
                else -> R.string.ble_unsupported
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            if (status == BleStatus.NO_PERMISSION) {
                androidx.compose.material3.Button({ launcher.launch(BlePermissions.required()) }) { Text(stringResource(R.string.ble_allow)) }
            } else if (status != BleStatus.UNSUPPORTED) {
                androidx.compose.material3.OutlinedButton({ controller.restart() }) { Text(stringResource(R.string.ble_try_again)) }
            }
        }
    }
}
