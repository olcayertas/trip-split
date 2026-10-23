import Foundation
import Testing
@testable import TripSplitCore

private func vector(_ name: String) throws -> JSONValue {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return try JSONParser.parse(try Data(contentsOf: url.appending(path: "protocol/vectors/\(name)")))
}

private func hex(_ s: JSONValue?) -> [UInt8] { Hex.decode(s?.stringValue ?? "")! }

@Suite("Sync vectors")
struct SyncVectorTests {
    @Test func hkdfRfc5869() throws {
        let c = try vector("crypto.json")["hkdfRfc5869Case1"]!
        let okm = SyncCrypto.hkdf(ikm: hex(c["ikmHex"]), salt: hex(c["saltHex"]), info: hex(c["infoHex"]),
                                  length: Int(c["length"]!.intValue!))
        #expect(Hex.encode(okm) == c["okmHex"]!.stringValue!)
    }

    @Test func tagsRecordsAndKey() throws {
        let c = try vector("crypto.json")
        let key = hex(c["groupKeyHex"])
        let tag = SyncCrypto.discoveryTag(groupKey: key)
        #expect(Hex.encode(tag) == c["discoveryTagHex"]!.stringValue!)
        #expect(Base64URL.encode(tag) == c["discoveryTagBase64url"]!.stringValue!)
        let other = SyncCrypto.discoveryTag(groupKey: key.map { $0 ^ 0xFF })
        #expect(Hex.encode(other) != c["discoveryTagHex"]!.stringValue!)

        let a = hex(c["initiatorSaltHex"]), b = hex(c["responderSaltHex"])
        #expect(Hex.encode(SyncCrypto.handshakeRecord(salt: a, tag: tag)) == c["initiatorRecordHex"]!.stringValue!)
        #expect(Hex.encode(SyncCrypto.handshakeRecord(salt: b, tag: tag)) == c["responderRecordHex"]!.stringValue!)
        let parsed = SyncCrypto.parseRecord(hex(c["initiatorRecordHex"]))
        #expect(parsed?.salt == a && parsed?.tag == tag)
        #expect(SyncCrypto.parseRecord(Array(hex(c["initiatorRecordHex"]).dropLast())) == nil)
        var badMagic = hex(c["initiatorRecordHex"])
        badMagic[0] = 0x58
        #expect(SyncCrypto.parseRecord(badMagic) == nil)

        let sessionKey = SyncCrypto.sessionKey(groupKey: key, saltA: a, saltB: b, groupId: c["groupId"]!.stringValue!)
        #expect(Hex.encode(sessionKey) == c["sessionKeyHex"]!.stringValue!)
        #expect(Hex.encode(SyncCrypto.sessionKey(groupKey: key, saltA: b, saltB: a, groupId: c["groupId"]!.stringValue!))
            == c["sessionKeyHex"]!.stringValue!)
        #expect(Int64(SyncCrypto.direction(ownSalt: a, peerSalt: b)) == c["initiatorDirection"]!.intValue!)
        #expect(Int64(SyncCrypto.direction(ownSalt: b, peerSalt: a)) == c["responderDirection"]!.intValue!)
    }

    @Test func frames() throws {
        let c = try vector("crypto.json")
        let key = hex(c["sessionKeyHex"])
        var sealers: [UInt8: FrameCipher] = [:]
        var openers: [UInt8: FrameCipher] = [:]
        for d: UInt8 in [1, 2] {
            sealers[d] = FrameCipher(sessionKey: key, direction: d)
            openers[d] = FrameCipher(sessionKey: key, direction: d)
        }
        for f in c["frames"]!.arrayValue! {
            let d = UInt8(f["direction"]!.intValue!)
            let plain = f["message"]!.canonicalBytes
            #expect(Hex.encode(plain) == f["plaintextHex"]!.stringValue!)
            let frame = try sealers[d]!.seal(plain)
            #expect(Hex.encode(frame) == f["frameHex"]!.stringValue!)
            let opened = try openers[d]!.open(Array(hex(f["frameHex"]).dropFirst(4)))
            #expect(opened == plain)
            let message = try SyncMessage.decode(opened)
            #expect(message.json == f["message"]!)
        }
        var tampered = FrameCipher(sessionKey: key, direction: 1)
        #expect(throws: FrameError.decryptFailed) {
            _ = try tampered.open(Array(hex(c["tamperedFrameHex"]).dropFirst(4)))
        }
        // A frame opened with the wrong counter fails.
        var late = FrameCipher(sessionKey: key, direction: 1, counter: 1)
        #expect(throws: FrameError.decryptFailed) {
            _ = try late.open(Array(hex(c["frames"]!.arrayValue![0]["frameHex"]).dropFirst(4)))
        }
    }

    @Test func frameLengthLimit() throws {
        var buffer = StreamBuffer()
        buffer.append([0x00, 0x10, 0x00, 0x01])  // 1 MiB + 1, no body yet
        #expect(throws: FrameError.tooLarge) { _ = try buffer.nextFrame() }
        var ok = StreamBuffer()
        ok.append([0x00, 0x00, 0x00, 0x14] + [UInt8](repeating: 7, count: 10))
        #expect(try ok.nextFrame() == nil)
        ok.append([UInt8](repeating: 7, count: 10))
        #expect(try ok.nextFrame()?.count == 20)
    }

    @Test func invite() throws {
        for c in try vector("invite.json")["cases"]!.arrayValue! {
            let parsed = Invite.parse(c["url"]!.stringValue!)
            if c["valid"] == .bool(true) {
                #expect(parsed?.groupId == c["groupId"]!.stringValue!)
                #expect(parsed.map { Hex.encode($0.groupKey) } == c["keyHex"]!.stringValue!)
                #expect(parsed?.url == c["url"]!.stringValue!)
            } else {
                #expect(parsed == nil, "\(c["url"]!.stringValue!)")
            }
        }
    }

    @Test func receivedOp() throws {
        let v = try vector("received_op.json")
        let group = v["group"]!.stringValue!
        for c in v["cases"]!.arrayValue! {
            #expect(checkReceivedOp(c["op"]!, sessionGroup: group).rawValue == c["result"]!.stringValue!, "\(c.canonical)")
        }
    }
}

// MARK: - Fake links

final class MemoryLink: Link, @unchecked Sendable {
    let incoming: AsyncThrowingStream<[UInt8], Error>
    private let continuation: AsyncThrowingStream<[UInt8], Error>.Continuation
    private let lock = NSLock()
    private var peer: MemoryLink?
    private var rng: SeededRNG
    private var closed = false

    init(seed: UInt64) {
        (incoming, continuation) = AsyncThrowingStream.makeStream()
        rng = SeededRNG(seed: seed)
    }

    static func pair(seed: UInt64) -> (MemoryLink, MemoryLink) {
        let a = MemoryLink(seed: seed), b = MemoryLink(seed: seed &+ 1)
        a.peer = b
        b.peer = a
        return (a, b)
    }

    func send(_ bytes: [UInt8]) async throws {
        let (target, pieces): (MemoryLink?, [[UInt8]]) = lock.withLock {
            var out: [[UInt8]] = []
            var i = 0
            while i < bytes.count {
                let n = min(bytes.count - i, Int.random(in: 1...97, using: &rng))
                out.append(Array(bytes[i..<i + n]))
                i += n
            }
            return (closed ? nil : peer, out)
        }
        guard let target else { throw SessionError.linkClosed }
        for p in pieces { target.continuation.yield(p) }
        await Task.yield()
    }

    func close() {
        let p: MemoryLink? = lock.withLock {
            closed = true
            return peer
        }
        continuation.finish()
        p?.continuation.finish()
    }
}

struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

private let groupId = "00000000-0000-4000-8000-000000000001"
private let groupKey = (0..<32).map { UInt8($0) }

private func deviceId(_ n: Int) -> String { "dddddddd-0000-4000-8000-00000000000\(n)" }

private func options(_ device: String, admission: (any SessionAdmission)? = nil) -> SessionOptions {
    SessionOptions(deviceId: device, now: { 1_761_210_000_000 }, admission: admission, closeGraceSeconds: 1)
}

/// Runs one session between two stores over a fake link. Returns both results.
private func gossip(_ a: InMemoryOpStore, _ b: InMemoryOpStore, seed: UInt64,
                    admissionA: (any SessionAdmission)? = nil, admissionB: (any SessionAdmission)? = nil) async throws
    -> (SessionResult, SessionResult)
{
    let (la, lb) = MemoryLink.pair(seed: seed)
    let da = await a.deviceId, db = await b.deviceId
    let initiator = SyncSession(role: .initiator, link: la, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: a)],
                                options: options(da, admission: admissionA))
    let responder = SyncSession(role: .responder, link: lb, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: b)],
                                options: options(db, admission: admissionB))
    async let r1 = initiator.run()
    async let r2 = responder.run()
    return try await (r1, r2)
}

// MARK: - Op generation

private struct Writer {
    let device: String
    var clock: HLC
    var seq: Int64 = 0

    init(device: String) {
        self.device = device
        clock = HLC(device: device)
    }

    mutating func op(group: String = groupId, type: String, body: JSONValue, wall: Int64) -> JSONValue {
        seq += 1
        return buildOp(group: group, author: device, seq: seq, hlc: clock.send(wall: wall), type: type, body: body)
    }
}

private func memberId(_ n: Int) -> String { "10000000-0000-4000-8000-0000000000" + String(format: "%02d", n) }
private func expenseId(_ d: Int, _ n: Int) -> String { "e0000000-0000-4000-8000-" + String(format: "%04d%08d", d, n) }

private func randomOps(device: Int, count: Int, rng: inout SeededRNG) -> [JSONValue] {
    var w = Writer(device: deviceId(device))
    var out: [JSONValue] = []
    var wall: Int64 = 1_761_210_000_000
    if device == 1 {
        out.append(w.op(type: "group.create", body: .object([
            "name": .string("Trip"), "baseCurrency": .string("EUR"),
            "creatorMemberId": .string(memberId(1)), "creatorName": .string("Ali"),
        ]), wall: wall))
        for m in 2...4 {
            wall += 1
            out.append(w.op(type: "member.add", body: .object(["memberId": .string(memberId(m)), "name": .string("M\(m)")]), wall: wall))
        }
    }
    for n in 0..<count {
        wall += Int64.random(in: 0...50, using: &rng)
        let payer = memberId(Int.random(in: 1...4, using: &rng))
        let amount = Int64.random(in: 100...90_000, using: &rng)
        let members = (1...4).map { JSONValue.string(memberId($0)) }
        let id = expenseId(device, n)
        let money: JSONValue = .object([
            "amountMinor": .int(amount), "currency": .string("EUR"), "rate": .string("1"),
            "split": .object(["mode": .string("equal"), "members": .array(members)]),
        ])
        switch Int.random(in: 0..<6, using: &rng) {
        case 0 where n > 0:
            out.append(w.op(type: "expense.edit", body: .object([
                "expenseId": .string(expenseId(device, Int.random(in: 0..<n, using: &rng))),
                "fields": .object(["title": .string("Edited \(n)")]),
            ]), wall: wall))
        case 1 where n > 0:
            out.append(w.op(type: "expense.delete", body: .object(["expenseId": .string(expenseId(device, Int.random(in: 0..<n, using: &rng)))]), wall: wall))
        case 2:
            out.append(w.op(type: "poll.add", body: .object(["q": .string("future type \(n)")]), wall: wall))
        default:
            out.append(w.op(type: "expense.add", body: .object([
                "expenseId": .string(id), "title": .string("Expense \(n)"), "date": .string("2026-10-20"),
                "category": .string("food"), "paidBy": .string(payer), "money": money,
            ]), wall: wall))
        }
    }
    return out
}

@Suite("Sync session")
struct SessionTests {
    @Test func twoDevicesExchange() async throws {
        var rng = SeededRNG(seed: 1)
        let a = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        for op in randomOps(device: 1, count: 20, rng: &rng) { await a.insertLocal(op) }
        for op in randomOps(device: 2, count: 20, rng: &rng) { await b.insertLocal(op) }
        let (ra, rb) = try await gossip(a, b, seed: 5)
        #expect(await a.versionVector() == b.versionVector())
        #expect(await a.fold() == b.fold())
        #expect(ra.receivedOps == rb.sentOps && ra.sentOps == rb.receivedOps)
        #expect(ra.peerDeviceId == deviceId(2) && rb.peerDeviceId == deviceId(1))
    }

    @Test func emptyDeviceJoins() async throws {
        // A new device holds only the invite: group id and key. It downloads the full log.
        var rng = SeededRNG(seed: 9)
        let invite = Invite.parse(Invite(groupId: groupId, groupKey: groupKey).url)!
        let old = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        for op in randomOps(device: 1, count: 150, rng: &rng) { await old.insertLocal(op) }
        let fresh = InMemoryOpStore(groupId: invite.groupId, deviceId: deviceId(2))
        let (la, lb) = MemoryLink.pair(seed: 3)
        let joiner = SyncSession(role: .initiator, link: la, groups: [SyncGroup(groupId: invite.groupId, groupKey: invite.groupKey, store: fresh)],
                                 options: options(deviceId(2)))
        let host = SyncSession(role: .responder, link: lb, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: old)],
                               options: options(deviceId(1)))
        async let r1 = joiner.run()
        async let r2 = host.run()
        let (joined, _) = try await (r1, r2)
        #expect(joined.receivedOps == 154)
        #expect(await fresh.fold() == old.fold())
        #expect(await fresh.versionVector() == old.versionVector())
    }

    @Test func unknownGroupIsRefused() async throws {
        let a = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let (la, lb) = MemoryLink.pair(seed: 1)
        let initiator = SyncSession(role: .initiator, link: la, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: a)],
                                    options: options(deviceId(1)))
        let other = SyncGroup(groupId: groupId, groupKey: groupKey.map { $0 ^ 1 }, store: b)
        let responder = SyncSession(role: .responder, link: lb, groups: [other], options: options(deviceId(2)))
        async let r1 = capture { try await initiator.run() }
        async let r2 = capture { try await responder.run() }
        let (x, y) = await (r1, r2)
        #expect((y.failure as? SessionError) == .unknownGroup)
        #expect(x.failure != nil)
    }

    @Test func badHashClosesSession() async throws {
        let a = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        var rng = SeededRNG(seed: 4)
        for op in randomOps(device: 1, count: 3, rng: &rng) {
            var o = op.objectValue!
            o["hash"] = .string(String(repeating: "0", count: 64))
            await a.insertLocal(.object(o))
        }
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let (la, lb) = MemoryLink.pair(seed: 2)
        let initiator = SyncSession(role: .initiator, link: la, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: a)],
                                    options: options(deviceId(1)))
        let responder = SyncSession(role: .responder, link: lb, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: b)],
                                    options: options(deviceId(2)))
        async let r1 = capture { try await initiator.run() }
        async let r2 = capture { try await responder.run() }
        let (_, y) = await (r1, r2)
        #expect((y.failure as? SessionError) == .hashMismatch)
        #expect(await b.allOps.isEmpty)
    }

    @Test func forkIsRecordedNotStored() async throws {
        var rng = SeededRNG(seed: 6)
        let ops = randomOps(device: 1, count: 2, rng: &rng)
        let a = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        for op in ops { await a.insertLocal(op) }
        // b holds seq 1 of the same author with a different body.
        let first = ops[0].objectValue!
        let forked = buildOp(group: groupId, author: first["author"]!.stringValue!, seq: 1, hlc: first["hlc"]!.stringValue!,
                             type: "group.rename", body: .object(["name": .string("Other")]))
        await b.insertLocal(forked)
        // a thinks b lacks nothing for seq 1 only if vectors say so: b's vector for the author is 1.
        let summary = try await b.receive([ops[0]], wallMillis: 1_761_210_000_000)
        #expect(summary.forks.count == 1)
        #expect(await b.allOps.contains(forked))
    }

    @Test func silentPeerTimesOut() async throws {
        let a = InMemoryOpStore(groupId: groupId, deviceId: deviceId(1))
        let (la, _) = MemoryLink.pair(seed: 1)
        let timeouts = SessionTimeouts(record: 0.2, hello: 0.2, idle: 0.2, session: 5)
        let session = SyncSession(role: .initiator, link: la, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: a)],
                                  options: SessionOptions(deviceId: deviceId(1), timeouts: timeouts))
        let result = await capture { try await session.run() }
        #expect((result.failure as? SessionError) == .timeout)
    }

    @Test func registryClosesNewerOnHigherDevice() async {
        let registry = SessionRegistry()
        #expect(await registry.admit(peerDevice: deviceId(1), groupId: groupId, myDevice: deviceId(2)))
        // Device 2 is higher than peer 1: it refuses the second connection.
        #expect(await !registry.admit(peerDevice: deviceId(1), groupId: groupId, myDevice: deviceId(2)))
        await registry.release(peerDevice: deviceId(1), groupId: groupId)
        #expect(await registry.admit(peerDevice: deviceId(1), groupId: groupId, myDevice: deviceId(2)))
        // The lower device admits both.
        #expect(await registry.admit(peerDevice: deviceId(2), groupId: groupId, myDevice: deviceId(1)))
        #expect(await registry.admit(peerDevice: deviceId(2), groupId: groupId, myDevice: deviceId(1)))
    }

    @Test(arguments: [UInt64(1), 2, 3, 4, 5, 6])
    func convergenceFuzz(seed: UInt64) async throws {
        var rng = SeededRNG(seed: seed)
        let stores = (1...4).map { InMemoryOpStore(groupId: groupId, deviceId: deviceId($0)) }
        var total = 0
        for (i, store) in stores.enumerated() {
            let ops = randomOps(device: i + 1, count: Int.random(in: 0...40, using: &rng), rng: &rng)
            total += ops.count
            for op in ops { await store.insertLocal(op) }
        }
        var rounds = 0
        func converged() async -> Bool {
            let first = await stores[0].versionVector()
            for s in stores.dropFirst() where await s.versionVector() != first { return false }
            return await stores[0].allOps.count == total
        }
        while await !converged() {
            rounds += 1
            #expect(rounds < 60, "no convergence")
            if rounds >= 60 { break }
            let i = Int.random(in: 0..<4, using: &rng)
            var j = Int.random(in: 0..<3, using: &rng)
            if j >= i { j += 1 }
            _ = try await gossip(stores[i], stores[j], seed: rng.next())
        }
        let expected = await stores[0].fold()
        for s in stores.dropFirst() { #expect(await s.fold() == expected) }
        #expect(await stores[0].allOps.count == total)
    }
}

/// Runs an async closure and keeps its result. (`Result(catching:)` has no async form on older compilers.)
func capture<T: Sendable>(_ body: @Sendable () async throws -> T) async -> Result<T, Error> {
    do { return .success(try await body()) } catch { return .failure(error) }
}

extension Result {
    var failure: Failure? { if case let .failure(e) = self { return e }; return nil }
}

@Suite("Group state")
struct GroupStateTests {
    @Test func decodesEveryFoldVector() throws {
        for c in try vector("fold.json")["cases"]!.arrayValue! {
            let expected = c["expected"]!
            let state = GroupState(foldResult: expected)
            #expect(state.expenses.count == expected["expenses"]!.arrayValue!.count)
            #expect(state.members.count == expected["members"]!.arrayValue!.count)
            #expect(state.settlements.count == expected["settlements"]!.arrayValue!.count)
            #expect(state.balances.count == expected["balances"]!.objectValue!.count)
            #expect(state.suggestedPayments.count == expected["suggestedPayments"]!.arrayValue!.count)
            #expect((state.group != nil) == (expected["group"]!.objectValue != nil))
        }
        #expect(GroupState.empty.group == nil)
    }
}

@Suite("Amounts and drafts")
struct DraftTests {
    @Test func parseAndFormat() {
        #expect(AmountFormat.parse("12.50", currency: "EUR") == 1250)
        #expect(AmountFormat.parse("12,5", currency: "EUR") == 1250)
        #expect(AmountFormat.parse("12", currency: "JPY") == 12)
        #expect(AmountFormat.parse("12.5", currency: "JPY") == nil)
        #expect(AmountFormat.parse("1.2.3", currency: "EUR") == nil)
        #expect(AmountFormat.parse("-5", currency: "EUR") == nil)
        #expect(AmountFormat.parse("", currency: "EUR") == nil)
        #expect(AmountFormat.parse(".5", currency: "EUR") == 50)
        #expect(AmountFormat.parse("1.234", currency: "KWD") == 1234)
        #expect(AmountFormat.format(1250, currency: "EUR", decimalSeparator: ",") == "12,50")
        #expect(AmountFormat.format(5, currency: "EUR") == "0.05")
        #expect(AmountFormat.format(1_234_567, currency: "EUR", decimalSeparator: ",", groupingSeparator: ".") == "12.345,67")
        #expect(AmountFormat.format(1234, currency: "JPY") == "1234")
        #expect(AmountFormat.signed(-1250, currency: "EUR") == "\u{2212}12.50")
        #expect(AmountFormat.signed(1250, currency: "EUR") == "+12.50")
        #expect(AmountFormat.signed(0, currency: "EUR") == "0.00")
    }

    private let a = "10000000-0000-4000-8000-000000000001"
    private let b = "20000000-0000-4000-8000-000000000002"

    @Test func addBodyForEveryMode() throws {
        var d = ExpenseDraft(currency: "USD", date: "2026-10-20", paidBy: a, members: [a, b])
        d.title = " Lunch "
        d.amountText = "30"
        d.rateText = "34.75"
        let equal = try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "TRY")
        #expect(isValidBody(type: "expense.add", body: equal))
        #expect(equal["title"] == .string("Lunch"))
        #expect(equal["money"]?["rate"] == .string("34.75"))

        d.mode = .weights
        d.weights = [a: 2, b: 1]
        #expect(isValidBody(type: "expense.add", body: try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "TRY")))

        d.mode = .exact
        d.exactTexts = [a: "10", b: "15"]
        #expect(throws: DraftError.exactMismatch(remaining: 500)) { try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "TRY") }
        #expect(d.exactRemaining() == 500)
        d.exactTexts[b] = "20"
        #expect(isValidBody(type: "expense.add", body: try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "TRY")))
    }

    @Test func baseCurrencyForcesRateOne() throws {
        var d = ExpenseDraft(currency: "EUR", date: "2026-10-20", paidBy: a, members: [a])
        d.title = "x"
        d.amountText = "1"
        d.rateText = "garbage"
        #expect(try d.money(baseCurrency: "EUR")["rate"] == .string("1"))
        d.currency = "USD"
        #expect(throws: DraftError.rateInvalid) { try d.money(baseCurrency: "EUR") }
        d.rateText = "0"
        #expect(throws: DraftError.rateInvalid) { try d.money(baseCurrency: "EUR") }
    }

    @Test func errors() {
        var d = ExpenseDraft(currency: "EUR", date: "2026-10-20")
        #expect(throws: DraftError.titleEmpty) { try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "EUR") }
        d.title = "x"
        #expect(throws: DraftError.noPayer) { try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "EUR") }
        d.paidBy = a
        d.amountText = "0"
        #expect(throws: DraftError.amountInvalid) { try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "EUR") }
        d.amountText = "5"
        #expect(throws: DraftError.noMembers) { try d.addBody(expenseId: "e0000000-0000-4000-8000-000000000001", baseCurrency: "EUR") }
    }

    @Test func editBodyHasOnlyChangedGroups() throws {
        let ops = try vector("fold.json")["cases"]!.arrayValue!.first { !$0["expected"]!["expenses"]!.arrayValue!.isEmpty }!
        let state = GroupState(foldResult: ops["expected"]!)
        let e = state.expenses[0]
        var d = ExpenseDraft(expense: e)
        #expect(try d.editBody(original: e, baseCurrency: state.group!.baseCurrency) == nil)
        d.title = e.title + "!"
        let body = try #require(try d.editBody(original: e, baseCurrency: state.group!.baseCurrency))
        #expect(body["fields"]?.objectValue?.keys.sorted() == ["title"])
        d.amountText = "1.00"
        let body2 = try #require(try d.editBody(original: e, baseCurrency: state.group!.baseCurrency))
        #expect(body2["fields"]?.objectValue?.keys.sorted() == ["money", "title"])
    }

    @Test func historyIsNewestFirst() throws {
        let ops = try vector("session.json")["ops"]!.arrayValue!
        let h = historyEntries(ops: ops)
        #expect(h.count == ops.count)
        #expect(zip(h, h.dropFirst()).allSatisfy { byteLess($1.hlc, $0.hlc) })
        #expect(h.contains { $0.type == "expense.add" && $0.text == "Bread" })
    }
}
