// Small byte helpers: hex and base64url without padding.

enum Hex {
    static func encode(_ bytes: some Sequence<UInt8>) -> String {
        let digits = Array("0123456789abcdef".utf8)
        var out: [UInt8] = []
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 15)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    static func decode(_ text: String) -> [UInt8]? {
        let u = Array(text.utf8)
        guard u.count % 2 == 0 else { return nil }
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 0x30...0x39: return c - 0x30
            case 0x61...0x66: return c - 0x61 + 10
            case 0x41...0x46: return c - 0x41 + 10
            default: return nil
            }
        }
        var out: [UInt8] = []
        var i = 0
        while i < u.count {
            guard let hi = nibble(u[i]), let lo = nibble(u[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }
}

public enum Base64URL {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)

    /// Encodes without padding.
    public static func encode(_ bytes: [UInt8]) -> String {
        var out: [UInt8] = []
        var i = 0
        while i + 3 <= bytes.count {
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8 | UInt32(bytes[i + 2])
            for shift in [18, 12, 6, 0] { out.append(alphabet[Int(n >> UInt32(shift) & 63)]) }
            i += 3
        }
        let rest = bytes.count - i
        if rest == 1 {
            let n = UInt32(bytes[i]) << 16
            for shift in [18, 12] { out.append(alphabet[Int(n >> UInt32(shift) & 63)]) }
        } else if rest == 2 {
            let n = UInt32(bytes[i]) << 16 | UInt32(bytes[i + 1]) << 8
            for shift in [18, 12, 6] { out.append(alphabet[Int(n >> UInt32(shift) & 63)]) }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Decodes text without padding. Returns nil for a bad character or a non-canonical encoding.
    public static func decode(_ text: String) -> [UInt8]? {
        var values: [UInt8] = []
        for c in text.utf8 {
            guard let index = alphabet.firstIndex(of: c) else { return nil }
            values.append(UInt8(index))
        }
        guard values.count % 4 != 1 else { return nil }
        var out: [UInt8] = []
        var i = 0
        while i < values.count {
            let chunk = Array(values[i..<min(i + 4, values.count)])
            var n: UInt32 = 0
            for k in 0..<4 { n = n << 6 | UInt32(k < chunk.count ? chunk[k] : 0) }
            let bytes = [UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255)]
            out.append(contentsOf: bytes.prefix(chunk.count - 1))
            i += 4
        }
        // Reject a tail with non-zero padding bits: only the canonical text decodes.
        return encode(out) == text ? out : nil
    }
}
