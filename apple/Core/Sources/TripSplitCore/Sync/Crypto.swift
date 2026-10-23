// Session crypto (SPEC §8.1): HKDF, discovery tag, handshake records, session key and frames.

import CryptoKit

public enum SyncCrypto {
    public static let magic: [UInt8] = Array("TS1".utf8)
    public static let saltLength = 16
    public static let tagLength = 12
    public static let recordLength = 3 + saltLength + tagLength
    public static let maxFrameLength = 1 << 20
    public static let gcmTagLength = 16

    public static func hkdf(ikm: [UInt8], salt: [UInt8], info: [UInt8], length: Int) -> [UInt8] {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: length)
        return key.withUnsafeBytes { Array($0) }
    }

    /// First 12 bytes of HMAC-SHA256(groupKey, "tripsplit/v1/discover").
    public static func discoveryTag(groupKey: [UInt8]) -> [UInt8] {
        let mac = HMAC<SHA256>.authenticationCode(
            for: Array("tripsplit/v1/discover".utf8), using: SymmetricKey(data: groupKey))
        return Array(Array(mac).prefix(tagLength))
    }

    public static func handshakeRecord(salt: [UInt8], tag: [UInt8]) -> [UInt8] {
        magic + salt + tag
    }

    public static func parseRecord(_ bytes: [UInt8]) -> (salt: [UInt8], tag: [UInt8])? {
        guard bytes.count == recordLength, Array(bytes.prefix(3)) == magic else { return nil }
        return (Array(bytes[3..<19]), Array(bytes[19...]))
    }

    /// Byte-order comparison of two salts of equal length.
    static func saltLess(_ a: [UInt8], _ b: [UInt8]) -> Bool { a.lexicographicallyPrecedes(b) }

    public static func sessionKey(groupKey: [UInt8], saltA: [UInt8], saltB: [UInt8], groupId: String) -> [UInt8] {
        let (low, high) = saltLess(saltA, saltB) ? (saltA, saltB) : (saltB, saltA)
        return hkdf(ikm: groupKey, salt: low + high, info: Array(("tripsplit/v1/frame/" + groupId).utf8), length: 32)
    }

    /// 0x01 for the side that sent the lower salt, 0x02 for the other side.
    public static func direction(ownSalt: [UInt8], peerSalt: [UInt8]) -> UInt8 {
        saltLess(ownSalt, peerSalt) ? 0x01 : 0x02
    }

    public static func nonce(direction: UInt8, counter: UInt64) -> [UInt8] {
        var n: [UInt8] = [direction, 0, 0, 0]
        for shift in stride(from: 56, through: 0, by: -8) { n.append(UInt8(truncatingIfNeeded: counter >> UInt64(shift))) }
        return n
    }
}

public enum FrameError: Error, Equatable {
    case tooLarge
    case tooShort
    case decryptFailed
}

/// One direction of an encrypted frame stream. `seal` and `open` advance the counter.
public struct FrameCipher: Sendable {
    private let key: SymmetricKey
    public let direction: UInt8
    public private(set) var counter: UInt64

    public init(sessionKey: [UInt8], direction: UInt8, counter: UInt64 = 0) {
        key = SymmetricKey(data: sessionKey)
        self.direction = direction
        self.counter = counter
    }

    /// Returns `u32 big-endian length || ciphertext || tag`.
    public mutating func seal(_ plaintext: [UInt8]) throws -> [UInt8] {
        let nonce = try AES.GCM.Nonce(data: SyncCrypto.nonce(direction: direction, counter: counter))
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        counter += 1
        let body = Array(box.ciphertext) + Array(box.tag)
        guard body.count <= SyncCrypto.maxFrameLength else { throw FrameError.tooLarge }
        let n = UInt32(body.count)
        return [UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255)] + body
    }

    /// Opens `ciphertext || tag` (the frame without its length prefix).
    public mutating func open(_ body: [UInt8]) throws -> [UInt8] {
        guard body.count >= SyncCrypto.gcmTagLength else { throw FrameError.tooShort }
        do {
            let nonce = try AES.GCM.Nonce(data: SyncCrypto.nonce(direction: direction, counter: counter))
            let cut = body.count - SyncCrypto.gcmTagLength
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: body[0..<cut], tag: body[cut...])
            let plain = try AES.GCM.open(box, using: key)
            counter += 1
            return Array(plain)
        } catch {
            throw FrameError.decryptFailed
        }
    }
}

/// Collects the bytes of a stream and cuts them into the handshake record and frames.
struct StreamBuffer {
    private var bytes: [UInt8] = []
    private var start = 0

    var count: Int { bytes.count - start }

    mutating func append(_ chunk: [UInt8]) {
        if start > 0, start >= bytes.count / 2 {
            bytes.removeFirst(start)
            start = 0
        }
        bytes.append(contentsOf: chunk)
    }

    mutating func take(_ n: Int) -> [UInt8]? {
        guard count >= n else { return nil }
        defer { start += n }
        return Array(bytes[start..<start + n])
    }

    /// Returns the next frame body, nil when more bytes are needed. Throws before it waits for a body
    /// that is over the limit (SPEC §8.1 rule 9).
    mutating func nextFrame() throws -> [UInt8]? {
        guard count >= 4 else { return nil }
        let length = Int(bytes[start]) << 24 | Int(bytes[start + 1]) << 16 | Int(bytes[start + 2]) << 8 | Int(bytes[start + 3])
        guard length <= SyncCrypto.maxFrameLength else { throw FrameError.tooLarge }
        guard length >= SyncCrypto.gcmTagLength else { throw FrameError.tooShort }
        guard count >= 4 + length else { return nil }
        start += 4
        return take(length)
    }
}
