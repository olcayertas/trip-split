package com.olcayertas.tripsplit.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A byte stream to one peer. LAN is a TCP socket. BLE feeds the reassembled messages in order. */
interface ByteLink {
    /** Exactly [n] bytes. Throws [LinkClosedException] at the end of the stream. */
    suspend fun readExactly(n: Int): ByteArray

    suspend fun write(bytes: ByteArray)

    /** Idempotent. Wakes a blocked read with [LinkClosedException]. */
    fun close()
}

class LinkClosedException(message: String = "link closed") : Exception(message)

/** A protocol error: the session closes at once and sends nothing first (SPEC §10.1.4). */
class SessionException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

enum class Role { INITIATOR, RESPONDER }

/** The limits of SPEC §10.1.4 (LAN) and §10.2.7 (BLE), in milliseconds. */
data class SessionTimeouts(
    val record: Long,
    val hello: Long,
    val idle: Long,
    val total: Long,
    val closeWait: Long,
) {
    companion object {
        val LAN = SessionTimeouts(record = 5_000, hello = 10_000, idle = 30_000, total = 600_000, closeWait = 5_000)
        val BLE = SessionTimeouts(record = 10_000, hello = 15_000, idle = 30_000, total = 600_000, closeWait = 5_000)
    }
}

class SessionResult(
    val groupId: String,
    val peerDeviceId: String,
    val sentOps: Int,
    val receivedOps: Int,
    val skippedOps: Int,
    val droppedOps: Int,
    val forks: Int,
    val clockSkew: Boolean,
)

/**
 * One sync session over one [ByteLink] (SPEC §8). The initiator names its group. The responder finds its
 * group by the tag in the first record.
 */
class SyncSession(
    private val device: Device,
    private val link: ByteLink,
    private val role: Role,
    private val initiatorGroupId: String? = null,
    private val timeouts: SessionTimeouts = SessionTimeouts.LAN,
    private val appVersion: String = "1.0.0",
    /** `hello.maxChunk`: 0 on LAN. */
    private val maxChunk: Long = 0,
    private val admission: SessionAdmission? = null,
    private val random: SecureRandom = SecureRandom(),
) {
    private lateinit var group: GroupKey
    private lateinit var cipher: FrameCipher
    private var peerId = ""
    private var sent = 0
    private var received = 0
    private var skipped = 0
    private var dropped = 0
    private var forks = 0
    private var skew = false
    private var admissionTicket: SessionAdmission.Ticket? = null
    private val startOrder = admission?.nextOrder() ?: 0L

    init {
        require(role == Role.RESPONDER || initiatorGroupId != null) { "initiator needs a group" }
    }

    /** Runs to completion. Throws [SessionException] or [LinkClosedException] when the session fails. */
    suspend fun run(): SessionResult {
        try {
            withTimeout(timeouts.total) {
                handshake()
                receiveLoop()
            }
            if (role == Role.RESPONDER) {
                // The initiator closes (SPEC §8.3 step 6). Wait for the end of the stream, then close.
                withTimeoutOrNull(timeouts.closeWait) {
                    try {
                        link.readExactly(1)
                    } catch (_: LinkClosedException) {
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw SessionException("timeout", e)
        } finally {
            admissionTicket?.let { admission?.release(it) }
            link.close()
        }
        return SessionResult(group.groupId, peerId, sent, received, skipped, dropped, forks, skew)
    }

    private suspend fun <T> within(ms: Long, what: String, block: suspend () -> T): T =
        try {
            withTimeout(ms) { block() }
        } catch (e: TimeoutCancellationException) {
            throw SessionException("timeout: $what", e)
        }

    private suspend fun handshake() {
        val mySalt = device.randomBytes(SALT_LEN)
        val theirs: HandshakeRecord
        if (role == Role.INITIATOR) {
            group = device.store.group(initiatorGroupId!!) ?: throw SessionException("unknown group")
            link.write(handshakeRecord(mySalt, group.tag))
            theirs = within(timeouts.record, "responder record") { readRecord() }
            if (!theirs.tag.contentEquals(group.tag)) throw SessionException("tag differs")
        } else {
            theirs = within(timeouts.record, "initiator record") { readRecord() }
            group = device.store.groups().firstOrNull { it.tag.contentEquals(theirs.tag) }
                ?: throw SessionException("unknown group tag")
            link.write(handshakeRecord(mySalt, group.tag))
        }
        val keys = try {
            deriveSessionKeys(group.key, group.groupId, mySalt, theirs.salt)
        } catch (e: WireException) {
            throw SessionException(e.message ?: "salt", e)
        }
        cipher = FrameCipher(keys)
    }

    private suspend fun readRecord(): HandshakeRecord =
        parseHandshakeRecord(link.readExactly(RECORD_LEN)) ?: throw SessionException("bad record")

    private suspend fun hello(): Message = Hello(
        protocolVersion = PROTOCOL_VERSION.toLong(),
        deviceId = device.deviceId,
        appVersion = appVersion,
        groupId = group.groupId,
        vector = device.store.vector(group.groupId),
        wallMillis = device.wall(),
        maxChunk = maxChunk,
    )

    private suspend fun send(message: Message) {
        link.write(cipher.seal(encodeMessage(message)))
    }

    private suspend fun receive(timeout: Long, what: String): Message = within(timeout, what) {
        val length = ByteBuffer.wrap(link.readExactly(4)).int
        if (length < 0 || length > MAX_FRAME) throw SessionException("frame too large")
        val body = link.readExactly(length)
        try {
            decodeMessage(cipher.open(body))
        } catch (e: WireException) {
            throw SessionException(e.message ?: "bad frame", e)
        }
    }

    private suspend fun receiveLoop() = coroutineScope {
        var gotHello = false
        var gotDone = false
        val peerVector = CompletableDeferred<Map<String, Long>>()
        // The sender runs beside the receiver: if both sides wrote a large log and nobody read, TCP would stall.
        // The hello is written here too: a link with a small buffer would block both sides if each wrote first.
        val sender = launch {
            send(hello())
            sendOps(peerVector.await())
        }
        while (!gotDone) {
            val message = receive(if (gotHello) timeouts.idle else timeouts.hello, if (gotHello) "idle" else "hello")
            when {
                !gotHello -> {
                    if (message !is Hello) throw SessionException("first frame is not hello")
                    checkHello(message)
                    gotHello = true
                    peerVector.complete(message.vector)
                }
                message is OpsMessage -> onOps(message.ops)
                message is Done -> gotDone = true
                else -> throw SessionException("unexpected hello")
            }
        }
        sender.join()
    }

    private fun checkHello(m: Hello) {
        if (m.groupId != group.groupId) throw SessionException("hello group differs from the tag group")
        if (m.protocolVersion < 1) throw SessionException("bad protocol version")
        if (m.wallMillis > device.wall() + MAX_FORWARD_SKEW_MS) skew = true
        peerId = m.deviceId
        admission?.let { adm ->
            val ticket = adm.register(startOrder, peerId, group.groupId, device.deviceId) { link.close() }
            admissionTicket = ticket
            if (!adm.admit(ticket)) throw SessionException("duplicate session")
        }
    }

    private suspend fun onOps(ops: List<JsonElement>) {
        val r = try {
            device.receive(group.groupId, ops)
        } catch (e: BadHashException) {
            throw SessionException("bad op hash", e)
        }
        received += r.stored
        skipped += r.skipped
        dropped += r.dropped
        forks += r.forks
        skew = skew || r.skew
    }

    private suspend fun sendOps(peerVector: Map<String, Long>) {
        var batch = ArrayList<JsonObject>()
        var size = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            send(OpsMessage(batch))
            sent += batch.size
            batch = ArrayList()
            size = 0
        }
        for (op in device.store.missingFor(group.groupId, peerVector)) {
            val n = canonicalBytes(op).size
            if (batch.isNotEmpty() && (batch.size >= MAX_OPS_PER_MESSAGE || size + n > MAX_OPS_PLAINTEXT - 1024)) flush()
            batch.add(op)
            size += n
        }
        flush()
        send(Done(device.store.vector(group.groupId)))
    }
}
