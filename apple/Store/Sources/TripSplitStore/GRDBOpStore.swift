// The OpStore of the sync engine on SQLite (PLAN §10). Each call is one transaction.

import Foundation
import GRDB
import TripSplitCore

public struct GRDBOpStore: RelayStore {
    public let pool: DatabasePool
    public let groupId: String

    public init(pool: DatabasePool, groupId: String) {
        self.pool = pool
        self.groupId = groupId
    }

    public func versionVector() async throws -> [String: Int64] {
        try await pool.read { db in
            var vector: [String: Int64] = [:]
            var currentAuthor = ""
            var run: Int64 = 0
            var broken = false
            let rows = try Row.fetchCursor(db, sql: "SELECT author, seq FROM ops WHERE group_id = ? ORDER BY author, seq", arguments: [groupId])
            while let row = try rows.next() {
                let author: String = row["author"]
                let seq: Int64 = row["seq"]
                if author != currentAuthor {
                    currentAuthor = author
                    run = 0
                    broken = false
                }
                if !broken, seq == run + 1 {
                    run = seq
                    vector[author] = run
                } else if seq != run {
                    broken = true
                }
            }
            return vector
        }
    }

    public func opsMissing(for peerVector: [String: Int64]) async throws -> [JSONValue] {
        try await pool.read { db in
            var out: [JSONValue] = []
            let rows = try Row.fetchCursor(
                db, sql: "SELECT author, seq, op_json FROM ops WHERE group_id = ? ORDER BY author, seq", arguments: [groupId])
            while let row = try rows.next() {
                let author: String = row["author"]
                let seq: Int64 = row["seq"]
                guard seq > peerVector[author, default: 0] else { continue }
                if let op = try? JSONParser.parse(row["op_json"] as String) { out.append(op) }
            }
            return out
        }
    }

    public func receive(_ ops: [JSONValue], wallMillis: Int64) async throws -> ReceiveSummary {
        let groupId = groupId
        return try await pool.write { db in try Self.store(db, groupId: groupId, ops: ops, wallMillis: wallMillis) }
    }

    /// Stores the ops and the relay state in one transaction (SPEC §10.4.4 step 1).
    public func receive(_ ops: [JSONValue], wallMillis: Int64, relay: RelayState) async throws -> ReceiveSummary {
        let groupId = groupId
        return try await pool.write { db in
            let summary = try Self.store(db, groupId: groupId, ops: ops, wallMillis: wallMillis)
            try Self.saveRelay(db, groupId: groupId, relay)
            return summary
        }
    }

    public func relayState() async throws -> RelayState {
        let groupId = groupId
        return try await pool.read { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT relay_cursor, relay_vector FROM groups WHERE group_id = ?",
                                             arguments: [groupId])
            else { throw StoreError.unknownGroup }
            let vector = (try? JSONParser.parse(row["relay_vector"] as String))?.objectValue?
                .compactMapValues { $0.intValue } ?? [:]
            return RelayState(cursor: row["relay_cursor"], vector: vector)
        }
    }

    public func saveRelayState(_ state: RelayState) async throws {
        let groupId = groupId
        try await pool.write { db in try Self.saveRelay(db, groupId: groupId, state) }
    }

    static func saveRelay(_ db: Database, groupId: String, _ state: RelayState) throws {
        let vector = JSONValue.object(state.vector.mapValues { .int($0) }).canonical
        try db.execute(sql: "UPDATE groups SET relay_cursor = ?, relay_vector = ? WHERE group_id = ?",
                       arguments: [state.cursor, vector, groupId])
    }

    static func store(_ db: Database, groupId: String, ops: [JSONValue], wallMillis: Int64) throws -> ReceiveSummary {
        var summary = ReceiveSummary()
        var clock = try loadClock(db)
        for op in ops {
            guard let author = op["author"]?.stringValue, let seq = op["seq"]?.intValue,
                  let hash = op["hash"]?.stringValue, let hlc = op["hlc"]?.stringValue,
                  let type = op["type"]?.stringValue
            else { continue }
            if let existing = try String.fetchOne(
                db, sql: "SELECT hash FROM ops WHERE group_id = ? AND author = ? AND seq = ?",
                arguments: [groupId, author, seq])
            {
                if existing == hash {
                    summary.duplicates += 1
                } else {
                    try db.execute(
                        sql: "INSERT OR IGNORE INTO forks (group_id, author, seq, foreign_hash, seen_at) VALUES (?, ?, ?, ?, ?)",
                        arguments: [groupId, author, seq, hash, wallMillis])
                    summary.forks.append(ForkRecord(group: groupId, author: author, seq: seq, foreignHash: hash))
                }
                continue
            }
            let verdict = status(type: type, op: op)
            try db.execute(
                sql: """
                INSERT INTO ops (group_id, author, seq, hlc, type, op_json, hash, status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                arguments: [groupId, author, seq, hlc, type, op.canonical, hash, verdict, wallMillis])
            summary.stored += 1
            if clock.receive(hlc, wall: wallMillis) { summary.clockSkew = true }
        }
        try saveClock(db, clock)
        return summary
    }

    /// `invalid` for a known type with a bad body. Unknown types and versions are `ok`: they are relayed.
    static func status(type: String, op: JSONValue) -> String {
        guard op["v"]?.intValue == 1, let body = op["body"] else { return "ok" }
        let known: Set<String> = [
            "group.create", "group.rename", "member.add", "member.rename", "member.claim", "member.unclaim",
            "member.remove", "expense.add", "expense.edit", "expense.delete", "settlement.add", "settlement.delete",
        ]
        guard known.contains(type) else { return "ok" }
        return isValidBody(type: type, body: body) ? "ok" : "invalid"
    }

    static func loadClock(_ db: Database) throws -> HLC {
        guard let row = try Row.fetchOne(db, sql: "SELECT device_id, last_hlc FROM device WHERE id = 1") else {
            throw StoreError.unknownGroup
        }
        let device: String = row["device_id"]
        let parsed = HLC.parse(row["last_hlc"] as String)
        return HLC(device: device, millis: parsed?.millis ?? 0, counter: parsed?.counter ?? 0)
    }

    static func saveClock(_ db: Database, _ clock: HLC) throws {
        try db.execute(sql: "UPDATE device SET last_hlc = ? WHERE id = 1", arguments: [clock.stamp])
    }
}
