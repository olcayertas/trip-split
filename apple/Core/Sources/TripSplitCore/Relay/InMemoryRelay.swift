// A relay in the process (SPEC §10.4.3), for tests of the sync engine and the stores. It has no network.

/// The mailbox server of SPEC §10.4 as an actor: it stores blobs, numbers them and checks the token.
public actor InMemoryRelay {
    struct Mailbox {
        var token: String
        var blobs: [(cursor: Int64, blob: [UInt8])] = []
    }

    private var mailboxes: [String: Mailbox] = [:]
    /// Errors that the next calls fail with, in order.
    private var failures: [RelayError] = []
    /// Negative control: acknowledge every push but store nothing.
    private var dropsPushes = false
    public private(set) var pushes = 0
    public private(set) var pulls = 0

    public init() {}

    public func fail(with errors: [RelayError]) { failures = errors }
    public func setDrops(_ value: Bool) { dropsPushes = value }

    public func append(raw blob: [UInt8], mailbox: String, token: String) {
        var box = mailboxes[mailbox] ?? Mailbox(token: token)
        box.blobs.append((Int64(box.blobs.count + 1), blob))
        mailboxes[mailbox] = box
    }

    public func pull(mailbox: String, token: String, after: Int64, limit: Int) throws -> RelayPage {
        pulls += 1
        if !failures.isEmpty { throw failures.removeFirst() }
        guard let box = mailboxes[mailbox] else { return RelayPage(batches: [], more: false) }
        guard box.token == token else { throw RelayError.forbidden }
        let rest = box.blobs.filter { $0.cursor > after }
        return RelayPage(batches: rest.prefix(limit).map { .init(cursor: $0.cursor, blob: $0.blob) }, more: rest.count > limit)
    }

    public func push(mailbox: String, token: String, blob: [UInt8]) throws -> Int64 {
        pushes += 1
        if !failures.isEmpty { throw failures.removeFirst() }
        var box = mailboxes[mailbox] ?? Mailbox(token: token)
        guard box.token == token else { throw RelayError.forbidden }
        let cursor = Int64(box.blobs.count + 1)
        if !dropsPushes {
            box.blobs.append((cursor, blob))
            mailboxes[mailbox] = box
        }
        return cursor
    }
}

public struct InMemoryRelayClient: RelayAPI {
    public let relay: InMemoryRelay
    public let mailbox: String
    public let token: String

    public init(_ relay: InMemoryRelay, groupKey: [UInt8]) {
        self.relay = relay
        mailbox = RelayCrypto.mailboxId(groupKey: groupKey)
        token = RelayCrypto.relayToken(groupKey: groupKey)
    }

    public init(relay: InMemoryRelay, mailbox: String, token: String) {
        self.relay = relay
        self.mailbox = mailbox
        self.token = token
    }

    public func pull(after cursor: Int64, limit: Int) async throws -> RelayPage {
        try await relay.pull(mailbox: mailbox, token: token, after: cursor, limit: limit)
    }

    public func push(_ blob: [UInt8]) async throws -> Int64 {
        try await relay.push(mailbox: mailbox, token: token, blob: blob)
    }
}
