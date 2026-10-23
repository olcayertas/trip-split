package com.olcayertas.tripsplit.core.relay

import java.security.MessageDigest

/** A relay in this process. It follows SPEC §10.4.3: cursors from 1, a token fixed by the first POST. */
class FakeRelay(
    private val maxBlobs: Int = 20_000,
    /** A test can flip bits in the stored blobs, to play a relay that changes data. */
    private val tamper: (ByteArray) -> ByteArray = { it },
) : RelayApi {
    private class Mailbox(val tokenHash: ByteArray) {
        val blobs = ArrayList<ByteArray>()
    }

    private val boxes = HashMap<String, Mailbox>()
    var failNext: RelayFailure? = null
    var pushes = 0
        private set
    var pulls = 0
        private set

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    @Synchronized
    fun blobCount(mailboxId: String): Int = boxes[mailboxId]?.blobs?.size ?: 0

    override suspend fun push(mailboxId: String, token: String, blob: ByteArray): Long = synchronized(this) {
        failNext?.let { failNext = null; throw RelayException(it, "injected") }
        pushes++
        val box = boxes.getOrPut(mailboxId) { Mailbox(hash(token)) }
        if (!box.tokenHash.contentEquals(hash(token))) throw RelayException(RelayFailure.FORBIDDEN, "token")
        if (blob.size > 320 * 1024) throw RelayException(RelayFailure.TOO_LARGE, "size")
        if (box.blobs.size >= maxBlobs) throw RelayException(RelayFailure.FULL, "full")
        box.blobs.add(blob.copyOf())
        box.blobs.size.toLong()
    }

    override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage = synchronized(this) {
        failNext?.let { failNext = null; throw RelayException(it, "injected") }
        pulls++
        val box = boxes[mailboxId] ?: return@synchronized RelayPage(emptyList(), false)
        if (!box.tokenHash.contentEquals(hash(token))) throw RelayException(RelayFailure.FORBIDDEN, "token")
        val from = after.toInt()
        val to = minOf(box.blobs.size, from + limit)
        val batches = (from until to).map { RelayBatch((it + 1).toLong(), tamper(box.blobs[it])) }
        RelayPage(batches, to < box.blobs.size)
    }
}
