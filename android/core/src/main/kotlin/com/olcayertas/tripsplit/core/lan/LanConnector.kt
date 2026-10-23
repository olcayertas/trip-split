package com.olcayertas.tripsplit.core.lan

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.LinkClosedException
import com.olcayertas.tripsplit.core.Role
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.SessionException
import com.olcayertas.tripsplit.core.SessionTimeouts
import com.olcayertas.tripsplit.core.SyncSession
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val LAN_CONNECT_TIMEOUT_MS = 5_000

/** Dials a peer and runs an initiator session for one group (SPEC §10.1.2 step 3). */
class LanConnector(
    private val device: Device,
    private val admission: SessionAdmission,
    private val timeouts: SessionTimeouts = SessionTimeouts.LAN,
    private val connectTimeoutMs: Int = LAN_CONNECT_TIMEOUT_MS,
) {
    suspend fun connect(host: String, port: Int, groupId: String): SessionOutcome {
        val socket = Socket()
        try {
            withContext(Dispatchers.IO) { socket.connect(InetSocketAddress(host, port), connectTimeoutMs) }
        } catch (e: IOException) {
            socket.close()
            return SessionOutcome.Failed("connect: ${e.message}", null)
        }
        return try {
            SessionOutcome.Done(
                SyncSession(device, TcpLink(socket), Role.INITIATOR, groupId, timeouts, admission = admission).run(),
            )
        } catch (e: SessionException) {
            SessionOutcome.Failed(e.reason, null)
        } catch (e: LinkClosedException) {
            SessionOutcome.Failed(e.message ?: "closed", null)
        }
    }
}
