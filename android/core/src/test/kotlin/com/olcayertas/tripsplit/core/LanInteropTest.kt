package com.olcayertas.tripsplit.core

import com.olcayertas.tripsplit.core.lan.LanConnector
import com.olcayertas.tripsplit.core.lan.LanListener
import com.olcayertas.tripsplit.core.lan.SessionOutcome
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real TCP against the Python reference peer (protocol/tools/peer.py, SPEC §10.1). Skipped when python3 is
 * missing. The peer stores its log as JSON when it ends, so the test compares op sets at the end.
 */
@Timeout(180)
class LanInteropTest {
    private val protocol = File(requireNotNull(System.getProperty("vectors.dir"))).parentFile
    private val peerPy = File(protocol, "tools/peer.py")
    private val tmp: File = Files.createTempDirectory("tripsplit-lan").toFile()

    private val py: String = System.getenv("TRIPSPLIT_PYTHON") ?: "python3"

    private fun python(vararg args: String): ProcessBuilder =
        ProcessBuilder(listOf(py, peerPy.path) + args).directory(tmp)

    /** The peer needs `cryptography`: pip install -r protocol/reference/requirements.txt. */
    private fun pythonAvailable(): Boolean = try {
        val p = ProcessBuilder(py, "-c", "import cryptography").redirectErrorStream(true).start()
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private fun freePort() = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun peerOps(log: File): Map<String, String> =
        Json.parseToJsonElement(log.readText()).jsonObject.getValue("ops").jsonArray.associate {
            val o = it.jsonObject
            opRef(o) to o.getValue("hash").toString().trim('"')
        }

    private fun myOps(t: TestDevice, groupId: String): Map<String, String> = runBlocking {
        t.store.ops(groupId).associate { opRef(it) to it.getValue("hash").toString().trim('"') }
    }

    private fun makeGroup(log: File): String {
        val p = python("--make-group", "Interop", "--log", log.path).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(0, p.waitFor())
        return out
    }

    @Test
    fun androidDialsTheReferencePeerAndBothSidesConverge() = runBlocking {
        assumeTrue(pythonAvailable(), "python3 with the cryptography package is missing")
        val log = File(tmp, "serve.json")
        val invite = makeGroup(log)
        val port = freePort()
        val err = File(tmp, "serve.err")
        val proc = python(
            "serve", "--log", log.path, "--random-ops", "30", "--seed", "7", "--no-mdns", "--port", port.toString(),
            "--timeout", "60",
        ).redirectError(err).redirectOutput(File(tmp, "serve.out")).start()
        try {
            withTimeout(30_000) { while (!err.exists() || !err.readText().contains("listening on port")) kotlinx.coroutines.delay(50) }
            val me = TestDevice("aaaaaaaa-0000-4000-8000-000000000001", 1)
            me.device.join(parseInvite(invite)!!)
            val gid = parseInvite(invite)!!.groupId
            // The Android side already holds ops of its own: both directions carry data. It adopts the
            // group first, so these ops are in the same group.
            me.device.append(gid, "member.add", buildJsonObject { put("memberId", me.device.newId()); put("name", "Ayşe") })
            me.device.append(gid, "group.rename", buildJsonObject { put("name", "From Android") })
            val outcome = LanConnector(me.device, me.admission).connect("127.0.0.1", port, gid)
            val done = assertIs<SessionOutcome.Done>(outcome, outcome.toString())
            assertEquals(2, done.result.sentOps)
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "peer did not finish")
            assertEquals(0, proc.exitValue(), err.readText())
            assertEquals(peerOps(log).size - 2, done.result.receivedOps)
            assertTrue(done.result.receivedOps >= 30)
            assertEquals(peerOps(log), myOps(me, gid))
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun referencePeerDialsAndroid() = runBlocking {
        assumeTrue(pythonAvailable(), "python3 with the cryptography package is missing")
        val log = File(tmp, "client.json")
        val invite = makeGroup(File(tmp, "owner.json"))
        val me = TestDevice("bbbbbbbb-0000-4000-8000-000000000002", 2)
        val inv = parseInvite(invite)!!
        me.device.join(inv)
        me.device.append(inv.groupId, "group.rename", buildJsonObject { put("name", "Android first") })
        val seen = CompletableDeferred<SessionOutcome>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = LanListener(me.device, me.admission, preferredPort = freePort(), onOutcome = { seen.complete(it) }, bindAddress = java.net.InetAddress.getLoopbackAddress())
        val port = listener.start(scope)
        try {
            val err = File(tmp, "client.err")
            val proc = python(
                "connect", "127.0.0.1:$port", "--log", log.path, "--invite", invite, "--random-ops", "25", "--seed", "3",
            ).redirectError(err).redirectOutput(File(tmp, "client.out")).start()
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "peer did not finish")
            assertEquals(0, proc.exitValue(), err.readText())
            val done = assertIs<SessionOutcome.Done>(withTimeout(30_000) { seen.await() })
            assertEquals(peerOps(log).size - 1, done.result.receivedOps) // all but the one Android op
            assertTrue(done.result.receivedOps >= 25)
            assertEquals(peerOps(log), myOps(me, inv.groupId))
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    @Test
    fun aWrongKeyIsRefused() = runBlocking {
        assumeTrue(pythonAvailable(), "python3 with the cryptography package is missing")
        val log = File(tmp, "secret.json")
        val invite = makeGroup(log)
        val port = freePort()
        val err = File(tmp, "secret.err")
        val proc = python("serve", "--log", log.path, "--no-mdns", "--port", port.toString(), "--timeout", "20")
            .redirectError(err).redirectOutput(File(tmp, "secret.out")).start()
        try {
            withTimeout(30_000) { while (!err.exists() || !err.readText().contains("listening on port")) kotlinx.coroutines.delay(50) }
            val me = TestDevice("aaaaaaaa-0000-4000-8000-000000000001", 1)
            val gid = parseInvite(invite)!!.groupId
            me.store.addGroup(gid, ByteArray(32) { 1 }) // right id, wrong key
            val outcome = LanConnector(me.device, me.admission).connect("127.0.0.1", port, gid)
            assertIs<SessionOutcome.Failed>(outcome)
            assertEquals(0, me.store.ops(gid).size)
        } finally {
            proc.destroyForcibly()
        }
    }
}
