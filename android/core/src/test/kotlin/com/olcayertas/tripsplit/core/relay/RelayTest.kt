package com.olcayertas.tripsplit.core.relay

import com.olcayertas.tripsplit.core.InMemoryOpStore
import com.olcayertas.tripsplit.core.OpBodies
import com.olcayertas.tripsplit.core.RelayState
import com.olcayertas.tripsplit.core.TestDevice
import com.olcayertas.tripsplit.core.canonical
import com.olcayertas.tripsplit.core.fold
import com.olcayertas.tripsplit.core.fromHex
import com.olcayertas.tripsplit.core.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEY = ByteArray(32) { it.toByte() }
private const val GID = "11111111-2222-4333-8444-555555555555"
private const val IDA = "aaaaaaaa-0000-4000-8000-000000000001"
private const val IDB = "bbbbbbbb-0000-4000-8000-000000000002"

@Timeout(60)
class RelayCryptoTest {
    // Values from Python hmac and cryptography, not from this code.
    private val mailbox = "c0f8baf39df185762f4d897092500523"

    @Test
    fun namesMatchTheReferenceValues() {
        assertEquals(mailbox, mailboxId(KEY))
        assertEquals("738c3d668ffab68b4f05720df9db1dfda10c4bb9e7630ab707a1ae83516acdad", relayToken(KEY))
        assertEquals("e2fe7c27052dccd4df1990e58f7e05db6f6a99f21c4d6941c9c4f0c8fde18b6a", toHex(batchKey(KEY, GID)))
    }

    @Test
    fun sealWithFixedNonceMatchesTheReference() {
        val blob = sealBatch(batchKey(KEY, GID), mailbox, emptyList(), ByteArray(12) { it.toByte() })
        assertEquals("000102030405060708090a0bb41bbdedcdcbe0e29754403ea9ed62336e1a392c5bf8554e307d", toHex(blob))
        assertEquals(0, openBatch(batchKey(KEY, GID), mailbox, blob)!!.size)
    }

    @Test
    fun badBlobsOpenAsNull() {
        val key = batchKey(KEY, GID)
        val blob = sealBatch(key, mailbox, emptyList())
        val flipped = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(openBatch(key, mailbox, flipped))
        assertNull(openBatch(key, mailbox, blob.copyOfRange(0, 20)))
        // The mailbox id is the AAD: the same blob in another mailbox does not open.
        assertNull(openBatch(key, "0".repeat(32), blob))
        assertNull(openBatch(batchKey(KEY, "other"), mailbox, blob))
    }

    @Test
    fun plaintextThatIsNotAnOpsObjectOpensAsNull() {
        val key = batchKey(KEY, GID)
        fun raw(plain: String): ByteArray {
            val nonce = ByteArray(12)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            c.updateAAD(mailbox.toByteArray())
            return nonce + c.doFinal(plain.toByteArray())
        }
        assertNull(openBatch(key, mailbox, raw("not json")))
        assertNull(openBatch(key, mailbox, raw("[1]")))
        assertNull(openBatch(key, mailbox, raw("""{"ops":3}""")))
        assertNotNull(openBatch(key, mailbox, raw("""{"ops":[]}""")))
    }

    @Test
    fun configuredOnlyForARealHost() {
        assertFalse(isRelayConfigured(RELAY_PLACEHOLDER_URL))
        assertFalse(isRelayConfigured("not a url"))
        assertTrue(isRelayConfigured("https://relay.example.com"))
    }
}

@Timeout(60)
class RelayBatchingTest {
    private fun op(author: String, seq: Long, pad: Int = 0) = buildJsonObject {
        put("author", author); put("seq", seq); put("pad", "x".repeat(pad))
    }

    @Test
    fun splitsAtSixtyFourOps() {
        val ops = (1L..130L).map { op(IDA, it) }
        val batches = splitIntoBatches(ops)
        assertEquals(listOf(64, 64, 2), batches.map { it.size })
        assertEquals(ops, batches.flatten())
    }

    @Test
    fun splitsAtTheSizeLimitAndKeepsEveryBatchUnderIt() {
        val ops = (1L..20L).map { op(IDA, it, pad = 100 * 1024) }
        val batches = splitIntoBatches(ops)
        assertTrue(batches.size > 1)
        for (b in batches) assertTrue(batchPlaintextSize(b) <= MAX_BATCH_PLAINTEXT)
        assertEquals(ops, batches.flatten())
    }

    @Test
    fun noOpsMakeNoBatch() = assertTrue(splitIntoBatches(emptyList()).isEmpty())
}

@Timeout(60)
class RelayBackoffTest {
    @Test
    fun growsFromFiveSecondsToFiveMinutesAndResets() {
        val b = RelayBackoff()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), (1..8).map { b.failure() })
        b.success()
        assertEquals(5_000L, b.failure())
    }
}

@Timeout(90)
class RelaySyncTest {
    private fun members(t: TestDevice, n: Int, from: Int = 0) = runBlocking {
        for (i in from until from + n) {
            t.device.append(GID, "member.add", OpBodies.memberAdd("00000000-0000-4000-8000-%012d".format(i), "M$i"))
        }
    }

    private fun pair(): Pair<TestDevice, TestDevice> {
        val a = TestDevice(IDA, 1)
        val b = TestDevice(IDB, 2)
        runBlocking {
            a.store.addGroup(GID, KEY)
            b.store.addGroup(GID, KEY)
            a.device.append(GID, "group.create", buildJsonObject {
                put("name", "Trip"); put("baseCurrency", "EUR")
                put("creatorMemberId", "00000000-0000-4000-8000-0000000000aa"); put("creatorName", "Ali")
            })
        }
        return a to b
    }

    private fun hashes(t: TestDevice) = runBlocking { t.store.ops(GID).map { (it["hash"] as JsonPrimitive).content }.sorted() }

    @Test
    fun twoDevicesConvergeThroughTheRelay() = runBlocking {
        val relay = FakeRelay()
        val (a, b) = pair()
        members(a, 3)
        RelaySync(a.device, relay).sync(GID)
        members(b, 2, from = 10)
        RelaySync(b.device, relay).sync(GID)
        RelaySync(a.device, relay).sync(GID)
        assertEquals(hashes(a), hashes(b))
        assertEquals(1 + 3 + 2, hashes(a).size)
        assertEquals(
            canonical(fold(GID, a.store.ops(GID)).toJson()),
            canonical(fold(GID, b.store.ops(GID)).toJson()),
        )
    }

    // The negative control: a relay that flips a bit gives the second device nothing, so the check above fails.
    @Test
    fun negativeControlATamperingRelayLeavesTheDevicesApart() = runBlocking {
        val relay = FakeRelay(tamper = { it.copyOf().also { x -> x[x.size - 1] = (x[x.size - 1].toInt() xor 1).toByte() } })
        val (a, b) = pair()
        members(a, 3)
        RelaySync(a.device, relay).sync(GID)
        RelaySync(b.device, relay).sync(GID)
        assertEquals(0, hashes(b).size)
        assertTrue(hashes(a) != hashes(b))
        // The cursor still moved: one bad blob does not block the next pull.
        assertEquals(relay.blobCount(mailboxId(KEY)).toLong(), b.store.relayState(GID).cursor)
    }

    @Test
    fun aSecondSyncPushesNothingAndADeviceDoesNotPushWhatTheRelayHas() = runBlocking {
        val relay = FakeRelay()
        val (a, b) = pair()
        members(a, 3)
        val first = RelaySync(a.device, relay).sync(GID)
        assertEquals(4, first.pushedOps)
        val again = RelaySync(a.device, relay).sync(GID)
        assertEquals(0, again.pushedOps)
        // B pulls first, so it has nothing left to push (SPEC §10.4.4 step 3).
        val second = RelaySync(b.device, relay).sync(GID)
        assertEquals(4, second.storedOps)
        assertEquals(0, second.pushedOps)
        assertEquals(1, relay.blobCount(mailboxId(KEY)))
    }

    @Test
    fun pullReadsEveryPageUntilMoreIsFalse() = runBlocking {
        val relay = FakeRelay()
        val (a, b) = pair()
        // 130 ops make 3 batches. A page limit of 2 needs two pages.
        members(a, 129)
        RelaySync(a.device, relay).sync(GID)
        assertEquals(3, relay.blobCount(mailboxId(KEY)))
        val r = RelaySync(b.device, relay, pageLimit = 2).sync(GID)
        assertEquals(130, r.storedOps)
        assertEquals(hashes(a), hashes(b))
    }

    @Test
    fun aBatchWithABadHashIsSkippedAndTheNextBatchStillCounts() = runBlocking {
        val relay = FakeRelay()
        val (a, b) = pair()
        members(a, 2)
        val good = a.store.ops(GID)
        val mailbox = mailboxId(KEY)
        val key = batchKey(KEY, GID)
        val forged = JsonObject(good.last() + ("body" to buildJsonObject { put("memberId", "x"); put("name", "Forged") }))
        relay.push(mailbox, relayToken(KEY), sealBatch(key, mailbox, listOf(forged)))
        relay.push(mailbox, relayToken(KEY), sealBatch(key, mailbox, good))
        val r = RelaySync(b.device, relay).sync(GID)
        assertEquals(3, r.storedOps)
        assertEquals(2L, b.store.relayState(GID).cursor)
        assertEquals(hashes(a), hashes(b))
    }

    @Test
    fun aFailedPushLeavesTheVectorAndTheNextSyncPushesAgain() = runBlocking {
        val inner = FakeRelay()
        val (a, _) = pair()
        members(a, 2)
        val flaky = object : RelayApi by inner {
            var calls = 0
            override suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted {
                if (calls++ == 0) throw RelayException(RelayFailure.RETRY, "down")
                return inner.push(mailboxId, token, blob)
            }
        }
        val sync = RelaySync(a.device, flaky)
        assertFailsWith<RelayException> { sync.sync(GID) }
        assertEquals(emptyMap(), a.store.relayState(GID).vector)
        assertEquals(3, sync.sync(GID).pushedOps)
        assertEquals(1, inner.blobCount(mailboxId(KEY)))
    }

    @Test
    fun aMailboxMadeAgainDuringThePushRunsTheWholeSyncOnceMore() = runBlocking {
        val inner = FakeRelay()
        val (a, _) = pair()
        members(a, 2)
        RelaySync(a.device, inner).sync(GID)
        val first = a.store.relayState(GID).epoch
        a.device.append(GID, "member.add", OpBodies.memberAdd("00000000-0000-4000-8000-0000000000ff", "Late"))
        // The relay deletes the mailbox right after the pull. The push then makes it again with a new epoch.
        val expiring = object : RelayApi by inner {
            var done = false
            override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage =
                inner.pull(mailboxId, token, after, limit).also { if (!done) { done = true; inner.expire(mailboxId) } }
        }
        val r = RelaySync(a.device, expiring).sync(GID)
        val state = a.store.relayState(GID)
        assertTrue(first != state.epoch)
        // First pass sent the one new op (seq 4) into the empty mailbox. A vector does not jump over a gap, so the
        // second pass read that op back without a vector entry, and sent all four ops. The relay ignores nothing:
        // the op store of the other devices skips the copy.
        assertEquals(5, r.pushedOps)
        assertEquals(2, inner.blobCount(mailboxId(KEY)))
        assertEquals(mapOf(IDA to 4L), state.vector)
    }

    @Test
    fun theEpochIsKeptAcrossPullsAndNotSetByAnUnknownMailbox() = runBlocking {
        val relay = FakeRelay()
        val (a, b) = pair()
        RelaySync(b.device, relay).sync(GID)
        assertNull(b.store.relayState(GID).epoch)
        RelaySync(a.device, relay).sync(GID)
        RelaySync(b.device, relay).sync(GID)
        assertEquals(a.store.relayState(GID).epoch, b.store.relayState(GID).epoch)
        assertNotNull(b.store.relayState(GID).epoch)
    }

    @Test
    fun aWrongTokenIsForbidden() = runBlocking {
        val relay = FakeRelay()
        val (a, _) = pair()
        members(a, 1)
        RelaySync(a.device, relay).sync(GID)
        val e = assertFailsWith<RelayException> { relay.push(mailboxId(KEY), "0".repeat(64), ByteArray(40)) }
        assertEquals(RelayFailure.FORBIDDEN, e.failure)
    }

    @Test
    fun receiveFromRelaySavesTheCursorWithTheOps() = runBlocking {
        val (a, b) = pair()
        members(a, 1)
        val ops = a.store.ops(GID)
        b.device.receiveFromRelay(GID, ops, 7)
        val state = b.store.relayState(GID)
        assertEquals(7, state.cursor)
        assertEquals(IDA to 2L, state.vector.entries.single().let { it.key to it.value })
        // The same blob again changes nothing, and the cursor never goes back.
        b.device.receiveFromRelay(GID, ops, 3)
        assertEquals(RelayState(7, state.vector), b.store.relayState(GID))
    }
}

@Timeout(60)
class RelayCoordinatorTest {
    private class Spy : RelayApi {
        var failure: RelayFailure? = null
        var pulls = 0
        override suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted = RelayPosted(1, "e")
        override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage {
            pulls++
            failure?.let { throw RelayException(it, "x") }
            return RelayPage(emptyList(), false, null)
        }
    }

    private fun setup(on: Boolean = true, online: Boolean = true): Triple<Spy, RelayCoordinator, LongArray> {
        val spy = Spy()
        val t = TestDevice(IDA, 1)
        runBlocking { t.store.addGroup(GID, KEY) }
        val clock = longArrayOf(1_000_000)
        val c = RelayCoordinator(RelaySync(t.device, spy), { on }, { online }, { clock[0] })
        return Triple(spy, c, clock)
    }

    @Test
    fun doesNothingWhenOffOrOffline() = runBlocking {
        val (spyOff, off, _) = setup(on = false)
        assertFalse(off.syncAll(listOf(GID)))
        assertEquals(0, spyOff.pulls)
        val (spyNet, net, _) = setup(online = false)
        assertFalse(net.syncAll(listOf(GID)))
        assertEquals(0, spyNet.pulls)
    }

    @Test
    fun waitsAfterARetryFailureAndTriesAgainLater() = runBlocking {
        val (spy, c, clock) = setup()
        spy.failure = RelayFailure.RETRY
        assertFalse(c.syncAll(listOf(GID)))
        assertEquals(1, spy.pulls)
        clock[0] += 4_999
        c.syncAll(listOf(GID))
        assertEquals(1, spy.pulls)
        clock[0] += 1
        spy.failure = null
        assertTrue(c.syncAll(listOf(GID)))
        assertEquals(2, spy.pulls)
    }

    @Test
    fun aForbiddenGroupDoesNotStopTheOthersAndDoesNotBackOff() = runBlocking {
        val (spy, c, _) = setup()
        spy.failure = RelayFailure.FORBIDDEN
        assertFalse(c.syncAll(listOf(GID, GID)))
        assertEquals(2, spy.pulls)
        assertFalse(c.syncAll(listOf(GID)))
        assertEquals(3, spy.pulls)
    }
}
