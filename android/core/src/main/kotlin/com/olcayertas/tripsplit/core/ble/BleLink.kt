package com.olcayertas.tripsplit.core.ble

import com.olcayertas.tripsplit.core.ByteLink
import com.olcayertas.tripsplit.core.LinkClosedException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One BLE connection as the link layer sees it. The platform implements it. */
interface ChunkPipe {
    /**
     * Sends one ATT value and returns after the platform confirms it: the write response (central) or
     * `onNotificationSent` (peripheral). SPEC §10.2.6.
     */
    suspend fun send(chunk: ByteArray)

    /** Central: disconnects the GATT link. Peripheral: drops the central when the platform can. Idempotent. */
    fun disconnect()
}

/**
 * A [ByteLink] over chunks (SPEC §10.2.4). Each `write` is one message. Each read takes bytes from one message and
 * never spans two: a record is exactly 31 bytes, a frame is exactly `4 + length`. The platform calls [onChunk] for
 * every ATT value it receives, in order.
 *
 * @param receiveLimit the largest chunk this side accepts.
 * @param isPeripheral a peripheral sends an ABORT chunk before it drops the link (SPEC §10.2.7).
 */
class BleLink(
    private val pipe: ChunkPipe,
    receiveLimit: Int,
    private val scope: CoroutineScope,
    private val isPeripheral: Boolean,
    initialSendChunk: Int = MIN_CHUNK,
    private val unfinishedMessageMs: Long = 15_000,
) : ByteLink {
    @Volatile
    var sendChunkSize: Int = initialSendChunk.coerceIn(MIN_CHUNK, MAX_CHUNK)

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val messages = Channel<ByteArray>(Channel.UNLIMITED)
    private val reassembler = Reassembler(receiveLimit)
    private val sendLock = Mutex()
    private var sendSeq = 0
    private val closed = AtomicBoolean(false)
    private var current: ByteArray = ByteArray(0)
    private var offset = 0

    /** The reason the link closed, or null. For the Nearby screen and tests. */
    @Volatile var closeReason: String? = null
        private set

    init {
        scope.launch {
            try {
                while (true) {
                    val chunk = if (reassembler.partial) {
                        withTimeout(unfinishedMessageMs) { incoming.receive() }
                    } else {
                        incoming.receive()
                    }
                    reassembler.feed(chunk)?.let { messages.trySend(it) }
                }
            } catch (e: ReassemblyException) {
                fail(e.code)
            } catch (_: TimeoutCancellationException) {
                fail("unfinished_message")
            } catch (_: ClosedReceiveChannelException) {
            }
        }
    }

    /** Called by the platform callback for each chunk received. Does not block. */
    fun onChunk(chunk: ByteArray) {
        incoming.trySend(chunk)
    }

    /** The platform tells the link that the connection is gone. */
    fun onDisconnected() = fail("disconnected", sendAbort = false)

    private fun fail(reason: String, sendAbort: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        incoming.close()
        messages.close(LinkClosedException(reason))
        drop(sendAbort && reason != "abort")
    }

    private fun drop(sendAbort: Boolean) {
        scope.launch {
            if (isPeripheral && sendAbort) {
                try {
                    val seq = sendSeq
                    pipe.send(abortChunk(seq))
                } catch (_: Exception) {
                }
            }
            pipe.disconnect()
        }
    }

    override suspend fun readExactly(n: Int): ByteArray {
        if (offset >= current.size) {
            current = try {
                messages.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw LinkClosedException(closeReason ?: "closed")
            }
            offset = 0
            if (current.size < n) { fail("bad_message_size"); throw LinkClosedException("bad_message_size") }
        } else if (current.size - offset != n) {
            fail("bad_message_size")
            throw LinkClosedException("bad_message_size")
        }
        val out = current.copyOfRange(offset, offset + n)
        offset += n
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed.get()) throw LinkClosedException(closeReason ?: "closed")
        sendLock.withLock {
            val chunks = chunkMessage(bytes, sendChunkSize, sendSeq)
            for (c in chunks) {
                if (closed.get()) throw LinkClosedException(closeReason ?: "closed")
                try {
                    pipe.send(c)
                } catch (e: LinkClosedException) {
                    throw e
                } catch (e: java.io.IOException) {
                    fail("send_failed", sendAbort = false)
                    throw LinkClosedException("send_failed")
                }
                sendSeq = (sendSeq + 1) and 0xFFFF
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeReason = closeReason ?: "closed"
        incoming.close()
        messages.close(LinkClosedException(closeReason ?: "closed"))
        drop(sendAbort = true)
    }
}
