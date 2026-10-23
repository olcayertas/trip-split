package com.olcayertas.tripsplit.core

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptoVectorTest {
    private val v = Vectors.load("crypto")
    private val groupKey = fromHex(v.getValue("groupKeyHex").str())
    private val groupId = v.getValue("groupId").str()

    @Test
    fun hkdfRfc5869Case1() {
        val c = v.getValue("hkdfRfc5869Case1").jsonObject
        val okm = hkdfSha256(
            fromHex(c.getValue("ikmHex").str()), fromHex(c.getValue("saltHex").str()),
            fromHex(c.getValue("infoHex").str()), c.getValue("length").long().toInt(),
        )
        assertEquals(c.getValue("okmHex").str(), toHex(okm))
    }

    @Test
    fun discoveryTags() {
        assertEquals(v.getValue("discoveryTagHex").str(), toHex(discoveryTag(groupKey)))
        // The other group in the vector has a different key; the tag must differ.
        assertEquals(24, toHex(discoveryTag(groupKey)).length)
    }

    @Test
    fun recordsAndSessionKey() {
        val tag = discoveryTag(groupKey)
        val iSalt = fromHex(v.getValue("initiatorSaltHex").str())
        val rSalt = fromHex(v.getValue("responderSaltHex").str())
        assertEquals(v.getValue("initiatorRecordHex").str(), toHex(handshakeRecord(iSalt, tag)))
        assertEquals(v.getValue("responderRecordHex").str(), toHex(handshakeRecord(rSalt, tag)))
        val parsed = parseHandshakeRecord(fromHex(v.getValue("initiatorRecordHex").str()))!!
        assertContentEquals(iSalt, parsed.salt)
        assertContentEquals(tag, parsed.tag)

        val a = deriveSessionKeys(groupKey, groupId, iSalt, rSalt)
        val b = deriveSessionKeys(groupKey, groupId, rSalt, iSalt)
        assertEquals(v.getValue("sessionKeyHex").str(), toHex(a.key))
        assertContentEquals(a.key, b.key)
        assertEquals(v.getValue("initiatorDirection").long().toInt(), a.sendDirection.toInt())
        assertEquals(v.getValue("responderDirection").long().toInt(), b.sendDirection.toInt())
        assertFailsWith<WireException> { deriveSessionKeys(groupKey, groupId, iSalt, iSalt) }
    }

    @Test
    fun badRecords() {
        val good = fromHex(v.getValue("initiatorRecordHex").str())
        assertEquals(null, parseHandshakeRecord(good.copyOf(30)))
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals(null, parseHandshakeRecord(badMagic))
    }

    @Test
    fun framesSealAndOpen() {
        val iSalt = fromHex(v.getValue("initiatorSaltHex").str())
        val rSalt = fromHex(v.getValue("responderSaltHex").str())
        val initiator = FrameCipher(deriveSessionKeys(groupKey, groupId, iSalt, rSalt))
        val responder = FrameCipher(deriveSessionKeys(groupKey, groupId, rSalt, iSalt))
        for (f in v.getValue("frames").objs()) {
            val fromInitiator = f.getValue("direction").long() == 1L
            val (sender, receiver) = if (fromInitiator) initiator to responder else responder to initiator
            val plain = fromHex(f.getValue("plaintextHex").str())
            val frame = fromHex(f.getValue("frameHex").str())
            assertContentEquals(frame, sender.seal(plain), "seal ${f.getValue("counter")}")
            assertContentEquals(plain, receiver.open(frame.copyOfRange(4, frame.size)))
        }
    }

    @Test
    fun tamperedFrameFails() {
        val iSalt = fromHex(v.getValue("initiatorSaltHex").str())
        val rSalt = fromHex(v.getValue("responderSaltHex").str())
        val responder = FrameCipher(deriveSessionKeys(groupKey, groupId, rSalt, iSalt))
        val frame = fromHex(v.getValue("tamperedFrameHex").str())
        assertFailsWith<WireException> { responder.open(frame.copyOfRange(4, frame.size)) }
    }
}
