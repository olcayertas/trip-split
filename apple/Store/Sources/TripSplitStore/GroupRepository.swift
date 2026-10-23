// The repository: writes ops (validated by the core) and publishes the fold result.

import Foundation
import GRDB
import Observation
import TripSplitCore

public struct GroupInfo: Equatable, Sendable, Identifiable {
    public let id: String
    public let createdAt: Int64
}

@MainActor
@Observable
public final class GroupRepository {
    public private(set) var groups: [GroupInfo] = []
    public private(set) var states: [String: GroupState] = [:]
    public private(set) var identity: DeviceIdentity

    @ObservationIgnored public let database: AppDatabase
    @ObservationIgnored private let now: @Sendable () -> Int64
    @ObservationIgnored private var observation: Task<Void, Never>?
    /// Called on the main actor after this device writes an op. The relay sync uses it (SPEC §10.4.4).
    @ObservationIgnored public var onLocalWrite: (@MainActor (String) -> Void)?

    public init(database: AppDatabase, identity: DeviceIdentity,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.database = database
        self.identity = identity
        self.now = now
    }

    deinit { observation?.cancel() }

    public var deviceId: String { identity.deviceId }

    // MARK: Observation

    /// Starts to follow the database. Every change (local write or sync) refreshes `states`.
    public func start() {
        guard observation == nil else { return }
        let pool = database.pool
        observation = Task { [weak self] in
            let tracking = ValueObservation.tracking { db -> [String] in
                let counts = try Row.fetchAll(db, sql: "SELECT group_id, COUNT(*) AS n FROM ops GROUP BY group_id")
                    .map { "\($0["group_id"] as String):\($0["n"] as Int64)" }
                let ids = try Row.fetchAll(db, sql: "SELECT group_id FROM groups ORDER BY created_at, group_id")
                    .map { "g:\($0["group_id"] as String)" }
                return ids + counts
            }
            do {
                for try await _ in tracking.values(in: pool) {
                    await self?.refreshAll()
                }
            } catch {}
        }
    }

    public func refreshAll() async {
        do {
            let rows = try await database.pool.read { db in
                try Row.fetchAll(db, sql: "SELECT group_id, created_at FROM groups ORDER BY created_at, group_id")
                    .map { GroupInfo(id: $0["group_id"], createdAt: $0["created_at"]) }
            }
            var next: [String: GroupState] = [:]
            for g in rows { next[g.id] = try await Self.loadState(pool: database.pool, groupId: g.id) }
            groups = rows
            states = next
        } catch {}
    }

    nonisolated static func loadState(pool: DatabasePool, groupId: String) async throws -> GroupState {
        let ops: [JSONValue] = try await pool.read { db in
            try String.fetchAll(db, sql: "SELECT op_json FROM ops WHERE group_id = ?", arguments: [groupId])
                .compactMap { try? JSONParser.parse($0) }
        }
        return GroupState(foldResult: fold(groupId: groupId, ops: ops))
    }

    public func state(of groupId: String) -> GroupState { states[groupId] ?? .empty }

    /// The member this device claims in the group, if any.
    public func myMember(in groupId: String) -> GroupState.Member? {
        state(of: groupId).members.first { $0.claimedBy == deviceId }
    }

    // MARK: Groups

    public func setDisplayName(_ name: String) throws {
        try database.setDisplayName(name)
        identity = DeviceIdentity(deviceId: identity.deviceId, displayName: name, mintedAfterRestore: identity.mintedAfterRestore)
    }

    /// Creates a group with a new key. The first op is `group.create` with seq 1.
    @discardableResult
    public func createGroup(name: String, baseCurrency: String, creatorName: String) async throws -> String {
        let groupId = newId()
        let key = (0..<32).map { _ in UInt8.random(in: 0...255) }
        let wall = now()
        try await database.pool.write { db in
            try db.execute(sql: "INSERT INTO groups (group_id, group_key, next_seq, created_at) VALUES (?, ?, 1, ?)",
                           arguments: [groupId, Data(key), wall])
        }
        try await append(groupId: groupId, type: "group.create", body: .object([
            "name": .string(name), "baseCurrency": .string(baseCurrency),
            "creatorMemberId": .string(newId()), "creatorName": .string(creatorName),
        ]))
        return groupId
    }

    /// Stores the group id and key of an invite. The log comes with the first sync.
    public func join(_ invite: Invite) async throws {
        let wall = now()
        try await database.pool.write { db in
            try db.execute(sql: "INSERT OR IGNORE INTO groups (group_id, group_key, next_seq, created_at) VALUES (?, ?, 1, ?)",
                           arguments: [invite.groupId, Data(invite.groupKey), wall])
        }
        await refreshAll()
    }

    public func groupKey(of groupId: String) async throws -> [UInt8] {
        try await database.pool.read { db in
            guard let data = try Data.fetchOne(db, sql: "SELECT group_key FROM groups WHERE group_id = ?", arguments: [groupId])
            else { throw StoreError.unknownGroup }
            return [UInt8](data)
        }
    }

    public func invite(for groupId: String) async throws -> Invite {
        Invite(groupId: groupId, groupKey: try await groupKey(of: groupId))
    }

    /// Every group of this device as the sync engine wants it.
    public func syncGroups() async throws -> [SyncGroup] {
        let pool = database.pool
        return try await pool.read { db in
            try Row.fetchAll(db, sql: "SELECT group_id, group_key FROM groups").map { row in
                let id: String = row["group_id"]
                return SyncGroup(groupId: id, groupKey: [UInt8](row["group_key"] as Data),
                                 store: GRDBOpStore(pool: pool, groupId: id))
            }
        }
    }

    /// Every group of this device with its key and its relay-aware store.
    public func relayTargets() async throws -> [RelayTarget] {
        let pool = database.pool
        return try await pool.read { db in
            try Row.fetchAll(db, sql: "SELECT group_id, group_key FROM groups ORDER BY created_at, group_id").map { row in
                let id: String = row["group_id"]
                return RelayTarget(groupId: id, groupKey: [UInt8](row["group_key"] as Data), store: GRDBOpStore(pool: pool, groupId: id))
            }
        }
    }

    /// The change log of a group, newest first.
    public func history(of groupId: String) async throws -> [HistoryEntry] {
        let ops: [JSONValue] = try await database.pool.read { db in
            try String.fetchAll(db, sql: "SELECT op_json FROM ops WHERE group_id = ?", arguments: [groupId])
                .compactMap { try? JSONParser.parse($0) }
        }
        return historyEntries(ops: ops)
    }

    // MARK: Writing ops

    /// Validates the body with the core, then writes the op. The seq, the HLC and the op row change in
    /// one transaction.
    @discardableResult
    public func append(groupId: String, type: String, body: JSONValue) async throws -> JSONValue {
        guard isValidBody(type: type, body: body) else { throw StoreError.invalidOp }
        let wall = now()
        let author = deviceId
        let op: JSONValue = try await database.pool.write { db in
            guard let seq = try Int64.fetchOne(db, sql: "SELECT next_seq FROM groups WHERE group_id = ?", arguments: [groupId])
            else { throw StoreError.unknownGroup }
            var clock = try GRDBOpStore.loadClock(db)
            let stamp = clock.send(wall: wall)
            let op = buildOp(group: groupId, author: author, seq: seq, hlc: stamp, type: type, body: body)
            try db.execute(
                sql: """
                INSERT INTO ops (group_id, author, seq, hlc, type, op_json, hash, status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ok', ?)
                """,
                arguments: [groupId, author, seq, stamp, type, op.canonical, op["hash"]!.stringValue!, wall])
            try db.execute(sql: "UPDATE groups SET next_seq = ? WHERE group_id = ?", arguments: [seq + 1, groupId])
            try GRDBOpStore.saveClock(db, clock)
            return op
        }
        await refreshAll()
        onLocalWrite?(groupId)
        return op
    }
}

public struct RelayTarget: Sendable {
    public let groupId: String
    public let groupKey: [UInt8]
    public let store: GRDBOpStore
}
