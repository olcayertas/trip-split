// Invite link (SPEC §9): tripsplit://join?g=<groupId>#k=<base64url key, no padding>

public struct Invite: Equatable, Sendable {
    public let groupId: String
    public let groupKey: [UInt8]

    public init(groupId: String, groupKey: [UInt8]) {
        self.groupId = groupId
        self.groupKey = groupKey
    }

    private static let prefix = "tripsplit://join?g="

    public var url: String { Self.prefix + groupId + "#k=" + Base64URL.encode(groupKey) }

    /// Accepts exactly the form of the spec. Anything else gives nil.
    public static func parse(_ text: String) -> Invite? {
        guard text.hasPrefix(prefix) else { return nil }
        let rest = Array(text.dropFirst(prefix.count).utf8)
        guard rest.count == 36 + 3 + 43 else { return nil }
        let id = String(decoding: rest[0..<36], as: UTF8.self)
        guard isUUID(id), rest[36..<39].elementsEqual("#k=".utf8) else { return nil }
        guard let key = Base64URL.decode(String(decoding: rest[39...], as: UTF8.self)), key.count == 32 else {
            return nil
        }
        return Invite(groupId: id, groupKey: key)
    }
}
