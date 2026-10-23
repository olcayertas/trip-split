// The session state machine (SPEC §8.3) over a byte-stream Link.
//
// A Link carries the plaintext handshake records and then the frames, as one ordered byte stream.
// The transport may cut the stream anywhere. The session cuts it into records and frames itself.

import Foundation

public protocol Link: Sendable {
    func send(_ bytes: [UInt8]) async throws
    /// One stream for the life of the link. It ends (or throws) when the peer or the transport closes.
    var incoming: AsyncThrowingStream<[UInt8], Error> { get }
    func close()
    /// Called once, after the hello of the peer. `maxChunk` is the value in that hello (0 on LAN).
    /// A Bluetooth link uses it to set the chunk size of its outgoing direction (SPEC §10.2.5).
    func peerAnnounced(maxChunk: Int64)
}

extension Link {
    public func peerAnnounced(maxChunk: Int64) {}
}

public struct SyncGroup: Sendable {
    public let groupId: String
    public let groupKey: [UInt8]
    public let store: any OpStore
    public var tag: [UInt8] { SyncCrypto.discoveryTag(groupKey: groupKey) }

    public init(groupId: String, groupKey: [UInt8], store: any OpStore) {
        self.groupId = groupId
        self.groupKey = groupKey
        self.store = store
    }
}

public enum SessionError: Error, Equatable {
    case badHandshake
    case unknownGroup
    case saltCollision
    case groupMismatch
    case unsupportedVersion
    case protocolViolation(String)
    case hashMismatch
    case frameTooLarge
    case decryptFailed
    case linkClosed
    case timeout
    case duplicateSession
    case transport(String)
}

public struct SessionResult: Equatable, Sendable {
    public var groupId: String
    public var peerDeviceId: String
    public var sentOps = 0
    public var receivedOps = 0
    public var droppedOps = 0
    public var duplicateOps = 0
    public var forks: [ForkRecord] = []
    public var clockSkew = false
    public var peerMaxChunk: Int64 = 0
}

public enum SessionEvent: Sendable {
    case handshake(peerDevice: String, groupId: String, peerMaxChunk: Int64)
    case clockSkew(peerDevice: String)
    case progress(sent: Int, received: Int)
}

/// Decides which of two sessions for one peer and group continues (SPEC §8.3 rule 7).
public protocol SessionAdmission: Sendable {
    func admit(peerDevice: String, groupId: String, myDevice: String) async -> Bool
    func release(peerDevice: String, groupId: String) async
}

/// The side with the higher deviceId closes the newer connection.
public actor SessionRegistry: SessionAdmission {
    private var active: [String: Int] = [:]
    public init() {}

    public func admit(peerDevice: String, groupId: String, myDevice: String) -> Bool {
        let key = peerDevice + "|" + groupId
        if active[key, default: 0] > 0, byteLess(peerDevice, myDevice) { return false }
        active[key, default: 0] += 1
        return true
    }

    public func release(peerDevice: String, groupId: String) {
        let key = peerDevice + "|" + groupId
        active[key] = max(0, active[key, default: 0] - 1)
    }
}

public struct SessionOptions: Sendable {
    public var deviceId: String
    public var appVersion: String
    public var maxChunk: Int64
    public var now: @Sendable () -> Int64
    public var makeSalt: @Sendable () -> [UInt8]
    public var admission: (any SessionAdmission)?
    public var onEvent: @Sendable (SessionEvent) -> Void
    /// How long a responder waits for the initiator to close after the session is complete.
    public var closeGraceSeconds: Double
    public var timeouts: SessionTimeouts

    public init(deviceId: String, appVersion: String = "1.0.0", maxChunk: Int64 = 0,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                makeSalt: @escaping @Sendable () -> [UInt8] = { (0..<SyncCrypto.saltLength).map { _ in UInt8.random(in: 0...255) } },
                admission: (any SessionAdmission)? = nil,
                onEvent: @escaping @Sendable (SessionEvent) -> Void = { _ in },
                closeGraceSeconds: Double = 5,
                timeouts: SessionTimeouts = .lan) {
        self.deviceId = deviceId
        self.appVersion = appVersion
        self.maxChunk = maxChunk
        self.now = now
        self.makeSalt = makeSalt
        self.admission = admission
        self.onEvent = onEvent
        self.closeGraceSeconds = closeGraceSeconds
        self.timeouts = timeouts
    }
}

/// Owns the single iterator of a link stream. An actor cannot hold a mutating async iterator across
/// an await, so the iterator lives here. Only one reader uses it at a time.
final class ByteSource: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<[UInt8], Error>.AsyncIterator

    init(_ stream: AsyncThrowingStream<[UInt8], Error>) { iterator = stream.makeAsyncIterator() }

    func next() async throws -> [UInt8]? { try await iterator.next() }

    /// Like `next()`, but throws `SessionError.timeout` when nothing arrives in time. After a timeout the
    /// source must not be used again: the session closes.
    func next(timeout: TimeInterval) async throws -> [UInt8]? {
        try await withThrowingTaskGroup(of: [UInt8]?.self) { group in
            group.addTask { try await self.next() }
            group.addTask {
                try await Task.sleep(for: .seconds(max(0.001, timeout)))
                throw SessionError.timeout
            }
            defer { group.cancelAll() }
            return try await group.next() ?? nil
        }
    }
}

/// Time limits of SPEC §10.1.4 (LAN) and §10.2.7 (BLE), in seconds.
public struct SessionTimeouts: Sendable {
    /// The record of the peer, after we wrote ours (responder: after the connection opened).
    public var record: TimeInterval
    /// The hello of the peer, after both records.
    public var hello: TimeInterval
    /// No byte received while the session is open.
    public var idle: TimeInterval
    /// The whole session.
    public var session: TimeInterval

    public init(record: TimeInterval, hello: TimeInterval, idle: TimeInterval, session: TimeInterval) {
        self.record = record
        self.hello = hello
        self.idle = idle
        self.session = session
    }

    public static let lan = SessionTimeouts(record: 5, hello: 10, idle: 30, session: 600)
    public static let ble = SessionTimeouts(record: 10, hello: 15, idle: 30, session: 600)
}

public actor SyncSession {
    public enum Role: Sendable { case initiator, responder }

    private let role: Role
    private let link: any Link
    private let groups: [SyncGroup]
    private let options: SessionOptions

    private let source: ByteSource
    private var buffer = StreamBuffer()
    private var sealer: FrameCipher?
    private var opener: FrameCipher?
    private var group: SyncGroup?
    private var result: SessionResult?
    private var senderError: SessionError?
    private var admitted: String?
    private var phaseTimeout: TimeInterval = 0
    private var deadline = Date.distantFuture

    /// An initiator passes the one group it wants to sync. A responder passes every group it holds.
    public init(role: Role, link: any Link, groups: [SyncGroup], options: SessionOptions) {
        self.role = role
        self.link = link
        self.groups = groups
        self.options = options
        source = ByteSource(link.incoming)
    }

    public func run() async throws -> SessionResult {
        do {
            let result = try await perform()
            if role == .initiator { link.close() } else { await waitForClose() }
            link.close()
            await releaseAdmission()
            return result
        } catch {
            link.close()
            await releaseAdmission()
            throw Self.map(error)
        }
    }

    private static func map(_ error: Error) -> SessionError {
        if let e = error as? SessionError { return e }
        if let e = error as? FrameError { return e == .tooLarge ? .frameTooLarge : .decryptFailed }
        if error is CancellationError { return .linkClosed }
        return .transport(String(describing: error))
    }

    private func releaseAdmission() async {
        if let peer = admitted, let g = group {
            admitted = nil
            await options.admission?.release(peerDevice: peer, groupId: g.groupId)
        }
    }

    // MARK: Handshake

    private func fill() async throws {
        let remaining = deadline.timeIntervalSinceNow
        guard remaining > 0 else { throw SessionError.timeout }
        guard let chunk = try await source.next(timeout: min(phaseTimeout, remaining)) else { throw SessionError.linkClosed }
        buffer.append(chunk)
    }

    private func readRecord() async throws -> (salt: [UInt8], tag: [UInt8]) {
        while buffer.count < SyncCrypto.recordLength { try await fill() }
        guard let bytes = buffer.take(SyncCrypto.recordLength), let record = SyncCrypto.parseRecord(bytes) else {
            throw SessionError.badHandshake
        }
        return record
    }

    private func handshake() async throws {
        deadline = Date().addingTimeInterval(options.timeouts.session)
        phaseTimeout = options.timeouts.record
        let ownSalt = options.makeSalt()
        let peerSalt: [UInt8]
        switch role {
        case .initiator:
            guard let wanted = groups.first else { throw SessionError.unknownGroup }
            try await link.send(SyncCrypto.handshakeRecord(salt: ownSalt, tag: wanted.tag))
            let record = try await readRecord()
            guard record.tag == wanted.tag else { throw SessionError.badHandshake }
            group = wanted
            peerSalt = record.salt
        case .responder:
            let record = try await readRecord()
            guard let found = groups.first(where: { $0.tag == record.tag }) else { throw SessionError.unknownGroup }
            group = found
            try await link.send(SyncCrypto.handshakeRecord(salt: ownSalt, tag: found.tag))
            peerSalt = record.salt
        }
        guard ownSalt != peerSalt else { throw SessionError.saltCollision }
        guard let g = group else { throw SessionError.unknownGroup }
        let key = SyncCrypto.sessionKey(groupKey: g.groupKey, saltA: ownSalt, saltB: peerSalt, groupId: g.groupId)
        let own = SyncCrypto.direction(ownSalt: ownSalt, peerSalt: peerSalt)
        sealer = FrameCipher(sessionKey: key, direction: own)
        opener = FrameCipher(sessionKey: key, direction: own == 0x01 ? 0x02 : 0x01)
    }

    // MARK: Frames

    private func sendMessage(_ message: SyncMessage) async throws {
        guard var cipher = sealer else { throw SessionError.badHandshake }
        let frame = try cipher.seal(message.plaintext)
        sealer = cipher
        try await link.send(frame)
    }

    private func readMessage() async throws -> SyncMessage {
        while true {
            if let body = try buffer.nextFrame() {
                guard var cipher = opener else { throw SessionError.badHandshake }
                let plain = try cipher.open(body)
                opener = cipher
                do {
                    return try SyncMessage.decode(plain)
                } catch {
                    throw SessionError.protocolViolation("bad message")
                }
            }
            try await fill()
        }
    }

    // MARK: Session

    private func perform() async throws -> SessionResult {
        try await handshake()
        guard let g = group else { throw SessionError.unknownGroup }

        phaseTimeout = options.timeouts.hello
        let hello = Hello(deviceId: options.deviceId, appVersion: options.appVersion, groupId: g.groupId,
                          vector: try await g.store.versionVector(), wallMillis: options.now(),
                          maxChunk: options.maxChunk)
        try await sendMessage(.hello(hello))
        guard case let .hello(peer) = try await readMessage() else {
            throw SessionError.protocolViolation("first frame is not hello")
        }
        phaseTimeout = options.timeouts.idle
        guard peer.groupId == g.groupId else { throw SessionError.groupMismatch }
        guard min(peer.protocolVersion, hello.protocolVersion) == 1 else { throw SessionError.unsupportedVersion }

        link.peerAnnounced(maxChunk: peer.maxChunk)
        var state = SessionResult(groupId: g.groupId, peerDeviceId: peer.deviceId)
        state.peerMaxChunk = peer.maxChunk
        state.clockSkew = peer.wallMillis > options.now() + maxForwardSkewMillis
        result = state
        options.onEvent(.handshake(peerDevice: peer.deviceId, groupId: g.groupId, peerMaxChunk: peer.maxChunk))
        if state.clockSkew { options.onEvent(.clockSkew(peerDevice: peer.deviceId)) }

        if let admission = options.admission {
            guard await admission.admit(peerDevice: peer.deviceId, groupId: g.groupId, myDevice: options.deviceId) else {
                throw SessionError.duplicateSession
            }
            admitted = peer.deviceId
        }

        let sender = Task { await self.sendPhase(peerVector: peer.vector) }
        do {
            var receivedDone = false
            while !receivedDone {
                switch try await readMessage() {
                case .hello:
                    throw SessionError.protocolViolation("second hello")
                case let .ops(ops):
                    try await handle(ops: ops, group: g)
                case .done:
                    receivedDone = true
                }
            }
            await sender.value
            if let e = senderError { throw e }
        } catch {
            sender.cancel()
            link.close()
            await sender.value
            throw senderError ?? error
        }
        guard let finished = result else { throw SessionError.badHandshake }
        return finished
    }

    private func handle(ops: [JSONValue], group g: SyncGroup) async throws {
        var accepted: [JSONValue] = []
        var dropped = 0
        for op in ops {
            switch checkReceivedOp(op, sessionGroup: g.groupId) {
            case .ok: accepted.append(op)
            case .group, .shape: dropped += 1
            case .hash: throw SessionError.hashMismatch
            }
        }
        let summary = try await g.store.receive(accepted, wallMillis: options.now())
        result?.droppedOps += dropped
        result?.receivedOps += summary.stored
        result?.duplicateOps += summary.duplicates
        result?.forks += summary.forks
        if summary.clockSkew { result?.clockSkew = true }
        report()
    }

    private func report() {
        options.onEvent(.progress(sent: result?.sentOps ?? 0, received: result?.receivedOps ?? 0))
    }

    private func sendPhase(peerVector: [String: Int64]) async {
        guard let g = group else { return }
        do {
            let missing = try await g.store.opsMissing(for: peerVector)
            for batch in Self.batches(missing) {
                try Task.checkCancellation()
                try await sendMessage(.ops(batch))
                result?.sentOps += batch.count
                report()
            }
            try await sendMessage(.done(try await g.store.versionVector()))
        } catch {
            if !(error is CancellationError) { senderError = Self.map(error) }
            link.close()
        }
    }

    /// Splits ops into messages of at most 64 ops and 256 KiB of plaintext.
    static func batches(_ ops: [JSONValue]) -> [[JSONValue]] {
        var out: [[JSONValue]] = []
        var current: [JSONValue] = []
        var size = 0
        for op in ops {
            let n = op.canonicalBytes.count + 1
            if !current.isEmpty, current.count >= SyncMessage.maxOpsPerMessage || size + n > SyncMessage.maxOpsPlaintext - 64 {
                out.append(current)
                current = []
                size = 0
            }
            current.append(op)
            size += n
        }
        if !current.isEmpty { out.append(current) }
        return out
    }

    /// After a complete session the responder gives the initiator a moment to close first.
    private func waitForClose() async {
        let grace = options.closeGraceSeconds
        await withTaskGroup(of: Void.self) { tasks in
            tasks.addTask { try? await Task.sleep(for: .seconds(grace)) }
            tasks.addTask { await self.drain() }
            await tasks.next()
            tasks.cancelAll()
        }
    }

    private func drain() async {
        while let _ = try? await source.next() {}
    }
}
