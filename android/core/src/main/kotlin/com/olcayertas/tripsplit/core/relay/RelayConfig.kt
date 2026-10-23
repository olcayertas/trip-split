package com.olcayertas.tripsplit.core.relay

/** The build default. A host that ends in `.invalid` never resolves (RFC 2606), so it marks "no relay". */
const val RELAY_PLACEHOLDER_URL = "https://relay.invalid"

/** PLAN §9.4: the relay sync stays off until the build sets a real base URL. */
fun isRelayConfigured(baseUrl: String): Boolean {
    val host = try {
        java.net.URI(baseUrl.trim()).host
    } catch (_: Exception) {
        null
    } ?: return false
    return !host.lowercase().endsWith(".invalid")
}
