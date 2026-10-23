package com.olcayertas.tripsplit.core

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SALT_LEN = 16
const val TAG_LEN = 12
const val RECORD_LEN = 3 + SALT_LEN + TAG_LEN
const val MAX_FRAME = 1024 * 1024
const val GCM_TAG_LEN = 16
const val DIR_LOW_SALT: Byte = 0x01
const val DIR_HIGH_SALT: Byte = 0x02
private val MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())

/** A frame or record that breaks SPEC §8.1. The session closes. */
class WireException(message: String) : Exception(message)

/** HMAC-SHA256. */
fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmac(key, data)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // An empty HKDF salt is legal (RFC 5869). SecretKeySpec rejects an empty key, so use one zero byte:
    // HMAC pads a key with zeros to the block size, so the result is the same.
    mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
    return mac.doFinal(data)
}

/** RFC 5869 with SHA-256. */
fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32))
    val prk = hmac(salt, ikm)
    val out = java.io.ByteArrayOutputStream()
    var block = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
        block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
        out.write(block)
        counter++
    }
    return out.toByteArray().copyOf(length)
}

/** SPEC §8.1 step 1. 12 bytes. */
fun discoveryTag(groupKey: ByteArray): ByteArray =
    hmac(groupKey, "tripsplit/v1/discover".toByteArray(Charsets.US_ASCII)).copyOf(TAG_LEN)

fun toHex(bytes: ByteArray): String = bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

fun fromHex(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** SPEC §8.1 step 2: "TS1" || salt || tag, 31 bytes. */
fun handshakeRecord(salt: ByteArray, tag: ByteArray): ByteArray {
    require(salt.size == SALT_LEN && tag.size == TAG_LEN)
    return MAGIC + salt + tag
}

class HandshakeRecord(val salt: ByteArray, val tag: ByteArray)

/** Returns null when the length or the magic is wrong. */
fun parseHandshakeRecord(bytes: ByteArray): HandshakeRecord? {
    if (bytes.size != RECORD_LEN) return null
    for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
    return HandshakeRecord(bytes.copyOfRange(3, 3 + SALT_LEN), bytes.copyOfRange(3 + SALT_LEN, RECORD_LEN))
}

private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in a.indices) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return 0
}

/** The session key and the direction bytes of one session (SPEC §8.1 steps 5 and 6). */
class SessionKeys(val key: ByteArray, val sendDirection: Byte, val receiveDirection: Byte)

/** Throws [WireException] when the two salts are equal. */
fun deriveSessionKeys(groupKey: ByteArray, groupId: String, mySalt: ByteArray, peerSalt: ByteArray): SessionKeys {
    val order = compareUnsigned(mySalt, peerSalt)
    if (order == 0) throw WireException("equal salts")
    val low = if (order < 0) mySalt else peerSalt
    val high = if (order < 0) peerSalt else mySalt
    val key = hkdfSha256(groupKey, low + high, "tripsplit/v1/frame/$groupId".toByteArray(Charsets.UTF_8), 32)
    return if (order < 0) SessionKeys(key, DIR_LOW_SALT, DIR_HIGH_SALT) else SessionKeys(key, DIR_HIGH_SALT, DIR_LOW_SALT)
}

private fun nonce(direction: Byte, counter: Long): ByteArray =
    ByteBuffer.allocate(12).put(direction).put(0).put(0).put(0).putLong(counter).array()

private fun gcm(mode: Int, key: ByteArray, direction: Byte, counter: Long): Cipher {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, nonce(direction, counter)))
    return cipher
}

/** AES-256-GCM frames of one session. The send and receive counters live here. Not thread safe. */
class FrameCipher(private val keys: SessionKeys) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** `u32 big-endian length || ciphertext || tag`. */
    fun seal(plaintext: ByteArray): ByteArray {
        val sealed = gcm(Cipher.ENCRYPT_MODE, keys.key, keys.sendDirection, sendCounter).doFinal(plaintext)
        sendCounter++
        return ByteBuffer.allocate(4 + sealed.size).putInt(sealed.size).put(sealed).array()
    }

    /** [body] is the frame without its 4-byte length. The receive counter moves only on success. */
    fun open(body: ByteArray): ByteArray {
        if (body.size < GCM_TAG_LEN) throw WireException("short frame")
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, keys.key, keys.receiveDirection, receiveCounter).doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw WireException("frame does not decrypt")
        }
        receiveCounter++
        return plain
    }
}
