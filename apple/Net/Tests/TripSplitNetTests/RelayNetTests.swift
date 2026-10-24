import Foundation
import Testing
import TripSplitCore
import TripSplitStore
@testable import TripSplitNet

/// Answers the requests of a `URLSession` from a closure, without a network.
final class StubProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: (@Sendable (URLRequest) -> (Int, Data))?
    nonisolated(unsafe) static var seen: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var request = self.request
        if request.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                data.append(buffer, count: n)
            }
            request.httpBody = data
        }
        Self.seen.append(request)
        let (code, body) = Self.handler?(request) ?? (500, Data())
        let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@Suite("Relay HTTP client", .serialized)
struct RelayHTTPClientTests {
    private let key = (0..<32).map { UInt8($0) }

    private func client() -> RelayHTTPClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        StubProtocol.seen = []
        return RelayHTTPClient(baseURL: URL(string: "https://relay.example.org")!, groupKey: key,
                               session: URLSession(configuration: configuration))
    }

    @Test func pullSendsTheTokenAndReadsTheBatches() async throws {
        let blob: [UInt8] = [1, 2, 3, 250, 251]
        StubProtocol.handler = { _ in
            (200, Data(#"{"epoch":"0000000000000000000000000000000a","batches":[{"cursor":4,"blob":"\#(Base64URL.encode(blob))"}],"more":true}"#.utf8))
        }
        let c = client()
        let page = try await c.pull(after: 3, limit: 50)
        #expect(page == RelayPage(batches: [.init(cursor: 4, blob: blob)], more: true, epoch: "0000000000000000000000000000000a"))
        let request = try #require(StubProtocol.seen.first)
        #expect(request.httpMethod == "GET")
        #expect(request.url?.path == "/v1/m/\(RelayCrypto.mailboxId(groupKey: key))/batches")
        #expect(request.url?.query == "after=3&limit=50")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer " + RelayCrypto.relayToken(groupKey: key))
    }

    @Test func pushSendsTheBlobAsBytes() async throws {
        StubProtocol.handler = { _ in (201, Data(#"{"cursor":12,"epoch":"0000000000000000000000000000000a"}"#.utf8)) }
        let c = client()
        let ack = try await c.push([9, 8, 7])
        #expect(ack == RelayPushAck(cursor: 12, epoch: "0000000000000000000000000000000a"))
        let request = try #require(StubProtocol.seen.first)
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/octet-stream")
        #expect(request.httpBody == Data([9, 8, 7]))
    }

    @Test func statusCodesMapToErrors() async throws {
        let table: [(Int, RelayError)] = [(403, .forbidden), (507, .full), (429, .retryLater), (500, .retryLater),
                                          (503, .retryLater), (400, .rejected(400)), (413, .rejected(413))]
        for (code, expected) in table {
            StubProtocol.handler = { _ in (code, Data()) }
            let c = client()
            await #expect(throws: expected) { _ = try await c.push([1]) }
            await #expect(throws: expected) { _ = try await c.pull(after: 0, limit: 10) }
        }
    }

    @Test func aNetworkErrorMeansTryLater() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = []
        let c = RelayHTTPClient(baseURL: URL(string: "https://127.0.0.1:1")!, groupKey: key,
                                session: URLSession(configuration: configuration))
        await #expect(throws: RelayError.retryLater) { _ = try await c.pull(after: 0, limit: 10) }
    }

    @Test func aBadBlobTextKeepsItsCursor() async throws {
        StubProtocol.handler = { _ in (200, Data(#"{"batches":[{"cursor":1,"blob":"***"}],"more":false}"#.utf8)) }
        let page = try await client().pull(after: 0, limit: 10)
        #expect(page.batches == [.init(cursor: 1, blob: [])])
    }

    @Test func theBaseUrlNeedsARealHost() {
        #expect(RelayConfig.baseURL("https://relay.invalid") == nil)
        #expect(RelayConfig.baseURL("https://relay.invalid/") == nil)
        #expect(RelayConfig.baseURL("") == nil)
        #expect(RelayConfig.baseURL(nil) == nil)
        #expect(RelayConfig.baseURL("http://relay.example.org") == nil)
        #expect(RelayConfig.baseURL("$(RELAY_BASE_URL)") == nil)
        #expect(RelayConfig.baseURL("https://relay.example.org")?.host() == "relay.example.org")
    }
}

@MainActor @Suite("Relay coordinator")
struct RelayCoordinatorTests {
    private let wall: @Sendable () -> Int64 = { 1_761_210_000_000 }

    private func repo() async throws -> GroupRepository {
        let db = try AppDatabase.open(directory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
        return GroupRepository(database: db, identity: try db.loadIdentity(marker: InMemoryInstallMarker(), wall: wall()), now: wall)
    }

    private func defaults() -> UserDefaults {
        let name = "relay-test-" + UUID().uuidString
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    private func settle(_ until: @MainActor () -> Bool) async {
        for _ in 0..<200 where !until() { try? await Task.sleep(for: .milliseconds(25)) }
    }

    private let url = URL(string: "https://relay.example.org")!

    @Test func twoPhonesConvergeAfterASave() async throws {
        let relay = InMemoryRelay()
        let a = try await repo(), b = try await repo()
        let ca = RelayCoordinator(repository: a, baseURL: url, defaults: defaults(), interval: 3600, watchNetwork: false,
                                  makeAPI: { _, key in InMemoryRelayClient(relay, groupKey: key) })
        let cb = RelayCoordinator(repository: b, baseURL: url, defaults: defaults(), interval: 3600, watchNetwork: false,
                                  makeAPI: { _, key in InMemoryRelayClient(relay, groupKey: key) })
        ca.setActive(true)
        cb.setActive(true)
        let id = try await a.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        // The save above triggered the sync of phone A.
        await settle { if case .done = ca.status { return true } else { return false } }
        try await b.join(try await a.invite(for: id))
        cb.trigger(id)
        await settle { b.state(of: id).members.count == 1 }
        #expect(b.state(of: id).members.count == 1)
        try await b.append(groupId: id, type: "member.add", body: .object(["memberId": .string(UUID().uuidString.lowercased()), "name": .string("Veli")]))
        // The save of phone B triggers its sync, which sends the op.
        await settle { if case let .done(_, _, sent) = cb.status { return sent > 0 } else { return false } }
        ca.trigger(nil)
        await settle { a.state(of: id).members.count == 2 }
        #expect(a.state(of: id).members.count == 2)
    }

    @Test func withoutAnUrlTheRelayStaysOff() async throws {
        let a = try await repo()
        let c = RelayCoordinator(repository: a, baseURL: nil, defaults: defaults(), watchNetwork: false)
        #expect(c.status == .notSetUp && !c.isConfigured)
        c.setActive(true)
        c.trigger(nil)
        c.setEnabled(true)
        #expect(c.status == .notSetUp)
    }

    @Test func theSwitchIsOnByDefaultAndPerDevice() async throws {
        let a = try await repo()
        let store = defaults()
        let c = RelayCoordinator(repository: a, baseURL: url, defaults: store, watchNetwork: false)
        #expect(c.isEnabled && c.status == .idle)
        c.setEnabled(false)
        #expect(c.status == .off)
        let next = RelayCoordinator(repository: a, baseURL: url, defaults: store, watchNetwork: false)
        #expect(!next.isEnabled && next.status == .off)
    }

    @Test func aFailureStartsTheBackOff() async throws {
        let relay = InMemoryRelay()
        await relay.fail(with: [.retryLater])
        let a = try await repo()
        _ = try await a.createGroup(name: "Trip", baseCurrency: "EUR", creatorName: "Ali")
        let c = RelayCoordinator(repository: a, baseURL: url, defaults: defaults(), interval: 3600, watchNetwork: false,
                                 makeAPI: { _, key in InMemoryRelayClient(relay, groupKey: key) })
        c.setActive(true)
        await settle { if case .waiting = c.status { return true } else { return false } }
        guard case let .waiting(until, _) = c.status else { Issue.record("no back-off: \(c.status)"); return }
        #expect(until.timeIntervalSinceNow > 3 && until.timeIntervalSinceNow <= 5)
        // While the back-off runs, a new trigger does not call the relay.
        let before = await relay.pulls
        c.trigger(nil)
        try await Task.sleep(for: .milliseconds(100))
        #expect(await relay.pulls == before)
        c.setActive(false)
    }
}
