package com.olcayertas.tripsplit.core

import com.olcayertas.tripsplit.core.ble.BleUuids
import com.olcayertas.tripsplit.core.ble.Reassembler
import com.olcayertas.tripsplit.core.ble.ReassemblyException
import com.olcayertas.tripsplit.core.ble.abortChunk
import com.olcayertas.tripsplit.core.ble.chunkMessage
import com.olcayertas.tripsplit.core.ble.chunkSizeForMtu
import com.olcayertas.tripsplit.core.ble.infoValue
import com.olcayertas.tripsplit.core.ble.parseInfo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BleChunkVectorTest {
    private val v = Vectors.load("ble_chunk")

    @Test
    fun uuids() {
        val u = v.getValue("uuids").jsonObject
        assertEquals(u.getValue("service").str(), BleUuids.SERVICE)
        assertEquals(u.getValue("rx").str(), BleUuids.RX)
        assertEquals(u.getValue("tx").str(), BleUuids.TX)
        assertEquals(u.getValue("info").str(), BleUuids.INFO)
    }

    @Test
    fun chunkSizes() {
        for (c in v.getValue("chunkSizes").objs()) {
            assertEquals(c.getValue("chunkSize").long().toInt(), chunkSizeForMtu(c.getValue("mtu").long().toInt()))
        }
    }

    @Test
    fun infoValueMatches() {
        val i = v.getValue("info").jsonObject
        val parsed = assertNotNull(parseInfo(fromHex(i.getValue("hex").str())))
        assertEquals(i.getValue("deviceId").str(), parsed.deviceId)
        assertEquals(setOf("central", "peripheral"), parsed.roles)
        val built = infoValue(parsed.deviceId, parsed.maxChunk, parsed.roles, parsed.tags)
        assertEquals(i.getValue("hex").str(), toHex(built))
        assertEquals(i.getValue("text").str(), String(built, Charsets.UTF_8))
    }

    @Test
    fun badInfoIsNull() {
        assertNull(parseInfo("not json".toByteArray()))
        assertNull(parseInfo("""{"deviceId":"x"}""".toByteArray()))
        assertNull(parseInfo("""{"deviceId":"x","maxChunk":10,"protocolVersion":1,"roles":[],"tags":[]}""".toByteArray()))
    }

    @Test
    fun chunking() {
        for (c in v.getValue("cases").objs()) {
            val name = c.getValue("name").str() + "@" + c.getValue("chunkSize").str()
            val message = fromHex(c.getValue("message").str())
            val chunks = chunkMessage(message, c.getValue("chunkSize").long().toInt(), c.getValue("firstSeq").long().toInt())
            val want = c.getValue("chunks").strings()
            assertEquals(want, chunks.map { toHex(it) }, name)
            // And back: a fresh receiver reassembles the message.
            val r = Reassembler(512)
            var got: ByteArray? = null
            for (ch in chunks) {
                if (c.getValue("firstSeq").long() != 0L) break
                got = r.feed(ch) ?: got
            }
            if (c.getValue("firstSeq").long() == 0L) assertContentEquals(message, got, name)
        }
    }

    @Test
    fun seqWrapsAndReceiverFollows() {
        val chunks = chunkMessage(ByteArray(60) { it.toByte() }, 20, 65534)
        assertEquals(listOf(65534, 65535, 0, 1), chunks.map { ((it[1].toInt() and 0xff) shl 8) or (it[2].toInt() and 0xff) })
    }

    @Test
    fun errors() {
        for ((i, e) in v.getValue("errors").objs().withIndex()) {
            val r = Reassembler(e.getValue("receiveLimit").long().toInt())
            val chunks: List<ByteArray> = if (e.containsKey("repeat")) {
                val rep = e.getValue("repeat").jsonObject
                val count = rep.getValue("count").long().toInt()
                val size = rep.getValue("payloadBytes").long().toInt()
                List(count) { n ->
                    val flags = if (n == 0) rep.getValue("firstFlags").long().toInt() else rep.getValue("flags").long().toInt()
                    byteArrayOf(flags.toByte(), (n shr 8).toByte(), n.toByte()) + ByteArray(size)
                }
            } else {
                e.getValue("chunks").strings().map { fromHex(it) }
            }
            val at = e.getValue("atChunk").long().toInt()
            val want = e.getValue("error").str()
            var failedAt = -1
            var code = ""
            for ((n, ch) in chunks.withIndex()) {
                try {
                    r.feed(ch)
                } catch (ex: ReassemblyException) {
                    failedAt = n
                    code = ex.code
                    break
                }
            }
            assertEquals(at, failedAt, "case $i $want")
            assertEquals(want, code, "case $i")
        }
    }

    @Test
    fun abortChunkShape() {
        assertContentEquals(byteArrayOf(4, 0, 7), abortChunk(7))
        assertFailsWith<IllegalArgumentException> { chunkMessage(ByteArray(0), 20) }
        assertFailsWith<IllegalArgumentException> { chunkMessage(ByteArray(5), 19) }
    }
}
