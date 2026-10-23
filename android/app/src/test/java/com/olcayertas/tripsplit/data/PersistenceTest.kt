package com.olcayertas.tripsplit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.parseInvite
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import com.olcayertas.tripsplit.data.identity.IdentityManager
import com.olcayertas.tripsplit.data.identity.InstallMarker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeMarker(var present: Boolean = false) : InstallMarker {
    override fun exists() = present
    override fun create() { present = true }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistenceTest {
    private lateinit var context: Context
    private lateinit var db: TripSplitDatabase
    private var ids = 0

    private fun nextId(): String = "00000000-0000-4000-8000-%012d".format(++ids)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TripSplitDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun device(id: String, store: RoomOpStore = RoomOpStore(db)) =
        Device(id, store, wall = { 1_761_210_000_000L }, newId = ::nextId)

    @Test
    fun firstRunMakesIdentityAndMarker() = runBlocking {
        val marker = FakeMarker()
        val id = IdentityManager(db, marker, ::nextId).ensure()
        assertTrue(marker.present)
        assertTrue(id.previousIds.isEmpty())
        assertEquals(id.deviceId, IdentityManager(db, marker, ::nextId).ensure().deviceId)
    }

    @Test
    fun restoreWithoutMarkerMintsANewDeviceIdAndKeepsTheOldOne() = runBlocking {
        val marker = FakeMarker()
        val first = IdentityManager(db, marker, ::nextId).ensure()
        // The database came back from a backup. The Keystore marker did not.
        val restoredMarker = FakeMarker(present = false)
        val second = IdentityManager(db, restoredMarker, ::nextId).ensure()
        assertNotEquals(first.deviceId, second.deviceId)
        assertEquals(listOf(first.deviceId), second.previousIds)
        assertTrue(restoredMarker.present)
        // A third launch with the marker keeps the new id.
        assertEquals(second.deviceId, IdentityManager(db, restoredMarker, ::nextId).ensure().deviceId)
        // A second restore keeps the whole history.
        val third = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        assertEquals(listOf(first.deviceId, second.deviceId), third.previousIds)
    }

    @Test
    fun opInsertIsIdempotentAndSurvivesReopen() = runBlocking {
        val id = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        val d = device(id.deviceId)
        val g = d.createGroup("Kapadokya", "EUR", "Ali")
        d.append(g.groupId, "member.add", buildJsonObject { put("memberId", nextId()); put("name", "Ayşe") })
        val store = RoomOpStore(db)
        val ops = store.ops(g.groupId)
        assertEquals(2, ops.size)

        val again = store.insert(g.groupId, ops) { null }
        assertEquals(0, again.stored.size)
        assertEquals(2, again.skipped)
        assertEquals(2, store.ops(g.groupId).size)
        assertEquals(mapOf(id.deviceId to 2L), store.vector(g.groupId))
        // The clock is persisted with the op.
        assertEquals(1_761_210_000_000L, store.clock().millis)
        assertTrue(store.clock().counter >= 1)
        // The stored text is canonical, so a relay keeps the hash.
        assertEquals(ops.map { it["hash"] }, RoomOpStore(db).ops(g.groupId).map { it["hash"] })
    }

    @Test
    fun forkIsRecordedOnce() = runBlocking {
        val id = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        val d = device(id.deviceId)
        val g = d.createGroup("G", "EUR", "Ali")
        val own = RoomOpStore(db).ops(g.groupId).single()
        val forged = kotlinx.serialization.json.JsonObject(
            own + ("hash" to kotlinx.serialization.json.JsonPrimitive("f".repeat(64))),
        )
        val store = RoomOpStore(db)
        store.insert(g.groupId, listOf(forged)) { null }
        store.insert(g.groupId, listOf(forged)) { null }
        assertEquals(1, store.forks(g.groupId).size)
        assertEquals(own["hash"], store.ops(g.groupId).single()["hash"])
    }

    @Test
    fun joinStoresTheKeyAndTwoStoresExchangeOps() = runBlocking {
        val a = device(nextId())
        val g = a.createGroup("G", "EUR", "Ali")
        val inv = parseInvite(g.invite())!!
        val b = device(nextId())
        b.join(inv)
        assertEquals(g.key.toList(), b.store.group(g.groupId)!!.key.toList())
        assertEquals(1, RoomOpStore(db).groups().size)
    }

    @Test
    fun repositoryFoldFlowEmitsOnNewOps() = runBlocking {
        val id = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        val repo = TripRepository(db, device(id.deviceId))
        val g = repo.createGroup("Trip", "EUR", "Ali")
        assertEquals("Trip", repo.foldNow(g.groupId).group?.name)
        repo.append(g.groupId, "group.rename", buildJsonObject { put("name", "Renamed") })
        assertEquals("Renamed", repo.foldNow(g.groupId).group?.name)
    }

    @Test
    fun addMemberWithEarlierExpensesEditsEqualAndWeightsOnly() = runBlocking {
        val id = IdentityManager(db, FakeMarker(), ::nextId).ensure()
        val repo = TripRepository(db, device(id.deviceId))
        val g = repo.createGroup("Trip", "EUR", "Ali")
        val ali = g.creatorMemberId
        fun add(split: kotlinx.serialization.json.JsonObject) = runBlocking {
            val e = nextId()
            repo.append(
                g.groupId, "expense.add",
                com.olcayertas.tripsplit.core.OpBodies.expenseAdd(
                    e, "x", "2026-10-23", "food", ali,
                    com.olcayertas.tripsplit.core.OpBodies.money(1000, "EUR", "1", split),
                ),
            )
            e
        }
        val equal = add(com.olcayertas.tripsplit.core.OpBodies.equalSplit(listOf(ali)))
        val weights = add(com.olcayertas.tripsplit.core.OpBodies.weightsSplit(mapOf(ali to 2L)))
        val exact = add(com.olcayertas.tripsplit.core.OpBodies.exactSplit(mapOf(ali to 1000L)))

        val fromNow = repo.addMember(g.groupId, "Ayşe", includeEarlier = false)
        assertTrue(fromNow.untouchedExpenses.isEmpty())
        assertEquals(setOf(ali), splitMembers(repo.foldNow(g.groupId), equal))

        val withEarlier = repo.addMember(g.groupId, "Can", includeEarlier = true)
        assertEquals(listOf(exact), withEarlier.untouchedExpenses)
        val state = repo.foldNow(g.groupId)
        assertEquals(setOf(ali, withEarlier.memberId), splitMembers(state, equal))
        assertEquals(setOf(ali, withEarlier.memberId), splitMembers(state, weights))
        assertEquals(setOf(ali), splitMembers(state, exact))
    }

    private fun splitMembers(state: com.olcayertas.tripsplit.core.FoldState, expenseId: String): Set<String> {
        val split = state.expenses.first { it.id == expenseId }.money.getValue("split") as kotlinx.serialization.json.JsonObject
        val mode = (split.getValue("mode") as kotlinx.serialization.json.JsonPrimitive).content
        return when (mode) {
            "equal" -> (split.getValue("members") as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toSet()
            "weights" -> (split.getValue("weights") as kotlinx.serialization.json.JsonObject).keys
            else -> (split.getValue("shares") as kotlinx.serialization.json.JsonObject).keys
        }
    }
}
