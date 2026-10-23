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

    public var id: String { kind.rawValue + "|" + (isProbe ? "probe|" : "") + (deviceId.isEmpty ? address : deviceId) }

    public init(deviceId: String, kind: TransportKind, tags: [String], address: String, isProbe: Bool = false) {
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

/// Text form of a tag in TXT records and info characteristics: 16 base64url characters.
public func tagText(_ tag: [UInt8]) -> String { Base64URL.encode(tag) }

/// The TXT record values of the LAN service (proposal for SPEC §10).
public enum Advert {
    public static let maxTags = 14

    public static func encodeTags(_ tags: [[UInt8]]) -> String {
        tags.prefix(maxTags).map(tagText).joined(separator: ",")
    }

    public static func decodeTags(_ text: String) -> [String] {
        text.split(separator: ",").map(String.init).filter { $0.utf8.count == 16 && Base64URL.decode($0)?.count == 12 }
    }
}
