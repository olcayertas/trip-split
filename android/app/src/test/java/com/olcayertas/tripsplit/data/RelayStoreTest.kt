package com.olcayertas.tripsplit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.olcayertas.tripsplit.core.Device
import com.olcayertas.tripsplit.core.RelayState
import com.olcayertas.tripsplit.data.db.TripSplitDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val GID = "11111111-2222-4333-8444-555555555555"
private const val IDA = "aaaaaaaa-0000-4000-8000-000000000001"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RelayStoreTest {
    private lateinit var db: TripSplitDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TripSplitDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // MigrationTestHelper cannot find the schema assets under Robolectric. This test builds the version 1 file from
    // the exported schema, then lets Room open it with the migration. Room checks the result against version 2.
    @Test
    fun migrationFromVersion1KeepsGroupsAndStartsTheRelayAtZero() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = Json.parseToJsonElement(
            java.io.File("schemas/com.olcayertas.tripsplit.data.db.TripSplitDatabase/1.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        for (entity in schema.getValue("entities").jsonArray) {
            val e = entity.jsonObject
            val table = e.getValue("tableName").jsonPrimitive.content
            raw.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            for (index in e["indices"]?.jsonArray.orEmpty()) {
                raw.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        for (q in schema.getValue("setupQueries").jsonArray) raw.execSQL(q.jsonPrimitive.content)
        raw.execSQL("INSERT INTO groups (group_id, group_key, created_at, last_used_at) VALUES ('$GID', x'00', 1, 2)")
        raw.version = 1
        raw.close()

        val migrated = Room.databaseBuilder(context, TripSplitDatabase::class.java, name)
            .addMigrations(TripSplitDatabase.MIGRATION_1_2, TripSplitDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            val g = runBlocking { migrated.groups().get(GID) }!!
            assertEquals(0L, g.relayCursor)
            assertEquals("{}", g.relayVector)
            assertEquals(null, g.relayEpoch)
            assertEquals(2L, g.lastUsedAt)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun relayStateSurvivesAReopenedStoreAndIsEmptyForANewGroup() = runBlocking {
        val store = RoomOpStore(db)
        store.addGroup(GID, ByteArray(32))
        assertEquals(RelayState(), store.relayState(GID))
        store.saveRelayState(GID, RelayState(5, mapOf(IDA to 3L), "e".repeat(32)))
        assertEquals(RelayState(5, mapOf(IDA to 3L), "e".repeat(32)), RoomOpStore(db).relayState(GID))
        store.saveRelayState(GID, RelayState())
        assertEquals(RelayState(), RoomOpStore(db).relayState(GID))
    }

    @Test
    fun theCursorAndTheOpsAreOneTransaction() = runBlocking {
        val store = RoomOpStore(db)
        store.addGroup(GID, ByteArray(32))
        val d = Device(IDA, store, wall = { 1_761_210_000_000L })
        val op = d.append(GID, "member.add", buildJsonObject { put("memberId", "00000000-0000-4000-8000-000000000001"); put("name", "Ali") })
        val before = store.relayState(GID)
        // The clock callback fails in the middle of the transaction. The new cursor must not stay.
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.insertFromRelay(GID, listOf(op), RelayState(9, mapOf(IDA to 1L))) { error("boom") }
            }
        }
        assertEquals(before, store.relayState(GID))
        // And a good call writes both.
        store.insertFromRelay(GID, emptyList(), RelayState(9, mapOf(IDA to 1L))) { null }
        assertEquals(RelayState(9, mapOf(IDA to 1L)), store.relayState(GID))
    }
}
