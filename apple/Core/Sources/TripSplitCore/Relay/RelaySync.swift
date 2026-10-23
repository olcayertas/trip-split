// The relay sync of one group (SPEC §10.4.4): pull until `more` is false, then push.

public struct RelayState: Equatable, Sendable {
    /// The highest cursor that this device has read.
    public var cursor: Int64
    /// For each author, the highest n such that the relay holds seq 1...n (as far as this device knows).
    public var vector: [String: Int64]

    public init(cursor: Int64 = 0, vector: [String: Int64] = [:]) {
        self.cursor = cursor
        self.vector = vector
    }

    /// Adds ops that the relay holds. A seq counts only when it follows the stored one.
    public mutating func add(_ ops: [JSONValue]) {
        for op in ops {
            guard let author = op["author"]?.stringValue, let seq = op["seq"]?.intValue else { continue }
            if seq == vector[author, default: 0] + 1 { vector[author] = seq }
        }
    }
}

/// An op store that also keeps the relay cursor and vector of its group.
public protocol RelayStore: OpStore {
    func relayState() async throws -> RelayState
    /// Stores the ops and the new relay state in one transaction.
    func receive(_ ops: [JSONValue], wallMillis: Int64, relay: RelayState) async throws -> ReceiveSummary
    func saveRelayState(_ state: RelayState) async throws
}

public struct RelayPage: Equatable, Sendable {
    public struct Batch: Equatable, Sendable {
        public let cursor: Int64
        public let blob: [UInt8]
        public init(cursor: Int64, blob: [UInt8]) {
            self.cursor = cursor
            self.blob = blob
        }
    }

    public let batches: [Batch]
    public let more: Bool

    public init(batches: [Batch], more: Bool) {
        self.batches = batches
        self.more = more
    }
}

/// The two requests of SPEC §10.4.3. The HTTP client implements it. Tests use a fake relay.
public protocol RelayAPI: Sendable {
    func pull(after cursor: Int64, limit: Int) async throws -> RelayPage
    /// Returns the cursor that the relay gave to the blob.
    func push(_ blob: [UInt8]) async throws -> Int64
}

public enum RelayError: Error, Equatable, Sendable {
    /// `429`, `5xx` or a network error: try again later with a back-off.
    case retryLater
    /// `403`: the mailbox belongs to a different token.
    case forbidden
    /// `507`: the mailbox is full.
    case full
    /// Any other answer, with its status code (`400`, `413`).
    case rejected(Int)
}

public struct RelayResult: Equatable, Sendable {
    public var pulledBatches = 0
    public var skippedBatches = 0
    public var received = 0
    public var pushedBatches = 0
    public var pushedOps = 0
    public var forks = 0
    public var clockSkew = false
    public init() {}
}

public struct RelaySync: Sendable {
    /// The default page size of SPEC §10.4.3.
    public static let defaultPageLimit = 50

    public let groupId: String
    public let groupKey: [UInt8]
    public let store: any RelayStore
    public let api: any RelayAPI
    public let now: @Sendable () -> Int64
    public let pageLimit: Int
    /// A fixed nonce for the vector tests. Nil means a random nonce.
    public let nonce: (@Sendable () -> [UInt8])?

    public init(groupId: String, groupKey: [UInt8], store: any RelayStore, api: any RelayAPI,
                now: @escaping @Sendable () -> Int64 = { 0 }, pageLimit: Int = RelaySync.defaultPageLimit,
                nonce: (@Sendable () -> [UInt8])? = nil) {
        self.pageLimit = pageLimit
        self.nonce = nonce
        self.groupId = groupId
        self.groupKey = groupKey
        self.store = store
        self.api = api
        self.now = now
    }

    public func run() async throws -> RelayResult {
        let mailbox = RelayCrypto.mailboxId(groupKey: groupKey)
        let key = RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId)
        var result = RelayResult()
        var state = try await store.relayState()
        try await pull(&state, &result, mailbox: mailbox, key: key)
        try await push(&state, &result, mailbox: mailbox, key: key)
        return result
    }

    private func pull(_ state: inout RelayState, _ result: inout RelayResult, mailbox: String, key: [UInt8]) async throws {
        while true {
            let page = try await api.pull(after: state.cursor, limit: pageLimit)
            var accepted: [JSONValue] = []
            var next = state
            for batch in page.batches {
                result.pulledBatches += 1
                next.cursor = max(next.cursor, batch.cursor)
                guard let ops = RelayCrypto.open(blob: batch.blob, batchKey: key, mailboxId: mailbox) else {
                    result.skippedBatches += 1
                    continue
                }
                var good: [JSONValue] = []
                var broken = false
                for op in ops {
                    switch checkReceivedOp(op, sessionGroup: groupId) {
                    case .ok: good.append(op)
                    case .group, .shape: continue
                    case .hash: broken = true
                    }
                    if broken { break }
                }
                if broken {
                    result.skippedBatches += 1
                    continue
                }
                accepted.append(contentsOf: good)
                next.add(good)
            }
            if next != state || !accepted.isEmpty {
                let summary = try await store.receive(accepted, wallMillis: now(), relay: next)
                result.received += summary.stored
                result.forks += summary.forks.count
                if summary.clockSkew { result.clockSkew = true }
            }
            let progressed = next.cursor > state.cursor
            state = next
            // A page with no new cursor cannot lead anywhere: stop instead of asking again.
            if !page.more || !progressed { return }
        }
    }

    private func push(_ state: inout RelayState, _ result: inout RelayResult, mailbox: String, key: [UInt8]) async throws {
        let missing = try await store.opsMissing(for: state.vector)
        for batch in RelayCrypto.batches(of: missing) {
            let blob = try RelayCrypto.seal(ops: batch, batchKey: key, mailboxId: mailbox, nonce: nonce?())
            _ = try await api.push(blob)
            state.add(batch)
            try await store.saveRelayState(state)
            result.pushedBatches += 1
            result.pushedOps += batch.count
        }
    }
}
