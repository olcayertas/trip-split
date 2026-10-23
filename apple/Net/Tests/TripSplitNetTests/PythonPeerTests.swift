import Foundation
import Testing
import TripSplitCore
import TripSplitStore
@testable import TripSplitNet

/// Interop with the Python reference peer (protocol/tools/peer.py). It runs only when
/// TRIPSPLIT_PYTHON points to a python3 that has the requirements of protocol/reference installed.
private let python = ProcessInfo.processInfo.environment["TRIPSPLIT_PYTHON"]
private let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }

private func peerScript() -> String {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return url.appending(path: "protocol/tools/peer.py").path
}

private func run(_ args: [String], wait: Bool = true) throws -> (Process, Pipe) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: python ?? "/usr/bin/python3")
    p.arguments = [peerScript()] + args
    let out = Pipe()
    p.standardOutput = out
    p.standardError = out
    try p.run()
    if wait { p.waitUntilExit() }
    return (p, out)
}

@MainActor
private func makeRepo() async throws -> GroupRepository {
    let db = try AppDatabase.open(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
    return GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
}

@MainActor
@Suite("Python reference peer", .timeLimit(.minutes(2)))
struct PythonPeerTests {
    @Test(.enabled(if: python != nil))
    func weConnectToThePeer() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "peer-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let log = dir.appending(path: "a.json").path
        let (_, made) = try run(["--make-group", "Kapadokya", "--log", log])
        let invite = try #require(Invite.parse(String(decoding: made.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)))
        let port = UInt16.random(in: 40_000..<60_000)
        let (server, _) = try run(["serve", "--log", log, "--port", String(port), "--no-mdns", "--timeout", "40", "--random-ops", "12", "--seed", "3"], wait: false)
        defer { if server.isRunning { server.terminate() } }

        let repo = try await makeRepo()
        try await repo.join(invite)
        let client = LANTransport(port: 0, advertise: false, loopbackOnly: true)
        var link: (any Link)?
        for _ in 0..<40 {
            link = try? await client.connect(host: "127.0.0.1", port: port)
            if link != nil { break }
            try await Task.sleep(for: .milliseconds(250))
        }
        let session = SyncSession(role: .initiator, link: try #require(link), groups: try await repo.syncGroups(),
                                  options: SessionOptions(deviceId: repo.deviceId, now: wall))
        let result = try await session.run()
        #expect(result.receivedOps == 13)
        await repo.refreshAll()
        #expect(repo.state(of: invite.groupId).group?.name == "Kapadokya")
        #expect(repo.state(of: invite.groupId).invalidOps.isEmpty)
    }

    @Test(.enabled(if: python != nil))
    func thePeerConnectsToUs() async throws {
        let repo = try await makeRepo()
        let id = try await repo.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        for n in 0..<20 {
            try await repo.append(groupId: id, type: "member.add", body: .object(["memberId": .string(UUID().uuidString.lowercased()), "name": .string("M\(n)")]))
        }
        let invite = try await repo.invite(for: id)
        let server = LANTransport(port: 0, advertise: false, loopbackOnly: true)
        await server.start(deviceId: repo.deviceId, tags: [])
        let port = try #require(await server.boundPortValue())
        let log = FileManager.default.temporaryDirectory.appending(path: "peer-b-" + UUID().uuidString + ".json").path

        var inbound = server.events.makeAsyncIterator()
        let (client, _) = try run(["connect", "127.0.0.1:\(port)", "--log", log, "--invite", invite.url], wait: false)
        defer { if client.isRunning { client.terminate() } }
        var accepted: (any Link)?
        while let e = await inbound.next() { if case let .inbound(l, _) = e { accepted = l; break } }
        let session = SyncSession(role: .responder, link: try #require(accepted), groups: try await repo.syncGroups(),
                                  options: SessionOptions(deviceId: repo.deviceId, now: wall))
        let result = try await session.run()
        #expect(result.sentOps == 21)
        client.waitUntilExit()
        #expect(client.terminationStatus == 0)
        let data = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: log))) as? [String: Any]
        #expect((data?["ops"] as? [Any])?.count == 21)
        await server.stop()
    }
}
