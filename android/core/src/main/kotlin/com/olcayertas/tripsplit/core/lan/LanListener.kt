package com.olcayertas.tripsplit.core.lan

import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.LinkClosedException
import com.olcayertas.tripsplit.core.Role
import com.olcayertas.tripsplit.core.SessionAdmission
import com.olcayertas.tripsplit.core.SessionException
import com.olcayertas.tripsplit.core.SessionResult
import com.olcayertas.tripsplit.core.SessionTimeouts
import com.olcayertas.tripsplit.core.SyncSession
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a finished or failed session reports. */
sealed interface SessionOutcome {
    data class Done(val result: SessionResult) : SessionOutcome
    data class Failed(val reason: String, val peer: String?) : SessionOutcome
}

/**
 * Listens on TCP (SPEC §10.1.1). Tries [preferredPort] first, then any free port. Each accepted
 * connection is a responder session.
 */
class LanListener(
    private val device: Device,
    private val admission: SessionAdmission,
    private val preferredPort: Int = LAN_PORT,
    private val timeouts: SessionTimeouts = SessionTimeouts.LAN,
    private val onOutcome: (SessionOutcome) -> Unit = {},
    /** Null binds every interface. Tests pass loopback. */
    private val bindAddress: java.net.InetAddress? = null,
) {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    /** The bound port. Starts the accept loop in [scope]. */
    fun start(scope: CoroutineScope): Int {
        val s = try {
            ServerSocket(preferredPort, 50, bindAddress)
        } catch (_: IOException) {
            ServerSocket(0, 50, bindAddress)
        }
        server = s
        acceptJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val socket = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch { runResponder(socket) }
            }
        }
        return s.localPort
    }

    private suspend fun runResponder(socket: Socket) {
        val outcome = try {
            SessionOutcome.Done(
                SyncSession(device, TcpLink(socket), Role.RESPONDER, timeouts = timeouts, admission = admission).run(),
            )
        } catch (e: SessionException) {
            SessionOutcome.Failed(e.reason, null)
        } catch (e: LinkClosedException) {
            SessionOutcome.Failed(e.message ?: "closed", null)
        }
        onOutcome(outcome)
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        acceptJob?.cancel()
        server = null
    }
}
