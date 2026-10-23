// BLE chunking, the info value and the GATT UUIDs (SPEC §10.2).

import Foundation
import TripSplitCore

public enum BLEUUID {
    public static let service = "717ed7c0-0ed4-4251-a5b1-606fa1771f42"
    public static let rx = "33f56749-2c8f-4bb9-9d1b-3b27fa731a8f"
    public static let tx = "bd1e5af2-f0f9-49f7-9128-63c490a78986"
    public static let info = "c9698bc0-ddbd-4f16-8bd3-9c7d34135fba"
}

public enum BLEChunkError: String, Error, Equatable, Sendable {
    case shortChunk = "short_chunk"
    case tooLarge = "too_large"
    case badFlags = "bad_flags"
    case badSeq = "bad_seq"
    case abort
    case emptyChunk = "empty_chunk"
    case unexpectedFirst = "unexpected_first"
    case unexpectedContinuation = "unexpected_continuation"
    case messageTooBig = "message_too_big"
}

public enum BLEChunk {
    public static let first: UInt8 = 0x01
    public static let last: UInt8 = 0x02
    public static let abortFlag: UInt8 = 0x04
    public static let headerLength = 3
    public static let minChunkSize = 20
    public static let maxChunkSize = 512
    public static let maxMessage = (1 << 20) + 4

    /// `chunkSize` is the full ATT value. For a link: max(20, min(512, ATT MTU − 3)).
    public static func clamp(_ size: Int) -> Int { max(minChunkSize, min(maxChunkSize, size)) }

    /// Splits one message. `seq` is the counter of the direction and moves on by one for each chunk.
    public static func split(_ message: [UInt8], chunkSize: Int, seq: inout UInt16) -> [[UInt8]] {
        precondition(!message.isEmpty, "a message is never empty")
        let payload = clamp(chunkSize) - headerLength
        var out: [[UInt8]] = []
        var offset = 0
        while offset < message.count {
            let end = min(offset + payload, message.count)
            var flags: UInt8 = 0
            if offset == 0 { flags |= first }
            if end == message.count { flags |= last }
            out.append([flags, UInt8(seq >> 8), UInt8(seq & 0xFF)] + message[offset..<end])
            seq &+= 1
            offset = end
        }
        return out
    }

    public static func abortChunk(seq: inout UInt16) -> [UInt8] {
        defer { seq &+= 1 }
        return [abortFlag, UInt8(seq >> 8), UInt8(seq & 0xFF)]
    }
}

/// Joins the chunks of one direction into messages. The checks run in the order of SPEC §10.2.4.
public struct BLEReassembler: Sendable {
    public let receiveLimit: Int
    private var expectedSeq: UInt16 = 0
    private var buffer: [UInt8] = []

    public init(receiveLimit: Int) { self.receiveLimit = receiveLimit }

    public var hasOpenMessage: Bool { !buffer.isEmpty }

    /// Returns a complete message, or nil while the message is open. Throws on the first failed check.
    public mutating func receive(_ chunk: [UInt8]) throws -> [UInt8]? {
        guard chunk.count >= BLEChunk.headerLength else { throw BLEChunkError.shortChunk }
        guard chunk.count <= receiveLimit else { throw BLEChunkError.tooLarge }
        let flags = chunk[0]
        let payload = chunk.dropFirst(BLEChunk.headerLength)
        let isAbort = flags & BLEChunk.abortFlag != 0
        if flags & 0xF8 != 0 { throw BLEChunkError.badFlags }
        if isAbort, flags & (BLEChunk.first | BLEChunk.last) != 0 || !payload.isEmpty { throw BLEChunkError.badFlags }
        let seq = UInt16(chunk[1]) << 8 | UInt16(chunk[2])
        guard seq == expectedSeq else { throw BLEChunkError.badSeq }
        if isAbort { throw BLEChunkError.abort }
        guard !payload.isEmpty else { throw BLEChunkError.emptyChunk }
        let isFirst = flags & BLEChunk.first != 0
        if isFirst != buffer.isEmpty {
            throw isFirst ? BLEChunkError.unexpectedFirst : BLEChunkError.unexpectedContinuation
        }
        guard buffer.count + payload.count <= BLEChunk.maxMessage else { throw BLEChunkError.messageTooBig }
        buffer.append(contentsOf: payload)
        expectedSeq &+= 1
        if flags & BLEChunk.last != 0 {
            defer { buffer = [] }
            return buffer
        }
        return nil
    }
}

/// The `info` characteristic (SPEC §10.2.2).
public struct BLEInfo: Equatable, Sendable {
    public var deviceId: String
    public var maxChunk: Int
    public var protocolVersion: Int
    public var roles: [String]
    public var tags: [String]

    public init(deviceId: String, maxChunk: Int, protocolVersion: Int = 1, roles: [String], tags: [String]) {
        self.deviceId = deviceId
        self.maxChunk = maxChunk
        self.protocolVersion = protocolVersion
        self.roles = roles.sorted()
        self.tags = Array(tags.prefix(Advert.maxTags))
    }

    public var data: Data {
        let json: JSONValue = .object([
            "deviceId": .string(deviceId), "maxChunk": .int(Int64(maxChunk)), "protocolVersion": .int(Int64(protocolVersion)),
            "roles": .array(roles.map { .string($0) }), "tags": .array(tags.map { .string($0) }),
        ])
        return Data(json.canonicalBytes)
    }

    /// nil when the value does not parse: the reader disconnects.
    public static func parse(_ data: Data) -> BLEInfo? {
        guard data.count <= 512, let v = try? JSONParser.parse(data), let device = v["deviceId"]?.stringValue, isValidId(device),
              let chunk = v["maxChunk"]?.intValue, (20...512).contains(chunk),
              let version = v["protocolVersion"]?.intValue, version >= 1,
              let roles = v["roles"]?.arrayValue?.compactMap({ $0.stringValue }),
              let rawTags = v["tags"]?.arrayValue
        else { return nil }
        let tags = rawTags.compactMap { $0.stringValue }
        guard tags.count == rawTags.count, tags.count <= Advert.maxTags, Advert.decodeTags(tags.joined(separator: ",")).count == tags.count,
              roles.allSatisfy({ $0 == "central" || $0 == "peripheral" })
        else { return nil }
        return BLEInfo(deviceId: device, maxChunk: Int(chunk), protocolVersion: Int(version), roles: roles, tags: tags)
    }
}

public enum BLERole {
    /// SPEC §10.2.3: when both sides can be central, the lower deviceId is central. Returns true when
    /// the local device should connect now.
    public static func localConnects(localDevice: String, peer: BLEInfo) -> Bool {
        guard peer.roles.contains("central") else { return true }
        // The local device is not central when it is greater than the peer: the peer is smaller.
        return !peer.deviceId.utf8.lexicographicallyPrecedes(localDevice.utf8)
    }
}
