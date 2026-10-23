package com.olcayertas.tripsplit.core.lan

import com.olcayertas.tripsplit.core.ByteLink
import com.olcayertas.tripsplit.core.LinkClosedException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A [ByteLink] over a TCP socket. Blocking reads wake every [pollMs] to see whether the coroutine was
 * cancelled, so a session timeout ends a read within about one poll interval.
 */
class TcpLink(private val socket: Socket, private val pollMs: Int = 500) : ByteLink {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = pollMs
    }

    override suspend fun readExactly(n: Int): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            currentCoroutineContext().ensureActive()
            val got = try {
                input.read(out, at, n - at)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                throw LinkClosedException(e.message ?: "io")
            }
            if (got < 0) throw LinkClosedException("end of stream")
            at += got
        }
        out
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw LinkClosedException(e.message ?: "io")
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
