package com.olcayertas.tripsplit.net

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Why BLE cannot run now. The Nearby screen turns each one into a message. */
enum class BleStatus { READY, NO_PERMISSION, BLUETOOTH_OFF, LOCATION_OFF, UNSUPPORTED }

object BlePermissions {
    /** Android 12 and later: scan, connect, advertise. Android 10 and 11: location (SPEC §10.2.3 needs scans). */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context): List<String> =
        required().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun status(context: Context): BleStatus {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BleStatus.UNSUPPORTED
        val adapter = adapter(context) ?: return BleStatus.UNSUPPORTED
        if (missing(context).isNotEmpty()) return BleStatus.NO_PERMISSION
        if (!adapter.isEnabled) return BleStatus.BLUETOOTH_OFF
        // Before Android 12 a BLE scan returns nothing while the location switch is off.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isLocationEnabled) return BleStatus.LOCATION_OFF
        }
        return BleStatus.READY
    }
}
