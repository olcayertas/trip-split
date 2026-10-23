import CryptoKit
import Foundation
import Testing
@testable import TripSplitCore

private func relayVector() throws -> JSONValue {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return try JSONParser.parse(try Data(contentsOf: url.appending(path: "protocol/vectors/relay.json")))
}

private func hexBytes(_ v: JSONValue?) -> [UInt8] { Hex.decode(v?.stringValue ?? "")! }

/// Counts the POSTs of a scenario. The k-th POST uses the nonce k: 8 zero bytes, then k as u32 big-endian.
private final class NonceCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count: UInt32 = 0

    func next() -> [UInt8] {
        let k = lock.withLock { count += 1; return count }
        return [UInt8](repeating: 0, count: 8) + [UInt8(k >> 24 & 255), UInt8(k >> 16 & 255), UInt8(k >> 8 & 255), UInt8(k & 255)]
    }
}

/// Wraps a relay and writes down each request in the form of the vector file.
private final class Recorder: RelayAPI, @unchecked Sendable {
    private let inner: InMemoryRelayClient
    private let lock = NSLock()
    private var log: [JSONValue] = []

    init(_ inner: InMemoryRelayClient) { self.inner = inner }

    var requests: [JSONValue] { lock.withLock { log } }

    func pull(after cursor: Int64, limit: Int) async throws -> RelayPage {
        let page = try await inner.pull(after: cursor, limit: limit)
        let batches: [JSONValue] = page.batches.map { .object(["cursor": .int($0.cursor), "blob": .string(Base64URL.encode($0.blob))]) }
        add(.object([
            "method": .string("GET"),
            "path": .string("/v1/m/\(inner.mailbox)/batches?after=\(cursor)&limit=\(limit)"),
            "status": .int(200),
            "response": .object(["epoch": page.epoch.map(JSONValue.string) ?? .null, "batches": .array(batches), "more": .bool(page.more)]),
        ]))
        return page
    }

    func push(_ blob: [UInt8]) async throws -> RelayPushAck {
        let ack = try await inner.push(blob)
        add(.object([
            "method": .string("POST"),
            "path": .string("/v1/m/\(inner.mailbox)/batches"),
            "status": .int(201),
            "bodyHex": .string(Hex.encode(blob)),
            "response": .object(["cursor": .int(ack.cursor), "epoch": ack.epoch.map(JSONValue.string) ?? .null]),
        ]))
        return ack
    }

    private func add(_ entry: JSONValue) { lock.withLock { log.append(entry) } }
}

@Suite("Relay vectors")
struct RelayVectorTests {
    @Test func namesAndKeys() throws {
        let v = try relayVector()
        let key = hexBytes(v["groupKeyHex"])
        let group = try #require(v["group"]?.stringValue)
        #expect(RelayCrypto.mailboxId(groupKey: key) == v["mailboxId"]?.stringValue)
        let token = RelayCrypto.relayToken(groupKey: key)
        #expect(token == v["token"]?.stringValue)
        #expect(Hex.encode(SHA256.hash(data: Array(token.utf8))) == v["tokenHashHex"]?.stringValue)
        #expect(RelayCrypto.batchKey(groupKey: key, groupId: group) == hexBytes(v["batchKeyHex"]))
    }

    @Test func sealGivesTheSameBytesAndOpenReturnsTheOps() throws {
        let v = try relayVector()
        let key = RelayCrypto.batchKey(groupKey: hexBytes(v["groupKeyHex"]), groupId: v["group"]!.stringValue!)
        let mailbox = v["mailboxId"]!.stringValue!
        let all = v["ops"]!.arrayValue!
        func op(_ ref: String) throws -> JSONValue {
            try #require(all.first { "\($0["author"]!.stringValue!):\($0["seq"]!.intValue!)" == ref })
        }
        for blob in v["blobs"]!.arrayValue! {
            let ops = try blob["opRefs"]!.arrayValue!.map { try op($0.stringValue!) }
            #expect(Array(RelayCrypto.plaintext(ops: ops)) == Array(blob["plaintext"]!.stringValue!.utf8))
            let sealed = try RelayCrypto.seal(ops: ops, batchKey: key, mailboxId: mailbox, nonce: hexBytes(blob["nonceHex"]))
            #expect(sealed == hexBytes(blob["blobHex"]))
            #expect(RelayCrypto.open(blob: sealed, batchKey: key, mailboxId: mailbox) == ops)
        }
    }

    @Test func tamperedAndWrongAadBlobsDoNotOpen() throws {
        let v = try relayVector()
        let key = RelayCrypto.batchKey(groupKey: hexBytes(v["groupKeyHex"]), groupId: v["group"]!.stringValue!)
        let mailbox = v["mailboxId"]!.stringValue!
        #expect(RelayCrypto.open(blob: hexBytes(v["blobs"]!.arrayValue![0]["blobHex"]), batchKey: key, mailboxId: mailbox) != nil)
        #expect(RelayCrypto.open(blob: hexBytes(v["tamperedBlobHex"]), batchKey: key, mailboxId: mailbox) == nil)
        let wrong = try #require(v["wrongAadMailboxId"]?.stringValue)
        #expect(RelayCrypto.open(blob: hexBytes(v["wrongAadBlobHex"]), batchKey: key, mailboxId: mailbox) == nil)
        // The blob is good for the mailbox that it was sealed for.
        #expect(RelayCrypto.open(blob: hexBytes(v["wrongAadBlobHex"]), batchKey: key, mailboxId: wrong) != nil)
    }

    /// Replays a scenario of the vector file. Returns the stores and the differences from the file.
    private func replay(_ name: String, followsEpoch: Bool = true) async throws -> (stores: [String: InMemoryOpStore], issues: [String]) {
        let v = try relayVector()
        let key = hexBytes(v["groupKeyHex"])
        let group = v["group"]!.stringValue!
        let scenario = try #require(v[name])
        let all = v["ops"]!.arrayValue!
        func held(_ refs: [JSONValue]) -> [JSONValue] {
            let wanted = Set(refs.compactMap(\.stringValue))
            return all.filter { wanted.contains("\($0["author"]!.stringValue!):\($0["seq"]!.intValue!)") }
        }
        let relay = InMemoryRelay()
        let nonces = NonceCounter()
        var issues: [String] = []
        var stores: [String: InMemoryOpStore] = [:]
        // Scenario "scenario" starts with these. The expiry scenario has the same devices.
        let initial = v["scenario"]!["initialHeld"]!.objectValue!
        for (device, refs) in initial {
            let store = InMemoryOpStore(groupId: group, deviceId: "dddddddd-0000-4000-8000-00000000000\(device == "A" ? 1 : device == "B" ? 2 : 3)")
            for op in held(refs.arrayValue!) { await store.insertLocal(op) }
            stores[device] = store
        }
        let expireBefore = scenario["expireBeforeStep"]?.intValue
        for (index, step) in scenario["steps"]!.arrayValue!.enumerated() {
            if Int64(index) == expireBefore { await relay.expireAll() }
            let device = step["device"]!.stringValue!
            let store = try #require(stores[device])
            let tag = "\(name) step \(index) (\(device))"
            if await store.allOps.count != Int(step["heldBefore"]!.intValue!) { issues.append("\(tag): heldBefore") }
            let recorder = Recorder(InMemoryRelayClient(relay, groupKey: key))
            let result = try await RelaySync(groupId: group, groupKey: key, store: store, api: recorder,
                                             pageLimit: Int(step["pageLimit"]!.intValue!), nonce: { nonces.next() },
                                             followsEpoch: followsEpoch).run()
            if JSONValue.array(recorder.requests) != step["requests"] { issues.append("\(tag): requests") }
            if result.received != Int(step["stored"]!.intValue!) { issues.append("\(tag): stored") }
            if result.pushedOps != Int(step["sent"]!.intValue!) { issues.append("\(tag): sent") }
            let state = await store.relayState()
            if let expected = step["relayEpoch"], (state.epoch.map(JSONValue.string) ?? .null) != expected { issues.append("\(tag): relayEpoch") }
            if state.cursor != step["relayCursor"]!.intValue! { issues.append("\(tag): relayCursor") }
            if JSONValue.object(state.vector.mapValues { .int($0) }) != step["relayVector"] { issues.append("\(tag): relayVector") }
            if await store.allOps.count != Int(step["heldAfter"]!.intValue!) { issues.append("\(tag): heldAfter") }
        }
        let refs = Set(scenario["endOpRefs"]!.arrayValue!.compactMap(\.stringValue))
        // A device that did not sync after the last write of another device may hold fewer ops (B in the expiry scenario).
        let lastHeld = Dictionary(scenario["steps"]!.arrayValue!.map { ($0["device"]!.stringValue!, Int($0["heldAfter"]!.intValue!)) },
                                  uniquingKeysWith: { _, last in last })
        for (device, store) in stores where lastHeld[device] == refs.count {
            let ops = await store.allOps
            if Set(ops.map { "\($0["author"]!.stringValue!):\($0["seq"]!.intValue!)" }) != refs { issues.append("\(name) end ops of \(device)") }
            if let balances = scenario["endFoldBalances"], fold(groupId: group, ops: ops)["balances"] != balances {
                issues.append("\(name) end balances of \(device)")
            }
        }
        return (stores, issues)
    }

    @Test func scenarioRequestsMatchInOrder() async throws {
        let (_, issues) = try await replay("scenario")
        #expect(issues.isEmpty, "\(issues)")
    }

    @Test func tokenInTheScenarioIsTheRelayToken() throws {
        let v = try relayVector()
        #expect(v["scenario"]?["authorization"]?.stringValue == "Bearer " + RelayCrypto.relayToken(groupKey: hexBytes(v["groupKeyHex"])))
    }

    @Test func expiryScenarioMatchesInOrder() async throws {
        let (_, issues) = try await replay("expiryScenario")
        #expect(issues.isEmpty, "\(issues)")
    }

    /// Negative control: without the epoch rule the expiry scenario must fail.
    @Test func expiryScenarioFailsWithoutTheEpochRule() async throws {
        let (stores, issues) = try await replay("expiryScenario", followsEpoch: false)
        #expect(!issues.isEmpty)
        // A kept its old vector, so it never sent seq 3 again and C does not hold the expense of A.
        let c = try #require(stores["C"])
        #expect(await c.allOps.count < 5)
    }
}
