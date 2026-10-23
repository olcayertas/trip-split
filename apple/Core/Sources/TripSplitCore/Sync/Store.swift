// The storage seen by the sync engine, and an in-memory version for tests.

public struct ForkRecord: Equatable, Sendable {
    public let group: String
    public let author: String
    public let seq: Int64
    public let foreignHash: String

    public init(group: String, author: String, seq: Int64, foreignHash: String) {
        self.group = group
        self.author = author
        self.seq = seq
        self.foreignHash = foreignHash
    }
}

public struct ReceiveSummary: Equatable, Sendable {
    public var stored = 0
    public var duplicates = 0
    public var forks: [ForkRecord] = []
    /// True when a stamp was refused for clock skew (SPEC §3).
    public var clockSkew = false
    public init() {}
}

/// Storage for one group. The real implementation (GRDB) does each call in one transaction.
public protocol OpStore: Sendable {
    func versionVector() async throws -> [String: Int64]
    /// Ops with seq above the peer vector, ordered by author bytes, then seq.
    func opsMissing(for peerVector: [String: Int64]) async throws -> [JSONValue]
    /// Stores the ops in one transaction. Idempotent on (author, seq). Merges each stored hlc into the clock.
    func receive(_ ops: [JSONValue], wallMillis: Int64) async throws -> ReceiveSummary
}

public actor InMemoryOpStore: OpStore {
    public let groupId: String
    public let deviceId: String
    private var ops: [String: JSONValue] = [:]
    private var clock: HLC
    public private(set) var forks: [ForkRecord] = []

    public init(groupId: String, deviceId: String) {
        self.groupId = groupId
        self.deviceId = deviceId
        clock = HLC(device: deviceId)
    }

    public var allOps: [JSONValue] { Array(ops.values) }

    public func versionVector() -> [String: Int64] { TripSplitCore.versionVector(allOps) }

    public func opsMissing(for peerVector: [String: Int64]) -> [JSONValue] {
        allOps.filter { ($0["seq"]?.intValue ?? 0) > peerVector[$0["author"]?.stringValue ?? "", default: 0] }
            .sorted { a, b in
                let aa = a["author"]?.stringValue ?? "", ab = b["author"]?.stringValue ?? ""
                return aa != ab ? byteLess(aa, ab) : (a["seq"]?.intValue ?? 0) < (b["seq"]?.intValue ?? 0)
            }
    }

    public func receive(_ incoming: [JSONValue], wallMillis: Int64) -> ReceiveSummary {
        var summary = ReceiveSummary()
        for op in incoming {
            let ref = opRef(op)
            if let existing = ops[ref] {
                if existing["hash"] == op["hash"] {
                    summary.duplicates += 1
                } else {
                    let fork = ForkRecord(group: groupId, author: op["author"]?.stringValue ?? "",
                                          seq: op["seq"]?.intValue ?? 0, foreignHash: op["hash"]?.stringValue ?? "")
                    forks.append(fork)
                    summary.forks.append(fork)
                }
                continue
            }
            ops[ref] = op
            summary.stored += 1
            if clock.receive(op["hlc"]?.stringValue ?? "", wall: wallMillis) { summary.clockSkew = true }
        }
        return summary
    }

    /// Adds an op this device wrote itself.
    public func insertLocal(_ op: JSONValue) { ops[opRef(op)] = op }

    public func fold() -> JSONValue { TripSplitCore.fold(groupId: groupId, ops: allOps) }
}
