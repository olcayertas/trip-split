package com.olcayertas.tripsplit.core

import java.util.Base64

class Invite(val groupId: String, val groupKey: ByteArray)

private val INVITE_RE = Regex("tripsplit://join\\?g=([0-9a-f-]{36})#k=([A-Za-z0-9_-]{43})")
private val ID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** SPEC §9. Returns null for anything but the exact form. */
fun parseInvite(url: String): Invite? {
    val m = INVITE_RE.matchEntire(url) ?: return null
    val id = m.groupValues[1]
    if (!ID_RE.matches(id)) return null
    val key = try {
        Base64.getUrlDecoder().decode(m.groupValues[2])
    } catch (_: IllegalArgumentException) {
        return null
    }
    return if (key.size == 32) Invite(id, key) else null
}

fun makeInvite(groupId: String, groupKey: ByteArray): String {
    require(ID_RE.matches(groupId) && groupKey.size == 32)
    return "tripsplit://join?g=$groupId#k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(groupKey)
}
