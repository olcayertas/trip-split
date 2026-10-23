package com.olcayertas.tripsplit.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.olcayertas.tripsplit.core.LinkClosedException
import com.olcayertas.tripsplit.core.ble.BleCentralConnection
import com.olcayertas.tripsplit.core.ble.BleUuids
import com.olcayertas.tripsplit.core.ble.CentralSetup
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * One GATT connection as central (SPEC §10.2.3). Every GATT call waits for its callback, so calls never
 * overlap. A new instance serves one attempt.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class AndroidGattCentral(private val context: Context, private val adapter: BluetoothAdapter) : BleCentralConnection {
    override var chunkSink: (ByteArray) -> Unit = {}
    override var disconnectSink: () -> Unit = {}

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var infoChar: BluetoothGattCharacteristic? = null
    private val connected = CompletableDeferred<Unit>()
    private var mtu = 23
    private var pending: CompletableDeferred<Any?>? = null
    private val lock = Any()

    private fun <T> expect(): CompletableDeferred<T> {
        @Suppress("UNCHECKED_CAST")
        val d = CompletableDeferred<Any?>() as CompletableDeferred<T>
        synchronized(lock) { @Suppress("UNCHECKED_CAST") run { pending = d as CompletableDeferred<Any?> } }
        return d
    }

    private fun finish(value: Any?) {
        val d = synchronized(lock) { pending.also { pending = null } }
        d?.complete(value)
    }

    private fun failAll(e: Throwable) {
        connected.completeExceptionally(e)
        val d = synchronized(lock) { pending.also { pending = null } }
        d?.completeExceptionally(e)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                failAll(LinkClosedException("disconnected (status $status)"))
                disconnectSink()
                g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            finish(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            finish(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("API 32 and earlier")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            finish(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            chunkSink(value)
        }

        @Deprecated("API 32 and earlier")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            chunkSink(c.value)
        }
    }

    override suspend fun connect(address: String) {
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        connected.await()
    }

    override suspend fun setup(): CentralSetup {
        val g = gatt ?: throw LinkClosedException("not connected")
        // Step 2: MTU 512. The result is the ATT MTU, 23 when the peer refuses.
        val mtuD = expect<Int>()
        if (!g.requestMtu(512)) throw LinkClosedException("mtu request failed")
        val negotiated = mtuD.await()
        // Step 3: discover the service and its three characteristics.
        val svcD = expect<Int>()
        if (!g.discoverServices()) throw LinkClosedException("discovery failed")
        if (svcD.await() != BluetoothGatt.GATT_SUCCESS) throw LinkClosedException("discovery failed")
        val service = g.getService(UUID.fromString(BleUuids.SERVICE)) ?: throw LinkClosedException("no TripSplit service")
        rx = service.getCharacteristic(UUID.fromString(BleUuids.RX))
        tx = service.getCharacteristic(UUID.fromString(BleUuids.TX))
        infoChar = service.getCharacteristic(UUID.fromString(BleUuids.INFO))
        val rxC = rx ?: throw LinkClosedException("no rx")
        val txC = tx ?: throw LinkClosedException("no tx")
        val infoC = infoChar ?: throw LinkClosedException("no info")
        rxC.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // write with response, SPEC §10.2.1
        // Step 4: read `info`. A long read (ATT read blob) is the platform's job.
        val infoD = expect<ByteArray?>()
        if (!g.readCharacteristic(infoC)) throw LinkClosedException("info read failed")
        val info = infoD.await() ?: throw LinkClosedException("info read failed")
        // Step 5: enable notifications on `tx`.
        g.setCharacteristicNotification(txC, true)
        val cccd = txC.getDescriptor(UUID.fromString(BleUuids.CCCD)) ?: throw LinkClosedException("no CCCD")
        val descD = expect<Int>()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
        if (!started || descD.await() != BluetoothGatt.GATT_SUCCESS) throw LinkClosedException("enable notifications failed")
        return CentralSetup(info, negotiated)
    }

    override suspend fun send(chunk: ByteArray) {
        val g = gatt ?: throw LinkClosedException("not connected")
        val rxC = rx ?: throw LinkClosedException("not set up")
        val d = expect<Int>()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(rxC, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            rxC.value = chunk
            g.writeCharacteristic(rxC)
        }
        if (!started) throw IOException("write not started")
        if (d.await() != BluetoothGatt.GATT_SUCCESS) throw IOException("write failed")
    }

    override fun disconnect() {
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: Exception) {
        }
    }
}
