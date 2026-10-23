package com.olcayertas.tripsplit.core

/**
 * SPEC §8.3 step 7. Two connections for one peer pair and one group: after both hellos, the side with the
 * higher deviceId closes the newer connection. One instance per device, shared by all its sessions.
 */
class SessionAdmission {
    class Ticket internal constructor(
        internal val order: Long,
        internal val key: String,
        internal val local: String,
        internal val peer: String,
        internal val closer: () -> Unit,
    ) {
        @Volatile internal var closed = false
    }

    private val lock = Any()
    private var counter = 0L
    private val active = HashMap<String, MutableList<Ticket>>()

    /** A session takes a number when it starts. A higher number means a newer connection. */
    fun nextOrder(): Long = synchronized(lock) { counter++ }

    /** Call after the hello of the peer. [order] comes from [nextOrder]. */
    fun register(order: Long, peerId: String, groupId: String, localId: String, closer: () -> Unit): Ticket =
        synchronized(lock) {
            val t = Ticket(order, "$peerId|$groupId", localId, peerId, closer)
            active.getOrPut(t.key) { ArrayList() }.add(t)
            t
        }

    /**
     * True when the session may go on. When a second connection is live and the local id is the higher one,
     * the newer connection is closed (it may be this one, or another one that this call finds).
     */
    fun admit(ticket: Ticket): Boolean = synchronized(lock) {
        val list = active[ticket.key] ?: return !ticket.closed
        if (list.size > 1 && ticket.local > ticket.peer) {
            val newest = list.maxByOrNull { it.order }!!
            newest.closed = true
            list.remove(newest)
            if (newest !== ticket) newest.closer()
        }
        !ticket.closed
    }

    fun release(ticket: Ticket) {
        synchronized(lock) {
            active[ticket.key]?.let {
                it.remove(ticket)
                if (it.isEmpty()) active.remove(ticket.key)
            }
        }
    }
}
