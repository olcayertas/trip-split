import Foundation
import Testing
@testable import TripSplitCore

/// Loads protocol/vectors/<name> from the repo root (five levels above this file).
private func vector(_ name: String) throws -> JSONValue {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    let data = try Data(contentsOf: url.appending(path: "protocol/vectors/\(name)"))
    return try JSONParser.parse(data)
}

private func hexBytes(_ bytes: [UInt8]) -> String {
    bytes.map { ($0 < 16 ? "0" : "") + String($0, radix: 16) }.joined()
}

@Suite("Golden vectors")
struct VectorTests {
    @Test func canonicalJSON() throws {
        let v = try vector("canonical_json.json")
        for c in v["cases"]!.arrayValue! {
            let input = c["input"]!
            #expect(input.canonical == c["canonical"]!.stringValue!)
            #expect(hexBytes(input.canonicalBytes) == c["utf8Hex"]!.stringValue!)
            // Round trip: parsing the canonical text gives the same value.
            #expect(try JSONParser.parse(input.canonical) == input)
        }
        for c in v["opHash"]!.arrayValue! {
            #expect(opHash(c["op"]!) == c["hash"]!.stringValue!)
        }
        for c in v["rejected"]!.arrayValue! {
            #expect(throws: JSONError.self) { try JSONParser.parse(c["json"]!.stringValue!) }
        }
    }

    @Test func currencyTable() throws {
        let v = try vector("currencies.json")
        let table = v["currencies"]!.objectValue!.mapValues { Int($0.intValue!) }
        #expect(table == currencies)
    }

    @Test func money() throws {
        let v = try vector("money.json")
        for c in v["convert"]!.arrayValue! {
            let run = {
                try convert(amountMinor: c["amountMinor"]!.intValue!, currency: c["currency"]!.stringValue!,
                            rate: c["rate"]!.stringValue!, base: c["base"]!.stringValue!)
            }
            if let expected = c["baseMinor"]?.intValue {
                #expect((try? run()) == expected, "\(c.canonical)")
            } else {
                #expect(throws: MoneyError.outOfRange) { try run() }
            }
        }
        for c in v["rates"]!.arrayValue! {
            let rate = c["rate"]!.stringValue!
            if c["valid"] == .bool(true) {
                let parsed = try parseRate(rate)
                #expect(String(parsed.mantissa) == c["mantissa"]!.stringValue!)
                #expect(Int64(parsed.scale) == c["scale"]!.intValue!)
            } else {
                #expect(throws: MoneyError.badRate, "rate \(rate)") { try parseRate(rate) }
            }
        }
    }

    @Test func split() throws {
        for c in try vector("split.json")["cases"]!.arrayValue! {
            let shares = splitShares(baseTotal: c["baseTotal"]!.intValue!, split: c["split"]!)
            #expect(JSONValue.object(shares.mapValues { .int($0) }) == c["shares"]!)
            #expect(shares.values.reduce(0, +) == c["baseTotal"]!.intValue!)
        }
    }

    @Test func settlement() throws {
        for c in try vector("settlement.json")["cases"]!.arrayValue! {
            let balances = c["balances"]!.objectValue!.mapValues { $0.intValue! }
            let payments: [JSONValue] = settle(balances).map {
                .object(["from": .string($0.from), "to": .string($0.to), "amountMinor": .int($0.amountMinor)])
            }
            #expect(JSONValue.array(payments) == c["payments"]!)
        }
    }

    @Test func hlc() throws {
        let v = try vector("hlc.json")
        for seq in v["sequences"]!.arrayValue! {
            var clock = HLC(device: seq["device"]!.stringValue!, millis: seq["startMillis"]!.intValue!,
                            counter: Int(seq["startCounter"]!.intValue!))
            for step in seq["steps"]!.arrayValue! {
                let wall = step["wall"]!.intValue!
                if step["event"]!.stringValue == "send" {
                    #expect(clock.send(wall: wall) == step["stamp"]!.stringValue!, "\(seq["name"]!.canonical)")
                } else {
                    let refused = clock.receive(step["remote"]!.stringValue!, wall: wall)
                    #expect(.bool(refused) == step["refused"]!)
                    #expect(clock.stamp == step["state"]!.stringValue!, "\(seq["name"]!.canonical)")
                }
            }
        }
        let sorted = v["sortInput"]!.arrayValue!.compactMap { $0.stringValue }.sorted(by: byteLess)
        #expect(sorted == v["sorted"]!.arrayValue!.compactMap { $0.stringValue })
    }

    @Test func foldCases() throws {
        for c in try vector("fold.json")["cases"]!.arrayValue! {
            let ops = c["ops"]!.arrayValue!
            let group = c["group"]!.stringValue!
            for op in ops { #expect(opHash(op) == op["hash"]!.stringValue!) }
            let forward = fold(groupId: group, ops: ops)
            let backward = fold(groupId: group, ops: ops.reversed())
            #expect(forward == c["expected"]!, "fold case: \(c["name"]!.canonical)")
            #expect(backward == forward, "order dependence in: \(c["name"]!.canonical)")
            if forward != c["expected"]! {
                Issue.record("got      \(forward.canonical)\nexpected \(c["expected"]!.canonical)")
            }
        }
    }

    @Test func session() throws {
        let v = try vector("session.json")
        let all = v["ops"]!.arrayValue!
        let byRef = Dictionary(uniqueKeysWithValues: all.map { (opRef($0), $0) })
        for c in v["cases"]!.arrayValue! {
            let a = c["sideA"]!.arrayValue!.map { byRef[$0.stringValue!]! }
            let b = c["sideB"]!.arrayValue!.map { byRef[$0.stringValue!]! }
            let va = versionVector(a), vb = versionVector(b)
            #expect(JSONValue.object(va.mapValues { .int($0) }) == c["vectorA"]!)
            #expect(JSONValue.object(vb.mapValues { .int($0) }) == c["vectorB"]!)
            #expect(JSONValue.array(missingFor(peerVector: vb, ops: a).map { .string($0) }) == c["aSends"]!)
            #expect(JSONValue.array(missingFor(peerVector: va, ops: b).map { .string($0) }) == c["bSends"]!)
        }
    }
}
