package com.olcayertas.tripsplit.core

import java.util.Random
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun seededIds(seed: Long): () -> String {
    val rng = Random(seed)
    return {
        val bytes = ByteArray(16).also { rng.nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hi = java.nio.ByteBuffer.wrap(bytes).long
        val lo = java.nio.ByteBuffer.wrap(bytes, 8, 8).long
        UUID(hi, lo).toString()
    }
}

class TestDevice(id: String, seed: Long, clockOffset: Long = 0) {
    var now = 1_761_210_000_000L + clockOffset
    val store = InMemoryOpStore()
    val device = Device(id, store, wall = { now }, newId = seededIds(seed))
    val admission = SessionAdmission()
}

/** Runs one session between two devices. Returns both results. */
private suspend fun sync(
    a: TestDevice, b: TestDevice, groupId: String, seed: Long, maxPiece: Int = 40,
): Pair<SessionResult, SessionResult> = kotlinx.coroutines.coroutineScope {
    val (ea, eb) = pipePair(seed, maxPiece)
    val ra = async { SyncSession(a.device, ea, Role.INITIATOR, groupId, admission = a.admission).run() }
    val rb = async { SyncSession(b.device, eb, Role.RESPONDER, admission = b.admission).run() }
    ra.await() to rb.await()
}

private fun foldOf(t: TestDevice, groupId: String) =
    runBlocking { fold(groupId, t.store.ops(groupId)).toJson() }

@Timeout(90)
class SyncEngineTest {
    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"

    @Test
    fun emptyDeviceJoinsAndDownloadsTheLog() = runBlocking {
        val a = TestDevice(idA, 1)
        val b = TestDevice(idB, 2)
        val g = a.device.createGroup("Kapadokya", "EUR", "Ali")
        a.device.append(
            g.groupId, "member.add",
            buildJsonObject { put("memberId", a.device.newId()); put("name", "Ayşe") },
        )
        b.device.join(parseInvite(g.invite())!!)
        assertTrue(b.store.ops(g.groupId).isEmpty())

        val (ra, rb) = sync(a, b, g.groupId, seed = 7)
        assertEquals(2, ra.sentOps)
        assertEquals(2, rb.receivedOps)
        assertEquals(idB, ra.peerDeviceId)
        assertEquals(foldOf(a, g.groupId), foldOf(b, g.groupId))
        assertEquals(2, b.store.ops(g.groupId).size)

        // A second session sends nothing: the diff is empty and the store skips nothing new.
        val (ra2, _) = sync(a, b, g.groupId, seed = 8)
        assertEquals(0, ra2.sentOps)
    }

    @Test
    fun hlcMergesSoLaterLocalOpsSortAfterReceivedOnes() = runBlocking {
        val a = TestDevice(idA, 1)
        val b = TestDevice(idB, 2)
        b.now -= 3_600_000 // B's clock runs one hour behind
        val g = a.device.createGroup("G", "EUR", "Ali")
        b.device.join(parseInvite(g.invite())!!)
        sync(a, b, g.groupId, 3)
        val op = b.device.append(g.groupId, "group.rename", buildJsonObject { put("name", "Renamed") })
        val last = a.store.ops(g.groupId).maxOf { strOrNull(it["hlc"])!! }
        assertTrue(strOrNull(op["hlc"])!! > last, "B's new op must sort after what it received")
    }

    @Test
    fun unknownGroupTagClosesTheResponder() = runBlocking {
        val a = TestDevice(idA, 1)
        val b = TestDevice(idB, 2)
        val ga = a.device.createGroup("G", "EUR", "Ali")
        b.device.createGroup("Other", "EUR", "Bo")
        b.store.addGroup(ga.groupId.replace('0', '1'), ByteArray(32) { 9 }) // another key under another id
        val (ea, eb) = pipePair(1)
        val ra = async { runCatching { SyncSession(a.device, ea, Role.INITIATOR, ga.groupId).run() } }
        val rb = async { runCatching { SyncSession(b.device, eb, Role.RESPONDER).run() } }
        assertTrue(rb.await().exceptionOrNull() is SessionException)
        assertTrue(ra.await().isFailure)
    }

    @Test
    fun badOpHashClosesTheSessionAndStoresNothing() = runBlocking {
        val a = TestDevice(idA, 1)
        val g = a.device.createGroup("G", "EUR", "Ali")
        val key = a.store.group(g.groupId)!!.key
        val (mine, theirs) = pipePair(5)
        val real = async { runCatching { SyncSession(a.device, theirs, Role.RESPONDER).run() } }

        // A scripted initiator: handshake, hello, then one op with a wrong hash.
        val salt = ByteArray(SALT_LEN) { 3 }
        mine.write(handshakeRecord(salt, discoveryTag(key)))
        val rec = parseHandshakeRecord(mine.readExactly(RECORD_LEN))!!
        val cipher = FrameCipher(deriveSessionKeys(key, g.groupId, salt, rec.salt))
        val evil = "cccccccc-0000-4000-8000-000000000003"
        mine.write(cipher.seal(encodeMessage(Hello(1, evil, "1", g.groupId, emptyMap(), a.now, 0))))
        val unsigned = buildJsonObject {
            put("v", 1); put("group", g.groupId); put("author", evil); put("seq", 1)
            put("hlc", hlcFormat(a.now, 0, evil)); put("type", "group.rename")
            put("body", buildJsonObject { put("name", "x") })
        }
        val badOp = JsonObject(unsigned + ("hash" to kotlinx.serialization.json.JsonPrimitive("0".repeat(64))))
        mine.write(cipher.seal(encodeMessage(OpsMessage(listOf(badOp)))))

        val outcome = real.await()
        val err = outcome.exceptionOrNull()
        assertTrue(err is SessionException && err.reason == "bad op hash", "got $err")
        assertEquals(1, a.store.ops(g.groupId).size, "only the creation op; the bad op is not stored")
    }

    @Test
    fun oversizedFrameClosesBeforeItAllocates() = runBlocking {
        val a = TestDevice(idA, 1)
        val g = a.device.createGroup("G", "EUR", "Ali")
        val key = a.store.group(g.groupId)!!.key
        val (mine, theirs) = pipePair(5)
        val real = async { runCatching { SyncSession(a.device, theirs, Role.RESPONDER).run() } }
        val salt = ByteArray(SALT_LEN) { 3 }
        mine.write(handshakeRecord(salt, discoveryTag(key)))
        mine.readExactly(RECORD_LEN)
        mine.write(byteArrayOf(0x7f, 0, 0, 0)) // length = 2 GiB, no body follows
        val err = real.await().exceptionOrNull()
        assertTrue(err is SessionException && err.reason == "frame too large", "got $err")
    }

    @Test
    fun forkIsRecordedAndTheLocalCopyKept() = runBlocking {
        val a = TestDevice(idA, 1)
        val g = a.device.createGroup("G", "EUR", "Ali")
        val own = a.store.ops(g.groupId).single()
        val forged = JsonObject(
            own.filterKeys { it != "hash" } + mapOf(
                "body" to buildJsonObject {
                    put("name", "Other"); put("baseCurrency", "EUR")
                    put("creatorMemberId", "10000000-0000-4000-8000-000000000001"); put("creatorName", "Ali")
                },
            ),
        )
        val signed = JsonObject(forged + ("hash" to kotlinx.serialization.json.JsonPrimitive(opHash(forged))))
        val r = a.device.receive(g.groupId, listOf(signed))
        assertEquals(1, r.forks)
        assertEquals(0, r.stored)
        assertEquals(own, a.store.ops(g.groupId).single())
        assertEquals(1, a.store.forks(g.groupId).size)
    }

    @Test
    fun admissionHigherIdClosesTheNewerConnection() {
        val adm = SessionAdmission()
        var closedOld = false
        val first = adm.register(adm.nextOrder(), idA, "g", idB) { closedOld = true }
        val second = adm.register(adm.nextOrder(), idA, "g", idB) { error("the asking session closes itself") }
        // Local id B is higher than peer id A: B closes the newer connection. Here the newer one asks.
        assertFalse(adm.admit(second))
        assertFalse(closedOld)
        assertTrue(adm.admit(first))
    }

    @Test
    fun admissionLowerIdDoesNotClose() {
        val adm = SessionAdmission()
        val first = adm.register(adm.nextOrder(), idB, "g", idA) { error("must not close") }
        val second = adm.register(adm.nextOrder(), idB, "g", idA) { error("must not close") }
        assertTrue(adm.admit(first))
        assertTrue(adm.admit(second))
    }

    @Test
    fun admissionClosesAnotherSessionWhenItIsTheNewest() {
        val adm = SessionAdmission()
        var closedNew = false
        val first = adm.register(adm.nextOrder(), idA, "g", idB) { error("the old one must stay") }
        adm.register(adm.nextOrder(), idA, "g", idB) { closedNew = true }
        // The older session asks late. The newer one is closed through its closer.
        assertTrue(adm.admit(first))
        assertTrue(closedNew)
    }

    @Test
    fun admissionKeysAreSeparatePerPeerAndGroup() {
        val adm = SessionAdmission()
        val one = adm.register(adm.nextOrder(), idA, "g1", idB) { error("no") }
        val two = adm.register(adm.nextOrder(), idA, "g2", idB) { error("no") }
        assertTrue(adm.admit(one))
        assertTrue(adm.admit(two))
    }

    private fun randomBody(rng: Random, t: TestDevice, groupId: String, members: MutableList<String>): Pair<String, JsonObject> =
        when (val k = rng.nextInt(4)) {
            0 -> {
                val id = t.device.newId(); members.add(id)
                "member.add" to buildJsonObject { put("memberId", id); put("name", "M${rng.nextInt(1000)}") }
            }
            1 -> "group.rename" to buildJsonObject { put("name", "G${rng.nextInt(1000)}") }
            2 -> if (members.isEmpty()) {
                "group.rename" to buildJsonObject { put("name", "R${rng.nextInt(1000)}") }
            } else {
                "member.rename" to buildJsonObject { put("memberId", members[rng.nextInt(members.size)]); put("name", "N${rng.nextInt(1000)}") }
            }
            else -> if (members.isEmpty()) {
                "group.rename" to buildJsonObject { put("name", "S${rng.nextInt(1000)}") }
            } else {
                val who = members[rng.nextInt(members.size)]
                "expense.add" to buildJsonObject {
                    put("expenseId", t.device.newId()); put("title", "E$k"); put("date", "2026-10-23")
                    put("category", "food"); put("paidBy", who)
                    put("money", buildJsonObject {
                        put("amountMinor", 1 + rng.nextInt(100000)); put("currency", "EUR"); put("rate", "1")
                        put("split", buildJsonObject { put("mode", "equal"); put("members", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(who)))) })
                    })
                }
            }
        }

    /** N devices write random ops offline, sync in random pairs with random chunking, and converge. */
    @Test
    fun fuzzConvergence() = runBlocking {
        for (seed in 1L..12L) {
            val rng = Random(seed)
            val n = 3 + rng.nextInt(3)
            val ids = List(n) { UUID(rng.nextLong(), rng.nextLong()).toString().let { s -> s.substring(0, 14) + "4" + s.substring(15) } }
            val devices = ids.mapIndexed { i, id -> TestDevice(id, seed * 100 + i, clockOffset = (rng.nextInt(2_000_000) - 1_000_000).toLong()) }
            val g = devices[0].device.createGroup("G", "EUR", "Ali")
            val inv = parseInvite(g.invite())!!
            devices.drop(1).forEach { it.device.join(inv) }
            val members = mutableListOf(g.creatorMemberId)

            repeat(4) { round ->
                for (d in devices) {
                    d.now += 1000L * rng.nextInt(50)
                    repeat(rng.nextInt(12)) {
                        val (type, body) = randomBody(rng, d, g.groupId, members)
                        d.device.append(g.groupId, type, body)
                    }
                }
                // A random subset of pairs syncs; others stay apart this round.
                val pairs = (0 until n).flatMap { i -> (i + 1 until n).map { i to it } }.shuffled(rng).take(1 + rng.nextInt(pairs(n)))
                for ((i, j) in pairs) {
                    val (a, b) = if (rng.nextBoolean()) devices[i] to devices[j] else devices[j] to devices[i]
                    sync(a, b, g.groupId, seed * 1000 + round, maxPiece = 1 + rng.nextInt(300))
                }
            }
            // Final gossip: full rounds of all pairs until nothing moves. n rounds always suffice.
            repeat(n) {
                for (i in 0 until n) for (j in i + 1 until n) {
                    sync(devices[i], devices[j], g.groupId, seed * 7 + i * 10 + j, maxPiece = 1 + rng.nextInt(500))
                }
            }
            val ref = foldOf(devices[0], g.groupId)
            val refOps = devices[0].store.ops(g.groupId).map { opRef(it) }.sorted()
            for (d in devices.drop(1)) {
                assertEquals(refOps, d.store.ops(g.groupId).map { opRef(it) }.sorted(), "ops, seed $seed")
                assertEquals(ref, foldOf(d, g.groupId), "fold, seed $seed")
            }
            for (d in devices) assertTrue(d.store.forks(g.groupId).isEmpty())
        }
    }

    private fun pairs(n: Int) = n * (n - 1) / 2
}
