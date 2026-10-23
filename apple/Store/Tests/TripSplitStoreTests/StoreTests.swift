import Foundation
import GRDB
import Testing
import TripSplitCore
@testable import TripSplitStore

private func tempDir() -> URL {
    FileManager.default.temporaryDirectory.appending(path: "tripsplit-test-" + UUID().uuidString.lowercased())
}

private func makeRepo(dir: URL, marker: any InstallMarkerStore, wall: @escaping @Sendable () -> Int64 = { 1_761_210_000_000 }) async throws
    -> GroupRepository
{
    let db = try AppDatabase.open(directory: dir)
    let identity = try db.loadIdentity(marker: marker, wall: wall())
    return await GroupRepository(database: db, identity: identity, now: wall)
}

@MainActor @Suite("Store")
struct StoreTests {
    @Test func createGroupWritesSeqOneAndFolds() async throws {
        let repo = try await makeRepo(dir: tempDir(), marker: InMemoryInstallMarker())
        let id = try await repo.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        let state = await repo.state(of: id)
        #expect(state.group?.name == "Trip")
        #expect(state.members.count == 1)
        #expect(await repo.myMember(in: id)?.name == "Ali")
        let vector = try await GRDBOpStore(pool: repo.database.pool, groupId: id).versionVector()
        #expect(vector == [await repo.deviceId: 1])
    }

    @Test func invalidOpIsRefusedBeforeWriting() async throws {
        let repo = try await makeRepo(dir: tempDir(), marker: InMemoryInstallMarker())
        let id = try await repo.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        await #expect(throws: StoreError.invalidOp) {
            try await repo.append(groupId: id, type: "member.add", body: .object(["memberId": .string("nope"), "name": .string("X")]))
        }
        await #expect(throws: StoreError.unknownGroup) {
            try await repo.append(groupId: "00000000-0000-4000-8000-000000000009", type: "group.rename", body: .object(["name": .string("X")]))
        }
        let vector = try await GRDBOpStore(pool: repo.database.pool, groupId: id).versionVector()
        #expect(vector.values.first == 1)
    }

    @Test func seqAndHlcSurviveRestart() async throws {
        let dir = tempDir()
        let marker = InMemoryInstallMarker()
        let first = try await makeRepo(dir: dir, marker: marker)
        let id = try await first.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        let memberId = await first.state(of: id).members[0].id
        let op2 = try await first.append(groupId: id, type: "member.add", body: .object(["memberId": .string(newId()), "name": .string("Ayşe")]))
        _ = memberId
        // Reopen with a wall clock that went back: the stamp must still grow.
        let second = try await makeRepo(dir: dir, marker: marker, wall: { 1_000 })
        #expect(await second.deviceId == first.deviceId)
        let op3 = try await second.append(groupId: id, type: "group.rename", body: .object(["name": .string("Trip 2")]))
        #expect(op3["seq"]?.intValue == 3)
        #expect(op2["hlc"]!.stringValue! < op3["hlc"]!.stringValue!)
        await second.refreshAll()
        #expect(await second.state(of: id).group?.name == "Trip 2")
        #expect(await second.state(of: id).members.count == 2)
    }

    @Test func insertIsIdempotentAndForksAreRecorded() async throws {
        let repo = try await makeRepo(dir: tempDir(), marker: InMemoryInstallMarker())
        let other = "aaaaaaaa-0000-4000-8000-000000000001"
        let group = "00000000-0000-4000-8000-000000000001"
        try await repo.join(Invite(groupId: group, groupKey: [UInt8](repeating: 1, count: 32)))
        let store = GRDBOpStore(pool: repo.database.pool, groupId: group)
        let op = buildOp(group: group, author: other, seq: 1, hlc: HLC.format(millis: 1_761_210_000_000, counter: 0, device: other),
                         type: "group.rename", body: .object(["name": .string("A")]))
        let forked = buildOp(group: group, author: other, seq: 1, hlc: HLC.format(millis: 1_761_210_000_000, counter: 0, device: other),
                             type: "group.rename", body: .object(["name": .string("B")]))
        let unknown = buildOp(group: group, author: other, seq: 2, hlc: HLC.format(millis: 1_761_210_000_001, counter: 0, device: other),
                              type: "poll.add", body: .object(["q": .string("?")]))
        let badBody = buildOp(group: group, author: other, seq: 3, hlc: HLC.format(millis: 1_761_210_000_002, counter: 0, device: other),
                              type: "member.add", body: .object(["memberId": .string("x")]))
        let s1 = try await store.receive([op, unknown, badBody], wallMillis: 1_761_210_000_000)
        #expect(s1.stored == 3 && s1.duplicates == 0)
        let s2 = try await store.receive([op, forked], wallMillis: 1_761_210_000_000)
        #expect(s2.stored == 0 && s2.duplicates == 1 && s2.forks.count == 1)
        #expect(try await store.versionVector() == [other: 3])
        let missing = try await store.opsMissing(for: [other: 1])
        #expect(missing.map { $0["seq"]!.intValue! } == [2, 3])
        let statuses = try await repo.database.pool.read { try String.fetchAll($0, sql: "SELECT status FROM ops ORDER BY seq") }
        #expect(statuses == ["ok", "ok", "invalid"])
        let forkCount = try await repo.database.pool.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM forks") }
        #expect(forkCount == 1)
    }

    @Test func restoreMintsNewDeviceId() async throws {
        let dir = tempDir()
        let marker = InMemoryInstallMarker()
        let first = try await makeRepo(dir: dir, marker: marker)
        let id = try await first.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        try await first.database.pool.writeWithoutTransaction { try $0.execute(sql: "PRAGMA wal_checkpoint(TRUNCATE)") }
        // A restored copy of the database on a phone whose marker store is empty.
        let copyDir = tempDir()
        try FileManager.default.createDirectory(at: copyDir, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: dir.appending(path: "tripsplit.sqlite"), to: copyDir.appending(path: "tripsplit.sqlite"))
        let restored = try await makeRepo(dir: copyDir, marker: InMemoryInstallMarker())
        #expect(await restored.deviceId != first.deviceId)
        #expect(await restored.identity.mintedAfterRestore)
        // The counter starts again at 1 for the new id; the old ops stay.
        let op = try await restored.append(groupId: id, type: "group.rename", body: .object(["name": .string("After")]))
        #expect(op["seq"]?.intValue == 1)
        #expect(op["author"]?.stringValue == restored.deviceId)
        let vector = try await GRDBOpStore(pool: restored.database.pool, groupId: id).versionVector()
        #expect(vector.count == 2)
        // A normal restart keeps the new id.
        let again = try await makeRepo(dir: copyDir, marker: InMemoryInstallMarker(restored.deviceId))
        #expect(await again.deviceId == restored.deviceId)
    }

    @Test func databaseIsExcludedFromBackup() throws {
        let db = try AppDatabase.open(directory: tempDir())
        let values = try db.url.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
        let dirValues = try db.url.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(dirValues.isExcludedFromBackup == true)
    }

    @Test func keychainMarkerRoundTrip() throws {
        let marker = KeychainInstallMarker(service: "com.olcayertas.TripSplit.test." + UUID().uuidString, account: "marker")
        #expect(marker.read() == nil)
        try marker.write("abc")
        #expect(marker.read() == "abc")
        try marker.write("def")
        #expect(marker.read() == "def")
    }

    @Test func observationPublishesRemoteOps() async throws {
        let a = try await makeRepo(dir: tempDir(), marker: InMemoryInstallMarker())
        await a.start()
        let id = try await a.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        // Ops that arrive through sync (not through append) also reach the published state.
        let store = GRDBOpStore(pool: a.database.pool, groupId: id)
        let author = "bbbbbbbb-0000-4000-8000-000000000002"
        let op = buildOp(group: id, author: author, seq: 1, hlc: HLC.format(millis: 1_761_210_100_000, counter: 0, device: author),
                         type: "group.rename", body: .object(["name": .string("Renamed remotely")]))
        _ = try await store.receive([op], wallMillis: 1_761_210_000_000)
        var name = ""
        for _ in 0..<100 {
            name = await a.state(of: id).group?.name ?? ""
            if name == "Renamed remotely" { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(name == "Renamed remotely")
    }
}
