package com.olcayertas.tripsplit.core

import com.olcayertas.tripsplit.core.ble.BleCentralConnection
import com.olcayertas.tripsplit.core.ble.BleCentralRunner
import com.olcayertas.tripsplit.core.ble.BleDirector
import com.olcayertas.tripsplit.core.ble.BleHost
import com.olcayertas.tripsplit.core.ble.BlePeripheral
import com.olcayertas.tripsplit.core.ble.BlePeripheralConnection
import com.olcayertas.tripsplit.core.ble.BleScanner
import com.olcayertas.tripsplit.core.ble.BleServerSession
import com.olcayertas.tripsplit.core.ble.CentralDecision
import com.olcayertas.tripsplit.core.ble.CentralResult
import com.olcayertas.tripsplit.core.ble.CentralSetup
import com.olcayertas.tripsplit.core.ble.buildInfo
import com.olcayertas.tripsplit.core.ble.centralDecision
import com.olcayertas.tripsplit.core.ble.chunkSizeForMtu
import com.olcayertas.tripsplit.core.ble.parseInfo
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import java.io.IOException
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One simulated BLE radio between a central and a peripheral at a given ATT MTU. It records every chunk. */
class FakeRadio(val mtu: Int) {
    val toPeripheral = CopyOnWriteArrayList<ByteArray>() // writes to rx
    val toCentral = CopyOnWriteArrayList<ByteArray>() // notifications on tx
    @Volatile var session: BleServerSession? = null
    @Volatile var centralSink: (ByteArray) -> Unit = {}
    @Volatile var notificationsOn = false
    @Volatile var abortSeen = false
    @Volatile var peripheralDropped = false
    @Volatile var centralDisconnected = false

    inner class ServerConn : BlePeripheralConnection {
        override val txLimit: Int get() = chunkSizeForMtu(mtu)
        override suspend fun send(chunk: ByteArray) {
            if (!notificationsOn) throw IOException("notify before the central enabled notifications")
            if (chunk.size > mtu - 3) throw IOException("chunk ${chunk.size} exceeds MTU-3")
            toCentral.add(chunk)
            if (chunk[0].toInt() == 4) abortSeen = true
            centralSink(chunk.copyOf())
        }
        override fun disconnect() { peripheralDropped = true }
    }

    inner class ClientConn(private val info: ByteArray) : BleCentralConnection {
        override var chunkSink: (ByteArray) -> Unit
            get() = centralSink
            set(v) { centralSink = v }
        override var disconnectSink: () -> Unit = {}
        override suspend fun connect(address: String) {}
        override suspend fun setup(): CentralSetup { notificationsOn = true; return CentralSetup(info, mtu) }
        override suspend fun send(chunk: ByteArray) {
            if (chunk.size > mtu - 3) throw IOException("chunk ${chunk.size} exceeds MTU-3")
            toPeripheral.add(chunk)
            session!!.onChunk(chunk.copyOf())
        }
        override fun disconnect() {
            centralDisconnected = true
            session?.onDisconnected()
        }
    }
}

@Timeout(120)
class BleTest {
    private val idLow = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idHigh = "bbbbbbbb-0000-4000-8000-000000000002"

    private fun manyOps(t: TestDevice, groupId: String, n: Int) = runBlocking {
        repeat(n) { t.device.append(groupId, "group.rename", buildJsonObject { put("name", "N$it") }) }
    }

    /** The central [c] dials the peripheral [p] through a [FakeRadio]. */
    private suspend fun connectOnce(c: TestDevice, p: TestDevice, mtu: Int, scope: CoroutineScope, radio: FakeRadio = FakeRadio(mtu)): Pair<CentralResult, FakeRadio> {
        val peripheral = BlePeripheral(p.device, p.admission, scope)
        val info = buildInfo(p.device.deviceId, p.store.groups(), canCentral = false)
        radio.session = peripheral.accept(radio.ServerConn())
        val runner = BleCentralRunner(c.device, c.admission, scope, { radio.ClientConn(info) })
        return runner.run("AA:BB") to radio
    }

    @Test
    fun emptyDeviceJoinsA500OpGroupAtEveryMtu() = runBlocking {
        for (mtu in listOf(23, 185, 517)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val owner = TestDevice(idHigh, 1)
            val joiner = TestDevice(idLow, 2)
            val g = owner.device.createGroup("Big", "EUR", "Ali")
            manyOps(owner, g.groupId, 499)
            joiner.device.join(parseInvite(g.invite())!!)
            val (result, radio) = connectOnce(joiner, owner, mtu, scope)
            val synced = assertIs<CentralResult.Synced>(result, result.toString())
            val done = assertIs<SessionOutcome.Done>(synced.outcomes.single(), synced.outcomes.toString())
            assertEquals(500, done.result.receivedOps, "mtu $mtu")
            assertEquals(500, joiner.store.ops(g.groupId).size)
            assertEquals(
                fold(g.groupId, owner.store.ops(g.groupId)).toJson(), fold(g.groupId, joiner.store.ops(g.groupId)).toJson(),
            )
            val limit = chunkSizeForMtu(mtu)
            assertTrue(radio.toCentral.all { it.size <= limit } && radio.toPeripheral.all { it.size <= limit })
            // Until the hello of the central is read, the peripheral uses chunks of 20 bytes (SPEC §10.2.5).
            assertEquals(20, radio.toCentral.first().size)
            if (mtu > 23) assertTrue(radio.toCentral.maxOf { it.size } > 20, "later chunks use the larger size")
            assertTrue(radio.centralDisconnected)
            scope.cancel()
        }
    }

    @Test
    fun bothSidesSendTheirOwnOps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = TestDevice(idHigh, 1)
        val b = TestDevice(idLow, 2)
        val g = a.device.createGroup("Two", "EUR", "Ali")
        b.device.join(parseInvite(g.invite())!!)
        connectOnce(b, a, 185, scope)
        manyOps(a, g.groupId, 10)
        manyOps(b, g.groupId, 12)
        val (result, _) = connectOnce(b, a, 185, scope)
        assertIs<CentralResult.Synced>(result)
        assertEquals(23, a.store.ops(g.groupId).size)
        assertEquals(fold(g.groupId, a.store.ops(g.groupId)).toJson(), fold(g.groupId, b.store.ops(g.groupId)).toJson())
        scope.cancel()
    }

    @Test
    fun noSharedTagMeansNoSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = TestDevice(idHigh, 1)
        val b = TestDevice(idLow, 2)
        a.device.createGroup("A", "EUR", "Ali")
        b.device.createGroup("B", "EUR", "Bo")
        val (result, radio) = connectOnce(b, a, 185, scope)
        val skipped = assertIs<CentralResult.Skipped>(result)
        assertEquals(CentralDecision.NoSharedTag, skipped.decision)
        assertTrue(radio.toPeripheral.isEmpty(), "no record is written")
        assertTrue(radio.centralDisconnected)
        scope.cancel()
    }

    @Test
    fun theHigherIdYieldsWhenThePeerCanBeCentral() {
        val g = runBlocking { TestDevice(idLow, 1).also { it.device.createGroup("G", "EUR", "x") }.store.groups() }
        val info = parseInfo(runBlocking { buildInfo(idLow, g, canCentral = true) })!!
        assertEquals(CentralDecision.YieldToPeer, centralDecision(idHigh, g, info))
        assertIs<CentralDecision.Go>(centralDecision(idHigh, g, info, forceCentral = true))
        assertIs<CentralDecision.Go>(centralDecision("00000000-0000-4000-8000-000000000000", g, info))
        // A peripheral-only peer cannot be central: the higher id goes ahead.
        val peripheralOnly = parseInfo(runBlocking { buildInfo(idLow, g, canCentral = false) })!!
        assertIs<CentralDecision.Go>(centralDecision(idHigh, g, peripheralOnly))
        assertEquals(CentralDecision.NoSharedTag, centralDecision(idHigh, emptyList(), info))
    }

    @Test
    fun aBadChunkSequenceClosesThePeripheralWithAnAbort() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = TestDevice(idHigh, 1)
        a.device.createGroup("G", "EUR", "Ali")
        val radio = FakeRadio(185)
        radio.notificationsOn = true
        val session = BlePeripheral(a.device, a.admission, scope).accept(radio.ServerConn())
        radio.session = session
        session.onChunk(byteArrayOf(3, 0, 5) + ByteArray(31)) // seq 5 instead of 0
        val outcome = withTimeout(10_000) { session.outcome.await() }
        val failed = assertIs<SessionOutcome.Failed>(outcome)
        assertTrue(failed.reason.isNotEmpty())
        withTimeout(5_000) { while (!radio.abortSeen || !radio.peripheralDropped) delay(20) }
        scope.cancel()
    }

    @Test
    fun aPeripheralDoesNotNotifyBeforeNotificationsAreOn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = TestDevice(idHigh, 1)
        val g = a.device.createGroup("G", "EUR", "Ali")
        val radio = FakeRadio(185)
        // notificationsOn stays false: the fake radio rejects the send, so the session must fail, not hang.
        val session = BlePeripheral(a.device, a.admission, scope).accept(radio.ServerConn())
        radio.session = session
        val key = a.store.group(g.groupId)!!.key
        val record = handshakeRecord(ByteArray(SALT_LEN) { 1 }, discoveryTag(key))
        for (c in com.olcayertas.tripsplit.core.ble.chunkMessage(record, 20)) session.onChunk(c)
        val outcome = withTimeout(10_000) { session.outcome.await() }
        assertIs<SessionOutcome.Failed>(outcome)
        assertTrue(radio.toCentral.isEmpty())
        scope.cancel()
    }

    @Test
    fun directorScansAndSyncsWithAnAdvertisingPeer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = TestDevice(idHigh, 1)
        val joiner = TestDevice(idLow, 2)
        val g = owner.device.createGroup("Dir", "EUR", "Ali")
        manyOps(owner, g.groupId, 40)
        joiner.device.join(parseInvite(g.invite())!!)
        val radio = FakeRadio(185)
        val ownerPeripheral = BlePeripheral(owner.device, owner.admission, scope)
        val info = buildInfo(owner.device.deviceId, owner.store.groups(), canCentral = false)
        radio.session = ownerPeripheral.accept(radio.ServerConn())
        val scanner = object : BleScanner {
            override fun start(onFound: (String) -> Unit): Boolean { onFound("AA:BB:CC"); return true }
            override fun stop() {}
        }
        val host = object : BleHost {
            override fun start(info: ByteArray, peripheral: BlePeripheral) = true
            override fun stop() {}
        }
        val d = BleDirector(joiner.device, scanner, host, { radio.ClientConn(info) }, scope, tickMs = 50)
        d.start()
        withTimeout(30_000) { while (joiner.store.ops(g.groupId).size < 41) delay(50) }
        withTimeout(10_000) { while (d.state.value.peers.none { it.lastOutcome is SessionOutcome.Done }) delay(50) }
        assertTrue(d.canScanNow && d.canServeNow)
        d.stop()
        scope.cancel()
    }
}
