package com.olcayertas.tripsplit.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.olcayertas.tripsplit.core.ble.BleHost
import com.olcayertas.tripsplit.core.ble.BlePeripheral
import com.olcayertas.tripsplit.core.ble.BlePeripheralConnection
import com.olcayertas.tripsplit.core.ble.BleServerSession
import com.olcayertas.tripsplit.core.ble.BleUuids
import com.olcayertas.tripsplit.core.ble.chunkSizeForMtu
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * The GATT server and the advertisement (SPEC §10.2.1). The advertisement holds the service UUID and nothing else.
 * `rx` takes write with response only. `tx` notifies. `info` is static while the screen is open.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class AndroidBleHost(private val context: Context, private val adapter: BluetoothAdapter) : BleHost {
    private class Conn(val device: BluetoothDevice) {
        @Volatile var mtu = 23
        @Volatile var notifyOn = false
        @Volatile var sent: CompletableDeferred<Int>? = null
        var session: BleServerSession? = null
    }

    private var server: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var infoBytes = ByteArray(0)
    private var peripheral: BlePeripheral? = null
    private val conns = ConcurrentHashMap<String, Conn>()
    private val notifyLock = Any()
    private lateinit var tx: BluetoothGattCharacteristic

    override fun start(info: ByteArray, peripheral: BlePeripheral): Boolean {
        if (!adapter.isMultipleAdvertisementSupported) return false
        val adv = adapter.bluetoothLeAdvertiser ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        infoBytes = info
        this.peripheral = peripheral
        val srv = manager.openGattServer(context, callback) ?: return false
        server = srv
        val service = BluetoothGattService(UUID.fromString(BleUuids.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        // rx: write with response only. A peripheral must not offer write without response.
        service.addCharacteristic(
            BluetoothGattCharacteristic(UUID.fromString(BleUuids.RX), BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        tx = BluetoothGattCharacteristic(UUID.fromString(BleUuids.TX), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).also {
            it.addDescriptor(
                BluetoothGattDescriptor(UUID.fromString(BleUuids.CCCD), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
            )
        }
        service.addCharacteristic(tx)
        service.addCharacteristic(
            BluetoothGattCharacteristic(UUID.fromString(BleUuids.INFO), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ),
        )
        srv.addService(service)
        advertiser = adv
        return true
    }

    private fun startAdvertising() {
        val adv = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleUuids.SERVICE)))
            .build()
        val cb = object : AdvertiseCallback() {}
        advertiseCallback = cb
        adv.startAdvertising(settings, data, cb)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                val c = Conn(device)
                conns[device.address] = c
                c.session = peripheral!!.accept(PeripheralConn(c))
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                conns.remove(device.address)?.let { it.session?.onDisconnected(); it.sent?.completeExceptionally(IOException("disconnected")) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            conns[device.address]?.mtu = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val srv = server ?: return
            if (c.uuid == UUID.fromString(BleUuids.INFO) && offset <= infoBytes.size) {
                srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, infoBytes.copyOfRange(offset, infoBytes.size))
            } else {
                srv.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val srv = server ?: return
            val conn = conns[device.address]
            val ok = c.uuid == UUID.fromString(BleUuids.RX) && !preparedWrite && offset == 0 && conn != null
            if (ok) conn!!.session?.onChunk(value)
            if (responseNeeded) {
                srv.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val srv = server ?: return
            val conn = conns[device.address]
            if (d.uuid == UUID.fromString(BleUuids.CCCD) && conn != null) {
                conn.notifyOn = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            conns[device.address]?.sent?.complete(status)
        }
    }

    private inner class PeripheralConn(private val c: Conn) : BlePeripheralConnection {
        override val txLimit: Int get() = chunkSizeForMtu(c.mtu)

        override suspend fun send(chunk: ByteArray) {
            // A peripheral must not notify before the central enabled notifications (SPEC §10.2.1).
            if (!c.notifyOn) throw IOException("notifications are off")
            val srv = server ?: throw IOException("server closed")
            val done = CompletableDeferred<Int>()
            c.sent = done
            val started = synchronized(notifyLock) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    srv.notifyCharacteristicChanged(c.device, tx, false, chunk) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    tx.value = chunk
                    srv.notifyCharacteristicChanged(c.device, tx, false)
                }
            }
            if (!started) throw IOException("notify not started")
            // The next notification waits for onNotificationSent (SPEC §10.2.6).
            if (done.await() != BluetoothGatt.GATT_SUCCESS) throw IOException("notify failed")
        }

        override fun disconnect() {
            server?.cancelConnection(c.device)
        }
    }

    override fun stop() {
        advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        conns.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        conns.clear()
        runCatching { server?.close() }
        server = null
        advertiser = null
    }
}
