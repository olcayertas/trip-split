package com.olcayertas.tripsplit.core.relay

import kotlinx.coroutines.CancellationException

/** SPEC §10.4.3: the wait after a failed request grows from 5 s to 5 min and falls back after a success. */
class RelayBackoff(private val minMs: Long = 5_000, private val maxMs: Long = 300_000) {
    private var current = 0L

    /** The wait after one more failure. */
    fun failure(): Long {
        current = if (current == 0L) minMs else minOf(current * 2, maxMs)
        return current
    }

    fun success() {
        current = 0
    }
}

/**
 * Decides when a relay sync may run. The app calls [syncAll] when a group screen opens, after a save and
 * on a 60 s timer. A call is a no-op when the relay is off, the device is offline, or the back-off wait has
 * not passed. This class holds no thread: the caller owns the timer.
 */
class RelayCoordinator(
    private val sync: RelaySync,
    private val enabled: () -> Boolean,
    private val online: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val backoff: RelayBackoff = RelayBackoff(),
) {
    private var notBefore = 0L

    /** Returns true when a sync ran for every group without a failure. */
    suspend fun syncAll(groupIds: List<String>): Boolean {
        if (!enabled() || !online() || now() < notBefore) return false
        var ok = true
        for (id in groupIds) {
            try {
                sync.sync(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                ok = false
                if (e.failure == RelayFailure.RETRY) {
                    // The network or the relay is down. The other groups would fail the same way.
                    notBefore = now() + backoff.failure()
                    return false
                }
                // A full or locked mailbox is a problem of one group. The other groups go on.
            }
        }
        if (ok) backoff.success()
        return ok
    }
}
