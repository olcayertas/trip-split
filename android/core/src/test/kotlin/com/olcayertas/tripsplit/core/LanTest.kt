package com.olcayertas.tripsplit.core

import com.olcayertas.tripsplit.core.lan.DiscoveredPeer
import com.olcayertas.tripsplit.core.lan.LanDirector
import com.olcayertas.tripsplit.core.lan.PeerDiscovery
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import com.olcayertas.tripsplit.core.lan.encodeTxt
import com.olcayertas.tripsplit.core.lan.instanceName
import com.olcayertas.tripsplit.core.lan.parseTxt
import com.olcayertas.tripsplit.core.lan.sharedGroups
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LOOPBACK: java.net.InetAddress = java.net.InetAddress.getLoopbackAddress()

private fun freePort() = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

/** A "network" in memory: every started discovery sees the services that the others registered. */
class FakeNetwork {
    private class Entry(val port: Int, val txt: Map<String, String>, val onPeer: (DiscoveredPeer) -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun discovery() = object : PeerDiscovery {
        private var mine: Entry? = null
        override fun start(port: Int, txt: Map<String, String>, instance: String, onPeer: (DiscoveredPeer) -> Unit) {
            synchronized(entries) { startLocked(port, txt, onPeer) }
        }

        private fun startLocked(port: Int, txt: Map<String, String>, onPeer: (DiscoveredPeer) -> Unit) {
            val e = Entry(port, txt, onPeer)
            mine = e
            for (o in entries) {
                onPeer(DiscoveredPeer("127.0.0.1", o.port, parseTxt(o.txt.mapValues { it.value.toByteArray() })!!))
                o.onPeer(DiscoveredPeer("127.0.0.1", port, parseTxt(txt.mapValues { it.value.toByteArray() })!!))
            }
            entries.add(e)
        }

        override fun stop() {
            mine?.let { entries.remove(it) }
        }
    }
}

class LanTxtTest {
    private fun groups(n: Int) = List(n) { GroupKey("00000000-0000-4000-8000-%012d".format(it), ByteArray(32) { b -> (b + it).toByte() }) }

    @Test
    fun txtHasExactlyTheThreeKeys() {
        val gs = groups(2)
        val txt = encodeTxt("aaaaaaaa-0000-4000-8000-000000000001", gs)
        assertEquals(setOf("v", "d", "t"), txt.keys)
        assertEquals("1", txt["v"])
        val tags = txt.getValue("t").split(',')
        assertEquals(2, tags.size)
        assertTrue(tags.all { it.matches(Regex("[0-9a-f]{24}")) })
        assertEquals(toHex(gs[0].tag), tags[0])
    }

    @Test
    fun atMostEightTagsAnd199Bytes() {
        val txt = encodeTxt("aaaaaaaa-0000-4000-8000-000000000001", groups(12))
        assertEquals(8, txt.getValue("t").split(',').size)
        assertEquals(199, txt.getValue("t").toByteArray().size)
        assertEquals(txt.getValue("t").split(',').map { it }, groups(8).map { toHex(it.tag) })
    }

    @Test
    fun parseIgnoresUnknownKeysAndBadTags() {
        val gs = groups(2)
        val raw = mapOf(
            "v" to "1".toByteArray(), "d" to "bbbbbbbb-0000-4000-8000-000000000002".toByteArray(),
            "t" to (toHex(gs[1].tag) + ",ZZZ,," + "A".repeat(24)).toByteArray(), "x" to "future".toByteArray(),
        )
        val rec = assertNotNull(parseTxt(raw))
        assertEquals(listOf(toHex(gs[1].tag)), rec.tags)
        assertEquals(listOf(gs[1].groupId), sharedGroups(rec, gs).map { it.groupId })
        assertNull(parseTxt(raw - "d"))
        assertNull(parseTxt(raw - "v"))
        assertEquals("TripSplit-bbbbbbbb", instanceName("bbbbbbbb-0000-4000-8000-000000000002"))
    }
}

@Timeout(120)
class LanDirectorTest {
    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"

    private fun device(id: String, seed: Long) = TestDevice(id, seed)

    private suspend fun <T> eventually(what: String, block: suspend () -> T?): T = withTimeout(30_000) {
        while (true) {
            block()?.let { return@withTimeout it }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error(what)
    }

    @Test
    fun twoDirectorsFindEachOtherAndSync() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = device(idA, 1)
        val b = device(idB, 2)
        val g = a.device.createGroup("Trip", "EUR", "Ali")
        a.device.append(g.groupId, "member.add", buildJsonObject { put("memberId", a.device.newId()); put("name", "Ayşe") })
        b.device.join(parseInvite(g.invite())!!)
        val net = FakeNetwork()
        val da = LanDirector(a.device, net.discovery(), { null }, scope, preferredPort = freePort(), tickMs = 100, bindAddress = LOOPBACK)
        val db = LanDirector(b.device, net.discovery(), { null }, scope, preferredPort = freePort(), tickMs = 100, bindAddress = LOOPBACK)
        da.start()
        db.start()
        eventually("B has the log") { b.store.ops(g.groupId).size.takeIf { it == 2 } }
        assertEquals(
            runBlocking { fold(g.groupId, a.store.ops(g.groupId)).toJson() },
            runBlocking { fold(g.groupId, b.store.ops(g.groupId)).toJson() },
        )
        // The state shows the peer and a finished session.
        eventually("peer listed") { da.state.value.peers.firstOrNull { it.lastOutcome is SessionOutcome.Done } }
        assertTrue(da.state.value.running)
        // A new local op on B moves to A through the cooldown (a new local op lifts the 30 s wait).
        b.device.append(g.groupId, "group.rename", buildJsonObject { put("name", "Renamed") })
        eventually("A has the rename") { a.store.ops(g.groupId).size.takeIf { it == 3 } }
        da.stop()
        db.stop()
        scope.cancel()
    }

    @Test
    fun aPeerWithNoSharedGroupIsLeftAlone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = device(idA, 1)
        val b = device(idB, 2)
        a.device.createGroup("Mine", "EUR", "Ali")
        b.device.createGroup("Yours", "EUR", "Bo")
        val net = FakeNetwork()
        val da = LanDirector(a.device, net.discovery(), { null }, scope, preferredPort = freePort(), tickMs = 100, bindAddress = LOOPBACK)
        val db = LanDirector(b.device, net.discovery(), { null }, scope, preferredPort = freePort(), tickMs = 100, bindAddress = LOOPBACK)
        da.start(); db.start()
        delay(1500)
        assertNull(da.state.value.lastOutcome)
        assertNull(db.state.value.lastOutcome)
        assertEquals(1, a.store.groups().size)
        da.stop(); db.stop(); scope.cancel()
    }

    @Test
    fun gatewayFallbackDialsWhenNoServiceAppears() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = device(idA, 1)
        val b = device(idB, 2)
        val g = a.device.createGroup("Trip", "EUR", "Ali")
        b.device.join(parseInvite(g.invite())!!)
        val bPort = freePort()
        val silent = object : PeerDiscovery {
            override fun start(port: Int, txt: Map<String, String>, instance: String, onPeer: (DiscoveredPeer) -> Unit) {}
            override fun stop() {}
        }
        val db = LanDirector(b.device, silent, { null }, scope, preferredPort = bPort, tickMs = 100, bindAddress = LOOPBACK)
        val da = LanDirector(
            a.device, silent, { "127.0.0.1" }, scope, preferredPort = freePort(), tickMs = 100,
            fallbackAfterMs = 300, fallbackEveryMs = 500, gatewayPort = bPort, bindAddress = LOOPBACK,
        )
        db.start(); da.start()
        eventually("B downloaded through the fallback") { b.store.ops(g.groupId).size.takeIf { it == 1 } }
        eventually("fallback peer listed") { da.state.value.peers.firstOrNull { it.viaFallback } }
        da.stop(); db.stop(); scope.cancel()
    }

    @Test
    fun listenerFallsBackToAnyPortWhenThePreferredOneIsBusy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val busy = ServerSocket(0, 1, LOOPBACK)
        val a = device(idA, 1)
        val d = LanDirector(a.device, object : PeerDiscovery {
            override fun start(port: Int, txt: Map<String, String>, instance: String, onPeer: (DiscoveredPeer) -> Unit) {}
            override fun stop() {}
        }, { null }, scope, preferredPort = busy.localPort, bindAddress = LOOPBACK)
        d.start()
        val port = assertNotNull(d.state.value.port)
        assertTrue(port != busy.localPort && port > 0)
        d.stop(); busy.close(); scope.cancel()
    }
}
