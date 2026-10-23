import Foundation
import Testing
import TripSplitCore
import TripSplitStore
@testable import TripSplitNet

private final class PipeLink: Link, @unchecked Sendable {
    let incoming: AsyncThrowingStream<[UInt8], Error>
    private let continuation: AsyncThrowingStream<[UInt8], Error>.Continuation
    private let lock = NSLock()
    private var peer: PipeLink?

    init() { (incoming, continuation) = AsyncThrowingStream.makeStream() }

    static func pair() -> (PipeLink, PipeLink) {
        let a = PipeLink(), b = PipeLink()
        a.peer = b
        b.peer = a
        return (a, b)
    }

    func send(_ bytes: [UInt8]) async throws {
        guard let target = lock.withLock({ peer }) else { throw SessionError.linkClosed }
        var i = 0
        while i < bytes.count {
            target.continuation.yield(Array(bytes[i..<min(i + 61, bytes.count)]))
            i += 61
        }
    }

    func close() {
        let p = lock.withLock { peer }
        continuation.finish()
        p?.continuation.finish()
    }
}

/// A shared "air" that connects fake transports of the same kind.
private final class FakeAir: @unchecked Sendable {
    private let lock = NSLock()
    private var transports: [FakeTransport] = []

    func join(_ t: FakeTransport) { lock.withLock { transports.append(t) }; refresh() }
    func leave(_ t: FakeTransport) { lock.withLock { transports.removeAll { $0 === t } }; refresh() }
    func find(_ deviceId: String, _ kind: TransportKind) -> FakeTransport? {
        lock.withLock { transports.first { $0.deviceId == deviceId && $0.kind == kind } }
    }

    func refresh() {
        let all = lock.withLock { transports }
        for t in all {
            let peers = all.filter { $0 !== t && $0.kind == t.kind && !$0.deviceId.isEmpty }.map {
                Peer(deviceId: $0.deviceId, kind: $0.kind, tags: $0.tags.map(tagText), address: $0.deviceId)
            }
            t.emit(.peers(peers))
        }
    }
}

private final class FakeTransport: PeerTransport, @unchecked Sendable {
    let kind: TransportKind
    let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private let air: FakeAir
    private let lock = NSLock()
    private var _deviceId = ""
    private var _tags: [[UInt8]] = []
    private var _connects = 0

    var deviceId: String { lock.withLock { _deviceId } }
    var tags: [[UInt8]] { lock.withLock { _tags } }
    var connectCount: Int { lock.withLock { _connects } }

    init(kind: TransportKind, air: FakeAir) {
        self.kind = kind
        self.air = air
        (events, continuation) = AsyncStream.makeStream()
    }

    func emit(_ e: TransportEvent) { continuation.yield(e) }

    func start(deviceId: String, tags: [[UInt8]]) async {
        lock.withLock { _deviceId = deviceId; _tags = tags }
        air.join(self)
    }

    func stop() async { air.leave(self) }

    func connect(to peer: Peer) async throws -> any Link {
        guard let target = air.find(peer.deviceId, kind) else { throw TransportError.failed("gone") }
        lock.withLock { _connects += 1 }
        let (a, b) = PipeLink.pair()
        target.emit(.inbound(b, kind: kind))
        return a
    }
}

@MainActor
private func makeRepo() async throws -> GroupRepository {
    let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }
    let db = try AppDatabase.open(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
    return GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
}

@MainActor
private func waitUntil(_ timeout: Double = 8, _ condition: () -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(timeout)
    while Date() < end {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(50))
    }
    return condition()
}

@MainActor
@Suite("Sync coordinator", .timeLimit(.minutes(1)))
struct CoordinatorTests {
    @Test func twoPhonesSyncOverWifiNotBluetooth() async throws {
        let host = try await makeRepo(), guest = try await makeRepo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        for n in 0..<10 {
            try await host.append(groupId: id, type: "member.add", body: .object(["memberId": .string(UUID().uuidString.lowercased()), "name": .string("M\(n)")]))
        }
        try await guest.join(try await host.invite(for: id))

        let air = FakeAir()
        let hostWifi = FakeTransport(kind: .wifi, air: air), hostBT = FakeTransport(kind: .bluetooth, air: air)
        let guestWifi = FakeTransport(kind: .wifi, air: air), guestBT = FakeTransport(kind: .bluetooth, air: air)
        let a = SyncCoordinator(repository: host, transports: [hostWifi, hostBT], cooldown: 30)
        let b = SyncCoordinator(repository: guest, transports: [guestWifi, guestBT], cooldown: 30)
        await a.start()
        await b.start()

        #expect(await waitUntil { guest.state(of: id).members.count == 11 })
        #expect(guest.state(of: id) == host.state(of: id))
        #expect(hostBT.connectCount == 0 && guestBT.connectCount == 0)
        #expect(await waitUntil { b.rows.contains { if case .done = $0.status { true } else { false } } })
        #expect(b.lastResult != nil)

        // Within the pause nothing new starts, although the peer lists change again.
        let before = hostWifi.connectCount + guestWifi.connectCount
        air.refresh()
        try await Task.sleep(for: .milliseconds(400))
        #expect(hostWifi.connectCount + guestWifi.connectCount == before)

        // A new local op ends the pause: the guest syncs again and the host receives it.
        _ = try await guest.append(groupId: id, type: "group.rename", body: .object(["name": .string("Trip 2")]))
        air.refresh()
        #expect(await waitUntil { host.state(of: id).group?.name == "Trip 2" })

        await a.stop()
        await b.stop()
    }

    @Test func bluetoothIsUsedWhenWifiIsAbsent() async throws {
        let host = try await makeRepo(), guest = try await makeRepo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        try await guest.join(try await host.invite(for: id))
        let air = FakeAir()
        let hostBT = FakeTransport(kind: .bluetooth, air: air), guestBT = FakeTransport(kind: .bluetooth, air: air)
        let a = SyncCoordinator(repository: host, transports: [hostBT])
        let b = SyncCoordinator(repository: guest, transports: [guestBT])
        await a.start()
        await b.start()
        #expect(await waitUntil { guest.state(of: id).group?.name == "Trip" })
        #expect(hostBT.connectCount + guestBT.connectCount >= 1)
        await a.stop()
        await b.stop()
    }

    @Test func strangersAreIgnored() async throws {
        let one = try await makeRepo(), two = try await makeRepo()
        _ = try await one.createGroup(name: "A", baseCurrency: "EUR", creatorName: "Ali")
        _ = try await two.createGroup(name: "B", baseCurrency: "EUR", creatorName: "Bo")
        let air = FakeAir()
        let t1 = FakeTransport(kind: .wifi, air: air), t2 = FakeTransport(kind: .wifi, air: air)
        let a = SyncCoordinator(repository: one, transports: [t1])
        let b = SyncCoordinator(repository: two, transports: [t2])
        await a.start()
        await b.start()
        try await Task.sleep(for: .milliseconds(500))
        #expect(t1.connectCount == 0 && t2.connectCount == 0)
        #expect(a.rows.first?.groupIds.isEmpty == true)
        await a.stop()
        await b.stop()
    }
}
