package com.olcayertas.tripsplit.core

import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.ensureActive

/** One end of an in-memory byte stream. Writes are cut into random pieces to test partial reads. */
class PipeEnd(
    private val inbound: Channel<ByteArray>,
    private val outbound: Channel<ByteArray>,
    private val rng: Random,
    private val maxPiece: Int,
) : ByteLink {
    private var buffer = ByteArray(0)

    override suspend fun readExactly(n: Int): ByteArray {
        while (buffer.size < n) {
            val next = try {
                inbound.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw LinkClosedException()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                throw LinkClosedException()
            }
            buffer += next
        }
        val out = buffer.copyOf(n)
        buffer = buffer.copyOfRange(n, buffer.size)
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val piece = minOf(bytes.size - at, 1 + rng.nextInt(maxPiece))
            try {
                outbound.send(bytes.copyOfRange(at, at + piece))
            } catch (e: ClosedSendChannelException) {
                throw LinkClosedException()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                throw LinkClosedException()
            }
            at += piece
        }
    }

    override fun close() {
        outbound.close()
        inbound.cancel()
    }
}

fun pipePair(seed: Long, maxPiece: Int = 40): Pair<PipeEnd, PipeEnd> {
    val ab = Channel<ByteArray>(4)
    val ba = Channel<ByteArray>(4)
    return PipeEnd(ba, ab, Random(seed), maxPiece) to PipeEnd(ab, ba, Random(seed + 1), maxPiece)
}
