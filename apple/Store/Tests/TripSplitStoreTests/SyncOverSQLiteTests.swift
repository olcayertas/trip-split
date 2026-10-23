import Foundation
import Testing
import TripSplitCore
@testable import TripSplitStore

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
        let target = lock.withLock { peer }
        guard let target else { throw SessionError.linkClosed }
        // Cut into 50-byte pieces to exercise the reassembly.
        var i = 0
        while i < bytes.count {
            target.continuation.yield(Array(bytes[i..<min(i + 50, bytes.count)]))
            i += 50
        }
    }

    func close() {
        let p = lock.withLock { peer }
        continuation.finish()
        p?.continuation.finish()
    }
}

@MainActor @Suite("Sync over SQLite")
struct SyncOverSQLiteTests {
    @Test func joinedPhoneDownloadsTheLog() async throws {
        let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }
        func repo() async throws -> GroupRepository {
            let db = try AppDatabase.open(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
            return await GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
        }
        let host = try await repo()
        let guest = try await repo()
        let id = try await host.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        for n in 0..<30 {
            try await host.append(groupId: id, type: "member.add", body: .object(["memberId": .string(newId()), "name": .string("M\(n)")]))
        }
        let invite = try await host.invite(for: id)
        try await guest.join(Invite.parse(invite.url)!)

        let (la, lb) = PipeLink.pair()
        let hostId = await host.deviceId, guestId = await guest.deviceId
        let initiator = SyncSession(role: .initiator, link: la, groups: try await guest.syncGroups(),
                                    options: SessionOptions(deviceId: guestId, now: wall, closeGraceSeconds: 1))
        let responder = SyncSession(role: .responder, link: lb, groups: try await host.syncGroups(),
                                    options: SessionOptions(deviceId: hostId, now: wall, closeGraceSeconds: 1))
        async let r1 = initiator.run()
        async let r2 = responder.run()
        let (joined, _) = try await (r1, r2)
        #expect(joined.receivedOps == 31)

        await guest.refreshAll()
        #expect(await guest.state(of: id) == host.state(of: id))
        #expect(await guest.state(of: id).members.count == 31)
        // The guest now writes with its own device id and seq 1.
        let guestMember = await guest.state(of: id).members[1].id
        let claim = try await guest.append(groupId: id, type: "member.claim", body: .object(["memberId": .string(guestMember)]))
        #expect(claim["seq"]?.intValue == 1)
        #expect(await guest.myMember(in: id)?.id == guestMember)
    }
}
