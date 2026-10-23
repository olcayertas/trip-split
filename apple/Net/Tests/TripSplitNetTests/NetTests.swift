import Foundation
import Testing
import TripSplitCore
import TripSplitStore
@testable import TripSplitNet

@Suite("Net helpers")
struct HelperTests {
    @Test func tagsInTXT() {
        let key = (0..<32).map { UInt8($0) }
        let tag = SyncCrypto.discoveryTag(groupKey: key)
        #expect(tagText(tag) == "703d549ece92e2d457f3ef9c")
        let text = Advert.encodeTags([tag, tag])
        #expect(Advert.decodeTags(text) == ["703d549ece92e2d457f3ef9c", "703d549ece92e2d457f3ef9c"])
        #expect(Advert.decodeTags("short,703d549ece92e2d457f3ef9c,703D549ECE92E2D457F3EF9C") == ["703d549ece92e2d457f3ef9c"])
        #expect(Advert.decodeTags("").isEmpty)
        #expect(Advert.encodeTags(Array(repeating: tag, count: 30)).utf8.count <= Advert.maxTagValueBytes)
    }

    @Test func gatewayGuess() {
        #expect(Gateway.candidate(address: 0xC0A8_2B17, mask: 0xFFFF_FF00) == 0xC0A8_2B01)
        #expect(Gateway.dotted(0xC0A8_2B01) == "192.168.43.1")
        #expect(Gateway.candidate(address: 0xC0A8_2B01, mask: 0xFFFF_FF00) == nil)
        #expect(Gateway.dotted(Gateway.candidate(address: 0xAC14_100E, mask: 0xFFFF_FFF0)!) == "172.20.16.1")
    }
}

private let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }

@MainActor
private func makeRepo() async throws -> GroupRepository {
    let db = try AppDatabase.open(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
    return GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
}

@Suite("LAN over loopback", .timeLimit(.minutes(1)))
struct LoopbackTests {
    /// Two real TCP sockets on 127.0.0.1 carry a full session between two SQLite stores.
    @MainActor @Test func realTCPSession() async throws {
        let host = try await makeRepo(), guest = try await makeRepo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        for n in 0..<25 {
            try await host.append(groupId: id, type: "member.add", body: .object(["memberId": .string(UUID().uuidString.lowercased()), "name": .string("M\(n)")]))
        }
        try await guest.join(try await host.invite(for: id))

        let server = LANTransport(port: 0, advertise: false, loopbackOnly: true)
        let client = LANTransport(port: 0, advertise: false, loopbackOnly: true)
        await server.start(deviceId: host.deviceId, tags: [])
        let port = try #require(await server.boundPortValue())

        var inbound = server.events.makeAsyncIterator()
        async let accepted: (any Link)? = {
            while let e = await inbound.next() { if case let .inbound(link, _) = e { return link } }
            return nil
        }()
        let outbound = try await client.connect(host: "127.0.0.1", port: port)
        let serverLink = try #require(await accepted)

        let initiator = SyncSession(role: .initiator, link: outbound, groups: try await guest.syncGroups(),
                                    options: SessionOptions(deviceId: guest.deviceId, now: wall, closeGraceSeconds: 1))
        let responder = SyncSession(role: .responder, link: serverLink, groups: try await host.syncGroups(),
                                    options: SessionOptions(deviceId: host.deviceId, now: wall, closeGraceSeconds: 1))
        async let r1 = initiator.run()
        async let r2 = responder.run()
        let (joined, _) = try await (r1, r2)
        #expect(joined.receivedOps == 26)
        await guest.refreshAll()
        #expect(guest.state(of: id) == host.state(of: id))
        await server.stop()
    }

    @Test func connectToClosedPortFails() async {
        let client = LANTransport(port: 0, advertise: false, loopbackOnly: true)
        await #expect(throws: (any Error).self) { _ = try await client.connect(host: "127.0.0.1", port: 1) }
    }
}

/// Live test with real Bonjour. It needs a peer that holds the fixed demo group: run the app in a
/// simulator with `-uitest -demo-fixed -auto-sync`, then run this test with TRIPSPLIT_LIVE=1.
@MainActor
@Suite("Live Bonjour", .timeLimit(.minutes(2)))
struct LiveTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["TRIPSPLIT_LIVE"] == "1"))
    func syncsWithTheSimulatorApp() async throws {
        let repo = try await makeRepo()
        let key = (0..<32).map { UInt8($0) }
        let group = "00000000-0000-4000-8000-0000000000aa"
        try await repo.join(Invite(groupId: group, groupKey: key))
        let coordinator = SyncCoordinator(repository: repo, transports: [LANTransport()])
        await coordinator.start()
        var ok = false
        for _ in 0..<100 {
            if repo.state(of: group).expenses.count >= 5 { ok = true; break }
            try await Task.sleep(for: .milliseconds(500))
        }
        print("LIVE rows:", coordinator.rows, "last:", coordinator.lastResult ?? "-", "problem:", coordinator.problem ?? "-")
        await coordinator.stop()
        #expect(ok)
    }
}
