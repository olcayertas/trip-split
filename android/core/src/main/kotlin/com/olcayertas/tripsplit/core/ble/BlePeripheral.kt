package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.LinkClosedException
import com.olcayertas.tripsplit.core.Role
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.SessionException
import com.olcayertas.tripsplit.core.SessionTimeouts
import com.olcayertas.tripsplit.core.SyncSession
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** One central connected to our GATT server. The platform implements it. */
interface BlePeripheralConnection : ChunkPipe {
    /** `ATT MTU - 3` of this connection now, clamped by the caller. */
    val txLimit: Int
}

/** The responder side of one connection. The platform feeds [onChunk] with each write to `rx`. */
class BleServerSession internal constructor(private val link: BleLink, val outcome: Deferred<SessionOutcome>) {
    fun onChunk(chunk: ByteArray) = link.onChunk(chunk)

    fun onDisconnected() = link.onDisconnected()
}

/** The peripheral side (SPEC §10.2.3). It serves any number of centrals, each with its own session. */
class BlePeripheral(
    private val device: Device,
    private val admission: SessionAdmission,
    private val scope: CoroutineScope,
    private val timeouts: SessionTimeouts = SessionTimeouts.BLE,
    private val acceptLimit: Int = MAX_CHUNK,
    private val unfinishedMessageMs: Long = 15_000,
    private val onOutcome: (SessionOutcome) -> Unit = {},
) {
    /** Until the hello of the central is read, the peripheral sends chunks of size 20 (SPEC §10.2.5). */
    fun accept(conn: BlePeripheralConnection): BleServerSession {
        val link = BleLink(conn, receiveLimit = acceptLimit, scope = scope, isPeripheral = true,
            initialSendChunk = MIN_CHUNK, unfinishedMessageMs = unfinishedMessageMs)
        val outcome = scope.async {
            val o = try {
                SessionOutcome.Done(
                    SyncSession(
                        device, link, Role.RESPONDER, timeouts = timeouts, maxChunk = acceptLimit.toLong(), admission = admission,
                        onPeerHello = { hello ->
                            link.sendChunkSize = minOf(conn.txLimit, hello.maxChunk.toInt().coerceIn(MIN_CHUNK, MAX_CHUNK)).coerceIn(MIN_CHUNK, MAX_CHUNK)
                        },
                    ).run(),
                )
            } catch (e: SessionException) {
                SessionOutcome.Failed(e.reason, null)
            } catch (e: LinkClosedException) {
                SessionOutcome.Failed(e.message ?: "closed", null)
            }
            onOutcome(o)
            o
        }
        return BleServerSession(link, outcome)
    }
}
