import Foundation
import Testing
import TripSplitCore
import TripSplitStore
@testable import TripSplitNet

private func vector() throws -> JSONValue {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return try JSONParser.parse(try Data(contentsOf: url.appending(path: "protocol/vectors/ble_chunk.json")))
}

private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }
private func bytes(_ s: String) -> [UInt8] {
    stride(from: 0, to: s.count, by: 2).map { UInt8(s[s.index(s.startIndex, offsetBy: $0)..<s.index(s.startIndex, offsetBy: $0 + 2)], radix: 16)! }
}

@Suite("BLE vectors")
struct BLEVectorTests {
    @Test func uuids() throws {
        let u = try vector()["uuids"]!
        #expect(u["service"]?.stringValue == BLEUUID.service)
        #expect(u["rx"]?.stringValue == BLEUUID.rx)
        #expect(u["tx"]?.stringValue == BLEUUID.tx)
        #expect(u["info"]?.stringValue == BLEUUID.info)
    }

    @Test func chunkSizeFromMTU() throws {
        for c in try vector()["chunkSizes"]!.arrayValue! {
            #expect(BLEChunk.clamp(Int(c["mtu"]!.intValue!) - 3) == Int(c["chunkSize"]!.intValue!))
        }
    }

    @Test func splitAndReassemble() throws {
        for c in try vector()["cases"]!.arrayValue! {
            let message = bytes(c["message"]!.stringValue!)
            let size = Int(c["chunkSize"]!.intValue!)
            var seq = UInt16(c["firstSeq"]!.intValue!)
            let chunks = BLEChunk.split(message, chunkSize: size, seq: &seq)
            #expect(chunks.map(hex) == c["chunks"]!.arrayValue!.map { $0.stringValue! }, "\(c["name"]!.stringValue!)")
            var r = BLEReassembler(receiveLimit: size)
            // The receiver of a wrapped counter starts at the first seq of the case: feed a fresh one.
            var out: [UInt8]?
            let firstSeq = UInt16(c["firstSeq"]!.intValue!)
            var receiver = BLEReassembler(receiveLimit: size)
            if firstSeq != 0 {
                // Move the receiver to the right counter with one single-chunk message per step.
                var s: UInt16 = 0
                while s != firstSeq {
                    var tmp = s
                    for ch in BLEChunk.split([1], chunkSize: 20, seq: &tmp) { _ = try receiver.receive(ch) }
                    s = tmp
                }
            }
            r = receiver
            for ch in chunks { out = try r.receive(ch) ?? out }
            #expect(out == message)
        }
    }

    @Test func errors() throws {
        for e in try vector()["errors"]!.arrayValue! {
            var r = BLEReassembler(receiveLimit: Int(e["receiveLimit"]!.intValue!))
            let expected = e["error"]!.stringValue!
            let at = Int(e["atChunk"]!.intValue!)
            var chunks: [[UInt8]] = []
            if let list = e["chunks"]?.arrayValue {
                chunks = list.map { bytes($0.stringValue!) }
            } else if let rep = e["repeat"] {
                let n = Int(rep["count"]!.intValue!), size = Int(rep["payloadBytes"]!.intValue!)
                for i in 0..<n {
                    let flags = UInt8(i == 0 ? rep["firstFlags"]!.intValue! : rep["flags"]!.intValue!)
                    chunks.append([flags, UInt8(i >> 8), UInt8(i & 255)] + [UInt8](repeating: 0, count: size))
                }
            }
            var failedAt: Int?
            var code: String?
            for (i, ch) in chunks.enumerated() {
                do { _ = try r.receive(ch) } catch let err as BLEChunkError {
                    failedAt = i
                    code = err.rawValue
                    break
                }
            }
            #expect(code == expected, "\(expected)")
            #expect(failedAt == at, "\(expected)")
        }
    }

    @Test func infoValue() throws {
        let i = try vector()["info"]!
        let info = BLEInfo(deviceId: i["deviceId"]!.stringValue!, maxChunk: 185, roles: ["peripheral", "central"],
                           tags: ["703d549ece92e2d457f3ef9c"])
        #expect(String(decoding: info.data, as: UTF8.self) == i["text"]!.stringValue!)
        #expect(BLEInfo.parse(info.data) == info)
        #expect(BLEInfo.parse(Data("{}".utf8)) == nil)
        #expect(BLEInfo.parse(Data(#"{"deviceId":"x"}"#.utf8)) == nil)
    }

    @Test func roleRule() {
        let low = "aaaaaaaa-0000-4000-8000-000000000001", high = "bbbbbbbb-0000-4000-8000-000000000002"
        let both = BLEInfo(deviceId: high, maxChunk: 185, roles: ["central", "peripheral"], tags: [])
        #expect(BLERole.localConnects(localDevice: low, peer: both))
        let lowPeer = BLEInfo(deviceId: low, maxChunk: 185, roles: ["central", "peripheral"], tags: [])
        #expect(!BLERole.localConnects(localDevice: high, peer: lowPeer))
        let peripheralOnly = BLEInfo(deviceId: low, maxChunk: 185, roles: ["peripheral"], tags: [])
        #expect(BLERole.localConnects(localDevice: high, peer: peripheralOnly))
    }
}

// MARK: - Links

/// Delivers chunks to the other link, one at a time, with a yield in between (flow control in miniature).
private final class WireWriter: BLEChunkWriter, @unchecked Sendable {
    private let lock = NSLock()
    private var peer: BLELink?
    private var _sizes: [Int] = []
    var sizes: [Int] { lock.withLock { _sizes } }
    func connect(_ link: BLELink) { lock.withLock { peer = link } }
    func write(_ chunk: [UInt8]) async throws {
        let target = lock.withLock { () -> BLELink? in _sizes.append(chunk.count); return peer }
        await Task.yield()
        target?.receive(chunk)
    }
}

private final class LockedInt: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Int
    init(_ v: Int) { _value = v }
    var value: Int {
        get { lock.withLock { _value } }
        set { lock.withLock { _value = newValue } }
    }
}

private let groupId = "00000000-0000-4000-8000-000000000001"
private let groupKey = (0..<32).map { UInt8($0) }

@Suite("BLE link", .timeLimit(.minutes(1)))
struct BLELinkTests {
    @Test func sessionOverChunkedLinks() async throws {
        let a = InMemoryOpStore(groupId: groupId, deviceId: "aaaaaaaa-0000-4000-8000-000000000001")
        let b = InMemoryOpStore(groupId: groupId, deviceId: "bbbbbbbb-0000-4000-8000-000000000002")
        var w = Writer2(device: "aaaaaaaa-0000-4000-8000-000000000001")
        await a.insertLocal(w.op(type: "group.create", body: .object([
            "name": .string("Trip"), "baseCurrency": .string("EUR"),
            "creatorMemberId": .string("10000000-0000-4000-8000-000000000001"), "creatorName": .string("Ali")])))
        for n in 0..<150 {
            await a.insertLocal(w.op(type: "member.add", body: .object([
                "memberId": .string(String(format: "20000000-0000-4000-8000-%012d", n)), "name": .string("Member number \(n)")])))
        }

        let toPeripheral = WireWriter(), toCentral = WireWriter()
        let central = BLELink(writer: toPeripheral, receiveLimit: 512, initialChunkSize: 182, isPeripheral: false)
        let peripheral = BLELink(writer: toCentral, receiveLimit: 512, initialChunkSize: 20, isPeripheral: true, txLimit: 185)
        toPeripheral.connect(peripheral)
        toCentral.connect(central)

        let initiator = SyncSession(role: .initiator, link: central, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: b)],
                                    options: SessionOptions(deviceId: await b.deviceId, maxChunk: 512, timeouts: .ble))
        let responder = SyncSession(role: .responder, link: peripheral, groups: [SyncGroup(groupId: groupId, groupKey: groupKey, store: a)],
                                    options: SessionOptions(deviceId: await a.deviceId, maxChunk: 512, closeGraceSeconds: 1, timeouts: .ble))
        async let r1 = initiator.run()
        async let r2 = responder.run()
        let (joined, _) = try await (r1, r2)
        #expect(joined.receivedOps == 151)
        #expect(await a.fold() == b.fold())
        // The peripheral started with chunk size 20 for its record, then moved up to min(185, 512).
        #expect(peripheral.currentSendChunkSize == 185)
        #expect(toCentral.sizes.first == 20)
        #expect(toCentral.sizes.max() == 185)
        #expect(toPeripheral.sizes.max() == 182)
    }

    @Test func chunkSizeGrowsWhenThePlatformLimitGrows() async throws {
        let limit = LockedInt(20)
        let toB = WireWriter(), toA = WireWriter()
        let a = BLELink(writer: toB, receiveLimit: 512, initialChunkSize: 20, isPeripheral: false,
                        limitProvider: { limit.value }, peerCap: 100)
        let b = BLELink(writer: toA, receiveLimit: 512, initialChunkSize: 20, isPeripheral: true)
        toB.connect(b)
        toA.connect(a)
        let message = [UInt8](repeating: 7, count: 400)
        try await a.send(message)  // limit is 20: chunks of 20
        #expect(toB.sizes.max() == 20)
        limit.value = 182          // the MTU exchange finished
        try await a.send(message)
        #expect(a.currentSendChunkSize == 100)  // capped by the peer limit
        #expect(toB.sizes.suffix(4).first == 100)
        limit.value = 20           // a lower reading never shrinks the chunk
        try await a.send(message)
        #expect(a.currentSendChunkSize == 100)
        #expect(toB.sizes.suffix(4).first == 100)
        // The receiver got three whole messages.
        var it = b.incoming.makeAsyncIterator()
        for _ in 0..<3 { #expect(try await it.next() == message) }
    }

    @Test func abortClosesThePeer() async throws {
        let toB = WireWriter(), toA = WireWriter()
        let a = BLELink(writer: toB, receiveLimit: 512, initialChunkSize: 20, isPeripheral: false)
        let b = BLELink(writer: toA, receiveLimit: 512, initialChunkSize: 20, isPeripheral: true)
        toB.connect(b)
        toA.connect(a)
        try await a.send([1, 2, 3])
        var it = b.incoming.makeAsyncIterator()
        #expect(try await it.next() == [1, 2, 3])
        a.close()
        await #expect(throws: BLELinkError.chunk(.abort)) { _ = try await it.next() }
        await #expect(throws: BLELinkError.closed) { try await a.send([9]) }
    }

    @Test func unfinishedMessageTimesOut() async throws {
        let w = WireWriter()
        let link = BLELink(writer: w, receiveLimit: 512, initialChunkSize: 20, isPeripheral: true, unfinishedTimeout: 0.1)
        link.receive([BLEChunk.first, 0, 0, 1, 2, 3])
        var it = link.incoming.makeAsyncIterator()
        await #expect(throws: BLELinkError.unfinishedMessage) { _ = try await it.next() }
    }

    @Test func badChunkFailsTheLink() async throws {
        let w = WireWriter()
        let link = BLELink(writer: w, receiveLimit: 20, initialChunkSize: 20, isPeripheral: true)
        link.receive([0x03, 0x00, 0x05, 1])  // wrong seq
        var it = link.incoming.makeAsyncIterator()
        await #expect(throws: BLELinkError.chunk(.badSeq)) { _ = try await it.next() }
    }
}

private struct Writer2 {
    let device: String
    var clock: HLC
    var seq: Int64 = 0
    init(device: String) {
        self.device = device
        clock = HLC(device: device)
    }
    mutating func op(type: String, body: JSONValue) -> JSONValue {
        seq += 1
        return buildOp(group: groupId, author: device, seq: seq, hlc: clock.send(wall: 1_761_210_000_000), type: type, body: body)
    }
}
