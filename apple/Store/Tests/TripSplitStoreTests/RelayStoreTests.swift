import Foundation
import GRDB
import Testing
import TripSplitCore
@testable import TripSplitStore

@MainActor @Suite("Relay state in SQLite")
struct RelayStoreTests {
    private let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }

    private func repo(directory: URL? = nil) async throws -> GroupRepository {
        let dir = directory ?? FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let db = try AppDatabase.open(directory: dir)
        return GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
    }

    private func run(_ repository: GroupRepository, _ relay: InMemoryRelay, groupId: String) async throws -> RelayResult {
        let target = try #require(try await repository.relayTargets().first { $0.groupId == groupId })
        return try await RelaySync(groupId: groupId, groupKey: target.groupKey, store: target.store,
                                   api: InMemoryRelayClient(relay, groupKey: target.groupKey), now: wall).run()
    }

    @Test func twoPhonesConvergeAndKeepTheirRelayState() async throws {
        let relay = InMemoryRelay()
        let host = try await repo()
        let guest = try await repo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        for n in 0..<70 {
            try await host.append(groupId: id, type: "member.add", body: .object([
                "memberId": .string(newId()), "name": .string("M\(n)"),
            ]))
        }
        try await guest.join(try await host.invite(for: id))

        let pushed = try await run(host, relay, groupId: id)
        #expect(pushed.pushedOps == 71 && pushed.pushedBatches == 2)
        let pulled = try await run(guest, relay, groupId: id)
        #expect(pulled.received == 71 && pulled.pushedOps == 0)
        await guest.refreshAll()
        #expect(guest.state(of: id).members.count == 71)

        // The cursor and the vector are in the database, and the next run sends and reads nothing.
        let store = try #require(try await guest.relayTargets().first).store
        let state = try await store.relayState()
        #expect(state.cursor == 2)
        #expect(state.vector[host.deviceId] == 71)
        let again = try await run(guest, relay, groupId: id)
        #expect(again.received == 0 && again.pushedOps == 0)
    }

    @Test func receiveWritesOpsAndStateInOneTransaction() async throws {
        let host = try await repo()
        let guest = try await repo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        try await guest.join(try await host.invite(for: id))
        let ops = try await host.relayTargets()[0].store.opsMissing(for: [:])
        let store = try #require(try await guest.relayTargets().first).store
        let epoch = "0000000000000000000000000000000a"
        let summary = try await store.receive(ops, wallMillis: wall(), relay: RelayState(epoch: epoch, cursor: 7, vector: [host.deviceId: 1]))
        #expect(summary.stored == 1)
        #expect(try await store.relayState() == RelayState(epoch: epoch, cursor: 7, vector: [host.deviceId: 1]))
        // A repeat is a duplicate and still saves the state.
        let repeated = try await store.receive(ops, wallMillis: wall(), relay: RelayState(cursor: 8, vector: [host.deviceId: 1]))
        #expect(repeated.duplicates == 1 && repeated.stored == 0)
        #expect(try await store.relayState().cursor == 8)
    }

    @Test func aDatabaseOfVersionOneGetsTheNewColumns() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try AppDatabase.prepare(directory: dir)
        let path = dir.appending(path: "tripsplit.sqlite").path
        let pool = try DatabasePool(path: path)
        // Apply only the first migration, then add a group like an older build did.
        let all = AppDatabase.migrator
        #expect(all.migrations.contains("v2-relay") && all.migrations.contains("v3-relay-epoch"))
        try all.migrate(pool, upTo: "v1")
        try await pool.write { db in
            try db.execute(sql: "INSERT INTO groups (group_id, group_key, next_seq, created_at) VALUES ('g1', x'00', 1, 1)")
        }
        try all.migrate(pool)
        let (cursor, vector): (Int64, String) = try await pool.read { db in
            let row = try #require(try Row.fetchOne(db, sql: "SELECT relay_cursor, relay_vector FROM groups WHERE group_id = 'g1'"))
            return (row["relay_cursor"], row["relay_vector"])
        }
        #expect(cursor == 0)
        #expect(vector == "{}")
        let epoch: String? = try await pool.read { try String.fetchOne($0, sql: "SELECT relay_epoch FROM groups WHERE group_id = 'g1'") }
        #expect(epoch == nil)
    }
}
