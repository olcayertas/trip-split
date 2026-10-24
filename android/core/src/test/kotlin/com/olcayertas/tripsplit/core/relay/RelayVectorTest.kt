package com.olcayertas.tripsplit.core.relay

import com.olcayertas.tripsplit.core.TestDevice
import com.olcayertas.tripsplit.core.Vectors
import com.olcayertas.tripsplit.core.fold
import com.olcayertas.tripsplit.core.fromHex
import com.olcayertas.tripsplit.core.long
import com.olcayertas.tripsplit.core.objs
import com.olcayertas.tripsplit.core.opAuthorOf
import com.olcayertas.tripsplit.core.opSeqOf
import com.olcayertas.tripsplit.core.str
import com.olcayertas.tripsplit.core.strings
import com.olcayertas.tripsplit.core.toHex
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `protocol/vectors/relay.json` (SPEC §10.4). */
@Timeout(60)
class RelayVectorTest {
    private val v = Vectors.load("relay")
    private val groupId = v.getValue("group").str()
    private val groupKey = fromHex(v.getValue("groupKeyHex").str())
    private val mailbox = v.getValue("mailboxId").str()
    private val ops = v.getValue("ops").objs()
    private fun opsOf(refs: List<String>): List<JsonObject> =
        refs.map { ref -> ops.single { opAuthorOf(it) + ":" + opSeqOf(it) == ref } }

    @Test
    fun keysAndNames() {
        assertEquals(mailbox, mailboxId(groupKey))
        assertEquals(v.getValue("token").str(), relayToken(groupKey))
        assertEquals(v.getValue("tokenHashHex").str(), toHex(MessageDigest.getInstance("SHA-256").digest(relayToken(groupKey).toByteArray())))
        assertEquals(v.getValue("batchKeyHex").str(), toHex(batchKey(groupKey, groupId)))
    }

    @Test
    fun sealGivesTheSameBytesAndOpenGivesTheOpsBack() {
        val key = batchKey(groupKey, groupId)
        for (b in v.getValue("blobs").objs()) {
            val batch = opsOf(b.getValue("opRefs").strings())
            val blob = sealBatch(key, mailbox, batch, fromHex(b.getValue("nonceHex").str()))
            assertEquals(b.getValue("blobHex").str(), toHex(blob))
            assertEquals(batch, openBatch(key, mailbox, blob))
        }
    }

    @Test
    fun tamperedAndWrongAadBlobsDoNotOpen() {
        val key = batchKey(groupKey, groupId)
        assertNull(openBatch(key, mailbox, fromHex(v.getValue("tamperedBlobHex").str())))
        // The wrong-AAD blob is sealed for another mailbox. It opens there, and not in this one.
        val wrongAad = fromHex(v.getValue("wrongAadBlobHex").str())
        assertNull(openBatch(key, mailbox, wrongAad))
        assertNotNull(openBatch(key, v.getValue("wrongAadMailboxId").str(), wrongAad))
    }

    /** What the relay answered to each request, in the form of the vector. */
    private class Recorder(private val inner: FakeRelay) : RelayApi {
        val log = ArrayList<Triple<String, String, Any>>() // method, path, bytes sent with the answer, or the page

        override suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted {
            val posted = inner.push(mailboxId, token, blob)
            log.add(Triple("POST", "/v1/m/$mailboxId/batches", blob to posted))
            return posted
        }

        override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage {
            val page = inner.pull(mailboxId, token, after, limit)
            log.add(Triple("GET", "/v1/m/$mailboxId/batches?after=$after&limit=$limit", page))
            return page
        }
    }

    /** A client that does not know epochs: the relay hides them. This is the negative control. */
    private class IgnoresEpochs(private val inner: RelayApi) : RelayApi {
        override suspend fun push(mailboxId: String, token: String, blob: ByteArray): RelayPosted =
            inner.push(mailboxId, token, blob).let { RelayPosted(it.cursor, "") }

        override suspend fun pull(mailboxId: String, token: String, after: Long, limit: Int): RelayPage =
            inner.pull(mailboxId, token, after, limit).let { RelayPage(it.batches, it.more, null) }
    }

    private fun epochOf(e: kotlinx.serialization.json.JsonElement?): String? =
        if (e == null || e is kotlinx.serialization.json.JsonNull) null else e.str()

    /** Replays [scenario] and checks every request and every step. Throws an AssertionError on the first difference. */
    private fun replay(scenario: JsonObject, wrap: (RelayApi) -> RelayApi = { it }) = runBlocking {
        val devices = mapOf(
            "A" to TestDevice("aaaaaaaa-0000-4000-8000-000000000001", 1),
            "B" to TestDevice("bbbbbbbb-0000-4000-8000-000000000002", 2),
            "C" to TestDevice("cccccccc-0000-4000-8000-000000000003", 3),
        )
        for ((name, refs) in v.getValue("scenario").jsonObject.getValue("initialHeld").jsonObject) {
            val t = devices.getValue(name)
            t.store.addGroup(groupId, groupKey)
            t.store.insert(groupId, opsOf(refs.strings())) { null }
        }
        val relay = FakeRelay()
        val recorder = Recorder(relay)
        val api = wrap(recorder)
        var posts = 0
        val nonce = { ByteArray(12).also { n -> val k = ++posts; n[8] = (k ushr 24).toByte(); n[9] = (k ushr 16).toByte(); n[10] = (k ushr 8).toByte(); n[11] = k.toByte() } }
        val expireBefore = scenario["expireBeforeStep"]?.long()?.toInt()

        for ((index, step) in scenario.getValue("steps").objs().withIndex()) {
            if (index == expireBefore) relay.expire(mailbox)
            val who = step.getValue("device").str()
            val t = devices.getValue(who)
            assertEquals(step.getValue("heldBefore").long(), t.store.ops(groupId).size.toLong(), "held before $who")
            recorder.log.clear()
            val r = RelaySync(t.device, api, step.getValue("pageLimit").long().toInt(), nonce).sync(groupId)

            val expected = step.getValue("requests").objs()
            assertEquals(expected.size, recorder.log.size, "request count of step $index ($who)")
            for ((e, got) in expected.zip(recorder.log)) {
                assertEquals(e.getValue("method").str(), got.first)
                assertEquals(e.getValue("path").str(), got.second)
                val resp = e.getValue("response").jsonObject
                if (got.first == "POST") {
                    @Suppress("UNCHECKED_CAST")
                    val sent = got.third as Pair<ByteArray, RelayPosted>
                    assertEquals(e.getValue("bodyHex").str(), toHex(sent.first))
                    assertEquals(201L, e.getValue("status").long())
                    assertEquals(resp.getValue("cursor").long(), sent.second.cursor)
                    assertEquals(resp.getValue("epoch").str(), sent.second.epoch)
                } else {
                    val page = got.third as RelayPage
                    assertEquals(epochOf(resp["epoch"]), page.epoch)
                    assertEquals(resp.getValue("more").str().toBoolean(), page.more)
                    val want = resp.getValue("batches").jsonArray.map { it.jsonObject }
                    assertEquals(want.map { it.getValue("cursor").long() }, page.batches.map { it.cursor })
                    assertEquals(
                        want.map { it.getValue("blob").str() },
                        page.batches.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.blob) },
                    )
                }
            }
            assertEquals(step.getValue("stored").long().toInt(), r.storedOps, "stored of step $index")
            assertEquals(step.getValue("sent").long().toInt(), r.pushedOps, "sent of step $index")
            val state = t.store.relayState(groupId)
            assertEquals(epochOf(step["relayEpoch"]), state.epoch, "relayEpoch of step $index")
            assertEquals(step.getValue("relayCursor").long(), state.cursor, "relayCursor of step $index")
            assertEquals(step.getValue("relayVector").jsonObject.mapValues { it.value.long() }, state.vector, "relayVector of step $index")
            assertEquals(step.getValue("heldAfter").long(), t.store.ops(groupId).size.toLong(), "held after step $index")
        }

        assertEquals(scenario.getValue("relayBlobCount").long().toInt(), relay.blobCount(mailbox))
        val end = devices.getValue("C").store.ops(groupId)
        assertEquals(scenario.getValue("endOpRefs").strings(), end.map { opAuthorOf(it) + ":" + opSeqOf(it) }.sorted())
        scenario["endFoldBalances"]?.let {
            assertEquals(it.jsonObject.mapValues { b -> b.value.long() }, fold(groupId, end).balances)
        }
    }

    @Test
    fun scenarioRequestsStateAndEndFold() = replay(v.getValue("scenario").jsonObject)

    @Test
    fun expiryScenarioResetsAStaleCursorAndVector() = replay(v.getValue("expiryScenario").jsonObject)

    // The negative control: a client without the epoch rule fails the expiry scenario.
    @Test
    fun negativeControlWithoutTheEpochRuleTheExpiryScenarioFails() {
        assertFailsWith<AssertionError> { replay(v.getValue("expiryScenario").jsonObject, ::IgnoresEpochs) }
    }
}
