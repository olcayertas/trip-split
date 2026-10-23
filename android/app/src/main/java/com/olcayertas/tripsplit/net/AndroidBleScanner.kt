package com.olcayertas.tripsplit.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.olcayertas.tripsplit.core.ble.BleScanner
import com.olcayertas.tripsplit.core.ble.BleUuids
import java.util.UUID

/** One long scan for the TripSplit service UUID. Android allows 5 scan starts in 30 s, so it never restarts. */
@SuppressLint("MissingPermission")
class AndroidBleScanner(private val adapter: BluetoothAdapter) : BleScanner {
    private var callback: ScanCallback? = null

    override fun start(onFound: (String) -> Unit): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(result.device.address)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onFound(it.device.address) }
            }
        }
        callback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BleUuids.SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            adapter.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }
}
