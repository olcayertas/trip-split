// Wire messages (SPEC §8.2) and the receive-side envelope check (SPEC §8.3 rule 3).

public struct Hello: Equatable, Sendable {
    public var protocolVersion: Int64
    public var deviceId: String
    public var appVersion: String
    public var groupId: String
    public var vector: [String: Int64]
    public var wallMillis: Int64
    public var maxChunk: Int64

    public init(protocolVersion: Int64 = 1, deviceId: String, appVersion: String, groupId: String,
                vector: [String: Int64], wallMillis: Int64, maxChunk: Int64) {
        self.protocolVersion = protocolVersion
        self.deviceId = deviceId
        self.appVersion = appVersion
        self.groupId = groupId
        self.vector = vector
        self.wallMillis = wallMillis
        self.maxChunk = maxChunk
    }
}

public enum SyncMessage: Equatable, Sendable {
    case hello(Hello)
    case ops([JSONValue])
    case done([String: Int64])

    public static let maxOpsPerMessage = 64
    public static let maxOpsPlaintext = 256 * 1024

    public var json: JSONValue {
        switch self {
        case let .hello(h):
            return .object([
                "t": .string("hello"), "protocolVersion": .int(h.protocolVersion), "deviceId": .string(h.deviceId),
                "appVersion": .string(h.appVersion), "groupId": .string(h.groupId),
                "vector": Self.vectorJSON(h.vector), "wallMillis": .int(h.wallMillis), "maxChunk": .int(h.maxChunk),
            ])
        case let .ops(ops):
            return .object(["t": .string("ops"), "ops": .array(ops)])
        case let .done(vector):
            return .object(["t": .string("done"), "vector": Self.vectorJSON(vector)])
        }
    }

    public var plaintext: [UInt8] { json.canonicalBytes }

    static func vectorJSON(_ v: [String: Int64]) -> JSONValue { .object(v.mapValues { .int($0) }) }

    static func vector(_ v: JSONValue?) throws -> [String: Int64] {
        guard let o = v?.objectValue else { throw MessageError.shape }
        var out: [String: Int64] = [:]
        for (k, n) in o {
            guard isUUID(k), let value = n.intValue, value >= 0 else { throw MessageError.shape }
            out[k] = value
        }
        return out
    }

    /// Decodes one frame plaintext. Unknown keys are ignored; an unknown `t` is an error.
    public static func decode(_ bytes: [UInt8]) throws -> SyncMessage {
        guard let v = try? JSONParser.parse(bytes), v.objectValue != nil, let t = v["t"]?.stringValue else {
            throw MessageError.shape
        }
        switch t {
        case "hello":
            guard let version = v["protocolVersion"]?.intValue, version >= 1,
                  let device = v["deviceId"]?.stringValue, isUUID(device),
                  let app = v["appVersion"]?.stringValue,
                  let group = v["groupId"]?.stringValue, isUUID(group),
                  let wall = v["wallMillis"]?.intValue,
                  let chunk = v["maxChunk"]?.intValue, chunk > 0
            else { throw MessageError.shape }
            return .hello(Hello(protocolVersion: version, deviceId: device, appVersion: app, groupId: group,
                                vector: try vector(v["vector"]), wallMillis: wall, maxChunk: chunk))
        case "ops":
            guard bytes.count <= maxOpsPlaintext, let ops = v["ops"]?.arrayValue,
                  (1...maxOpsPerMessage).contains(ops.count)
            else { throw MessageError.shape }
            return .ops(ops)
        case "done":
            return .done(try vector(v["vector"]))
        default:
            throw MessageError.unknownType
        }
    }
}

public enum MessageError: Error, Equatable {
    case shape
    case unknownType
}

public enum ReceivedOpVerdict: String, Equatable, Sendable {
    case ok, group, shape, hash
}

private let envelopeKeys: Set<String> = ["v", "group", "author", "seq", "hlc", "type", "body", "hash"]

private func isHash(_ s: String) -> Bool {
    s.utf8.count == 64 && s.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
}

/// Envelope check for an op that arrives in a session. `hash` means: close the session.
/// `group` and `shape` mean: drop this op only. The body is not read, so unknown ops pass.
public func checkReceivedOp(_ op: JSONValue, sessionGroup: String) -> ReceivedOpVerdict {
    guard let o = op.objectValue, Set(o.keys) == envelopeKeys,
          let v = o["v"]?.intValue, v >= 1,
          let group = o["group"]?.stringValue,
          let author = o["author"]?.stringValue, isUUID(author),
          let seq = o["seq"]?.intValue, seq >= 1,
          let hlc = o["hlc"]?.stringValue, let stamp = HLC.parse(hlc), stamp.device == author,
          o["type"]?.stringValue != nil,
          o["body"]?.objectValue != nil,
          let hash = o["hash"]?.stringValue, isHash(hash)
    else { return .shape }
    guard group == sessionGroup else { return .group }
    return opHash(op) == hash ? .ok : .hash
}
