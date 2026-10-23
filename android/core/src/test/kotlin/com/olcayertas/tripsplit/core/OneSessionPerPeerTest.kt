package com.olcayertas.tripsplit.core

import com.olcayertas.tripsplit.core.ble.BleDirector
import com.olcayertas.tripsplit.core.ble.BleHost
import com.olcayertas.tripsplit.core.ble.BlePeripheral
import com.olcayertas.tripsplit.core.ble.BleScanner
import com.olcayertas.tripsplit.core.ble.buildInfo
import com.olcayertas.tripsplit.core.lan.LanDirector
import com.olcayertas.tripsplit.core.lan.LanListener
import com.olcayertas.tripsplit.core.lan.PeerStatus
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import com.olcayertas.tripsplit.core.lan.Transport
import com.olcayertas.tripsplit.core.lan.encodeTxt
import com.olcayertas.tripsplit.core.lan.instanceName
import com.olcayertas.tripsplit.core.lan.mergePeers
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
private fun freePort() = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

/**
 * A reaches B over Wi-Fi and over Bluetooth at the same time. B counts every session that starts on it, on both
 * transports. Wi-Fi wins (PLAN §9.2), and the two directors of A share one admission (SPEC §8.3 step 7).
 */
@Timeout(120)
class OneSessionPerPeerTest {
    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"

    /** Returns the number of sessions that started on B, over both transports. */
    private fun run(skipBleWhenLanKnown: Boolean): Pair<Int, Int> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = TestDevice(idA, 1)
        val b = TestDevice(idB, 2)
        val g = a.device.createGroup("Trip", "EUR", "Ali")
        repeat(20) { a.device.append(g.groupId, "group.rename", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("N$it")) }) }
        b.device.join(parseInvite(g.invite())!!)

        val lanSessions = AtomicInteger()
        val bleSessions = AtomicInteger()
        // A connection that closes before the handshake is not a session: the central read `info` and left.
        fun counts(o: SessionOutcome) = !(o is SessionOutcome.Failed && o.reason in setOf("disconnected", "closed"))

        // B: a Wi-Fi listener that DNS-SD announces, and a BLE peripheral. B never dials.
        val listener = LanListener(b.device, b.admission, freePort(), onOutcome = { if (counts(it)) lanSessions.incrementAndGet() }, bindAddress = LOOPBACK)
        val bPort = listener.start(scope)
        val net = FakeNetwork()
        net.discovery().start(
            bPort, encodeTxt(idB, runBlocking { b.store.groups() }), instanceName(idB),
        ) {}
        val radio = FakeRadio(185)
        val peripheral = BlePeripheral(b.device, b.admission, scope, onOutcome = { if (counts(it)) bleSessions.incrementAndGet() })
        radio.sessionFactory = { peripheral.accept(radio.ServerConn()) }
        val bInfo = buildInfo(idB, b.store.groups(), canCentral = false)

        // A: a LAN director and a BLE director with one admission.
        val admission = SessionAdmission()
        val lan = LanDirector(a.device, net.discovery(), { null }, scope, preferredPort = freePort(), tickMs = 100, bindAddress = LOOPBACK, admission = admission)
        val scanner = object : BleScanner {
            override fun start(onFound: (String) -> Unit): Boolean { onFound("AA:BB:CC"); return true }
            override fun stop() {}
        }
        val host = object : BleHost {
            override fun start(info: ByteArray, peripheral: BlePeripheral) = true
            override fun stop() {}
        }
        val ble = BleDirector(
            a.device, scanner, host, { radio.ClientConn(bInfo) }, scope, tickMs = 50, admission = admission,
            skipPeer = { id -> skipBleWhenLanKnown && id in lan.knownPeerIds() },
        )
        lan.start()
        // Wi-Fi knows B before Bluetooth looks at it.
        withTimeout(10_000) { while (idB !in lan.knownPeerIds()) delay(20) }
        ble.start()

        withTimeout(30_000) { while (b.store.ops(g.groupId).size < 21) delay(50) }
        delay(1_500) // time for a second session to start, if one would
        lan.stop(); ble.stop(); listener.stop(); scope.cancel()
        lanSessions.get() to bleSessions.get()
    }

    @Test
    fun aPeerThatBothTransportsReachGetsOneSession() {
        val (lan, ble) = run(skipBleWhenLanKnown = true)
        assertEquals(1, lan, "Wi-Fi serves the peer")
        assertEquals(0, ble, "Bluetooth leaves it alone")
    }

    /** Negative control: without the rule, Bluetooth dials the peer too, so two sessions start. */
    @Test
    fun withoutTheRuleTwoSessionsStart() {
        val (lan, ble) = run(skipBleWhenLanKnown = false)
        assertTrue(lan + ble >= 2, "lan=$lan ble=$ble")
    }

    @Test
    fun theNearbyListShowsOneRowForEachPeer() {
        fun row(id: String, t: Transport, at: Long?, o: SessionOutcome? = null) =
            PeerStatus(id, "h", emptyList(), false, false, o, at, t)
        val failed = SessionOutcome.Failed("x", null)
        val merged = mergePeers(
            lan = listOf(row(idB, Transport.WIFI, 100)),
            ble = listOf(row(idB, Transport.BLUETOOTH, 300, failed), row("cccccccc-0000-4000-8000-000000000003", Transport.BLUETOOTH, 50)),
        )
        assertEquals(listOf(idB, "cccccccc-0000-4000-8000-000000000003"), merged.map { it.deviceId })
        assertEquals(Transport.WIFI, merged[0].transport, "Wi-Fi wins")
        assertEquals(failed, merged[0].lastOutcome, "the row shows the newer result")
        assertEquals(Transport.BLUETOOTH, merged[1].transport)
    }
}
