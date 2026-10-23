package com.olcayertas.tripsplit.core.relay

import com.olcayertas.tripsplit.core.GCM_TAG_LEN
import com.olcayertas.tripsplit.core.canonical
import com.olcayertas.tripsplit.core.hkdfSha256
import com.olcayertas.tripsplit.core.hmacSha256
import com.olcayertas.tripsplit.core.toHex
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val RELAY_NONCE_LEN = 12
const val MAX_BATCH_OPS = 64
const val MAX_BATCH_PLAINTEXT = 256 * 1024

/** SPEC §10.4.1. The mailbox id: 32 lowercase hex characters. */
fun mailboxId(groupKey: ByteArray): String =
    toHex(hmacSha256(groupKey, "tripsplit/v1/relay/mailbox".toByteArray(Charsets.US_ASCII)).copyOf(16))

/** SPEC §10.4.1. The bearer token: 64 lowercase hex characters. */
fun relayToken(groupKey: ByteArray): String =
    toHex(hmacSha256(groupKey, "tripsplit/v1/relay/token".toByteArray(Charsets.US_ASCII)))

/** SPEC §10.4.1. The AES-256 key of the batches of one group. */
fun batchKey(groupKey: ByteArray, groupId: String): ByteArray =
    hkdfSha256(groupKey, ByteArray(0), "tripsplit/v1/relay/batch/$groupId".toByteArray(Charsets.UTF_8), 32)

private val random = SecureRandom()

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").also {
        it.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
    }

/**
 * SPEC §10.4.2. `nonce || AES-256-GCM(batchKey, nonce, {"ops":[...]}, aad = mailboxId)`. The caller keeps to
 * [MAX_BATCH_OPS] ops. A test can fix the [nonce].
 */
fun sealBatch(
    batchKey: ByteArray,
    mailboxId: String,
    ops: List<JsonObject>,
    nonce: ByteArray = ByteArray(RELAY_NONCE_LEN).also { random.nextBytes(it) },
): ByteArray {
    require(nonce.size == RELAY_NONCE_LEN)
    val plain = canonical(JsonObject(mapOf("ops" to JsonArray(ops)))).toByteArray(Charsets.UTF_8)
    val cipher = gcm(Cipher.ENCRYPT_MODE, batchKey, nonce)
    cipher.updateAAD(mailboxId.toByteArray(Charsets.US_ASCII))
    return nonce + cipher.doFinal(plain)
}

/** The size that a batch of [ops] has as plaintext, to keep it under [MAX_BATCH_PLAINTEXT]. */
fun batchPlaintextSize(ops: List<JsonObject>): Int =
    canonical(JsonObject(mapOf("ops" to JsonArray(ops)))).toByteArray(Charsets.UTF_8).size

/**
 * SPEC §10.4.2. The op envelopes of a blob, or null when the blob does not decrypt or the plaintext is not
 * `{"ops":[...]}`. The caller skips such a blob.
 */
fun openBatch(batchKey: ByteArray, mailboxId: String, blob: ByteArray): List<JsonElement>? {
    if (blob.size < RELAY_NONCE_LEN + GCM_TAG_LEN) return null
    val plain = try {
        val cipher = gcm(Cipher.DECRYPT_MODE, batchKey, blob.copyOfRange(0, RELAY_NONCE_LEN))
        cipher.updateAAD(mailboxId.toByteArray(Charsets.US_ASCII))
        cipher.doFinal(blob, RELAY_NONCE_LEN, blob.size - RELAY_NONCE_LEN)
    } catch (_: GeneralSecurityException) {
        return null
    }
    return try {
        val root = Json.parseToJsonElement(String(plain, Charsets.UTF_8))
        ((root as? JsonObject)?.get("ops") as? JsonArray)?.toList()
    } catch (_: Exception) {
        null
    }
}
