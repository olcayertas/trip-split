// Names, keys and batches of the internet relay (SPEC §10.4.1, §10.4.2).

import CryptoKit

public enum RelayCrypto {
    public static let nonceLength = 12
    public static let maxOpsPerBatch = 64
    public static let maxPlaintext = 256 * 1024

    private static func mac(_ groupKey: [UInt8], _ message: String) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: Array(message.utf8), using: SymmetricKey(data: groupKey)))
    }

    /// 32 lowercase hex characters: the first 16 bytes of HMAC-SHA256(groupKey, "tripsplit/v1/relay/mailbox").
    public static func mailboxId(groupKey: [UInt8]) -> String {
        Hex.encode(mac(groupKey, "tripsplit/v1/relay/mailbox").prefix(16))
    }

    /// 64 lowercase hex characters: HMAC-SHA256(groupKey, "tripsplit/v1/relay/token").
    public static func relayToken(groupKey: [UInt8]) -> String {
        Hex.encode(mac(groupKey, "tripsplit/v1/relay/token"))
    }

    public static func batchKey(groupKey: [UInt8], groupId: String) -> [UInt8] {
        SyncCrypto.hkdf(ikm: groupKey, salt: [], info: Array(("tripsplit/v1/relay/batch/" + groupId).utf8), length: 32)
    }

    /// The plaintext of a batch: canonical JSON `{"ops":[...]}`.
    public static func plaintext(ops: [JSONValue]) -> [UInt8] {
        JSONValue.object(["ops": .array(ops)]).canonicalBytes
    }

    /// `nonce || ciphertext || tag`. The AAD is the mailbox id as ASCII. The nonce is random unless a test fixes it.
    public static func seal(ops: [JSONValue], batchKey: [UInt8], mailboxId: String, nonce: [UInt8]? = nil) throws -> [UInt8] {
        let iv = try AES.GCM.Nonce(data: nonce ?? (0..<nonceLength).map { _ in UInt8.random(in: 0...255) })
        let box = try AES.GCM.seal(plaintext(ops: ops), using: SymmetricKey(data: batchKey), nonce: iv,
                                   authenticating: Array(mailboxId.utf8))
        return Array(iv) + Array(box.ciphertext) + Array(box.tag)
    }

    /// The ops of a blob, or nil when the blob does not decrypt or the plaintext is not a valid batch.
    public static func open(blob: [UInt8], batchKey: [UInt8], mailboxId: String) -> [JSONValue]? {
        guard blob.count >= nonceLength + SyncCrypto.gcmTagLength else { return nil }
        let cut = blob.count - SyncCrypto.gcmTagLength
        guard let iv = try? AES.GCM.Nonce(data: blob[0..<nonceLength]),
              let box = try? AES.GCM.SealedBox(nonce: iv, ciphertext: blob[nonceLength..<cut], tag: blob[cut...]),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: batchKey), authenticating: Array(mailboxId.utf8)),
              plain.count <= maxPlaintext,
              let json = try? JSONParser.parse(Array(plain)), json.objectValue != nil,
              let ops = json["ops"]?.arrayValue, (1...maxOpsPerBatch).contains(ops.count)
        else { return nil }
        return ops
    }

    /// Cuts ops (already in §7 order) into batches of at most 64 ops and 256 KiB of plaintext.
    public static func batches(of ops: [JSONValue]) -> [[JSONValue]] {
        let frame = plaintext(ops: []).count
        var out: [[JSONValue]] = []
        var current: [JSONValue] = []
        var size = frame
        for op in ops {
            let cost = op.canonicalBytes.count + (current.isEmpty ? 0 : 1)
            if !current.isEmpty, current.count == maxOpsPerBatch || size + cost > maxPlaintext {
                out.append(current)
                current = []
                size = frame
            }
            size += op.canonicalBytes.count + (current.isEmpty ? 0 : 1)
            current.append(op)
        }
        if !current.isEmpty { out.append(current) }
        return out
    }
}

/// Back-off for relay errors: 5 s, then double each time, at most 5 min (SPEC §10.4.3).
public struct RelayBackoff: Equatable, Sendable {
    public static let first: Double = 5
    public static let limit: Double = 300
    public private(set) var delay: Double = 0

    public init() {}

    /// Call after a failure that may pass. Returns the wait before the next try.
    @discardableResult
    public mutating func failed() -> Double {
        delay = delay == 0 ? Self.first : min(delay * 2, Self.limit)
        return delay
    }

    public mutating func succeeded() { delay = 0 }
}
