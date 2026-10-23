package com.olcayertas.tripsplit.core

const val MAX_FORWARD_SKEW_MS = 24L * 3600 * 1000
const val MAX_COUNTER = 0xFFFF

fun hlcFormat(millis: Long, counter: Int, device: String): String =
    String.format(java.util.Locale.ROOT, "%015d-%04x-%s", millis, counter, device)

data class HlcStamp(val millis: Long, val counter: Int, val device: String)

private val HLC_RE = Regex("([0-9]{15})-([0-9a-f]{4})-(.+)")

fun hlcParse(s: String): HlcStamp {
    val m = requireNotNull(HLC_RE.matchEntire(s)) { "bad hlc" }
    return HlcStamp(m.groupValues[1].toLong(), m.groupValues[2].toInt(16), m.groupValues[3])
}

/** SPEC §3. The wall time is a parameter. This class never reads the system clock. */
class Hlc(val device: String, var millis: Long = 0, var counter: Int = 0) {
    private fun norm() {
        if (counter > MAX_COUNTER) {
            millis += 1
            counter = 0
        }
    }

    fun current(): String = hlcFormat(millis, counter, device)

    fun send(wall: Long): String {
        if (wall > millis) {
            millis = wall
            counter = 0
        } else {
            counter += 1
        }
        norm()
        return current()
    }

    /** Returns true when the remote stamp is refused for clock skew. The state does not change. */
    fun receive(remote: String, wall: Long): Boolean {
        val r = hlcParse(remote)
        if (r.millis > wall + MAX_FORWARD_SKEW_MS) return true
        val m = maxOf(millis, r.millis, wall)
        val c = when {
            m == millis && m == r.millis -> maxOf(counter, r.counter) + 1
            m == millis -> counter + 1
            m == r.millis -> r.counter + 1
            else -> 0
        }
        millis = m
        counter = c
        norm()
        return false
    }
}
