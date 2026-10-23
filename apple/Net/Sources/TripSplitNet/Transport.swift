// What the coordinator needs from a transport (Wi-Fi LAN now, Bluetooth in phase 6).

import Foundation
import TripSplitCore

public enum TransportKind: String, Sendable, Hashable { case wifi, bluetooth }

public struct Peer: Sendable, Hashable, Identifiable {
    /// The deviceId when the transport knows it. Empty for a gateway probe.
    public let deviceId: String
    public let kind: TransportKind
    /// Discovery tags of the groups the peer holds, base64url, 16 characters each.
    public let tags: [String]
    /// Transport-specific key to connect again (an endpoint description).
    public let address: String
    /// True for the fixed-port fallback: the peer is not known yet, so the coordinator tries each group.
    public let isProbe: Bool
    /// False while the local device waits for the peer to connect first (SPEC §10.2.3).
    public let initiate: Bool

    public var id: String { kind.rawValue + "|" + (isProbe ? "probe|" : "") + (deviceId.isEmpty ? address : deviceId) }

    public init(deviceId: String, kind: TransportKind, tags: [String], address: String, isProbe: Bool = false, initiate: Bool = true) {
        self.initiate = initiate
        self.deviceId = deviceId
        self.kind = kind
        self.tags = tags
        self.address = address
        self.isProbe = isProbe
    }
}

public enum TransportEvent: Sendable {
    /// The full list of peers in range now.
    case peers([Peer])
    /// A peer connected to us. The coordinator runs a responder session on it.
    case inbound(any Link, kind: TransportKind)
    /// A problem the user can act on, for example a missing permission.
    case problem(String)
}

public protocol PeerTransport: Sendable {
    var kind: TransportKind { get }
    var events: AsyncStream<TransportEvent> { get }
    /// Starts to advertise and to look for peers. `tags` are the discovery tags of our groups.
    func start(deviceId: String, tags: [[UInt8]]) async
    func stop() async
    func connect(to peer: Peer) async throws -> any Link
}

public enum TransportError: Error, Equatable {
    case timeout
    case cancelled
    case failed(String)
}

/// Text form of a tag in TXT records and `info` values: 24 lowercase hex characters (SPEC §10.1.1).
public func tagText(_ tag: [UInt8]) -> String {
    let digits = Array("0123456789abcdef".utf8)
    var out: [UInt8] = []
    for b in tag {
        out.append(digits[Int(b >> 4)])
        out.append(digits[Int(b & 15)])
    }
    return String(decoding: out, as: UTF8.self)
}

/// The TXT record of the LAN service (SPEC §10.1.1).
public enum Advert {
    public static let maxTags = 8
    public static let maxTagValueBytes = 199

    /// At most 8 tags, joined with ",". The caller passes the groups used last first.
    public static func encodeTags(_ tags: [[UInt8]]) -> String {
        tags.prefix(maxTags).map(tagText).joined(separator: ",")
    }

    /// Ignores a tag that is not 24 lowercase hex characters.
    public static func decodeTags(_ text: String) -> [String] {
        text.split(separator: ",").map(String.init).filter { t in
            t.utf8.count == 24 && t.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
        }
    }
}
