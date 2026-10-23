// Strict JSON value, parser and canonical encoder (SPEC §2).
//
// The platform JSON decoders blur booleans, integers and floats, so the core uses its own small
// parser: it rejects any number with a fraction or exponent and any integer outside ±(2^53 − 1).

public indirect enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public subscript(key: String) -> JSONValue? {
        if case let .object(o) = self { return o[key] }
        return nil
    }

    public var stringValue: String? { if case let .string(s) = self { return s }; return nil }
    public var intValue: Int64? { if case let .int(n) = self { return n }; return nil }
    public var objectValue: [String: JSONValue]? { if case let .object(o) = self { return o }; return nil }
    public var arrayValue: [JSONValue]? { if case let .array(a) = self { return a }; return nil }
}

public enum JSONError: Error, Equatable {
    case syntax(Int)
    case float
    case outOfRange
}

public let maxSafeInteger: Int64 = 9_007_199_254_740_991

public struct JSONParser {
    private let b: [UInt8]
    private var i = 0

    private init(_ bytes: [UInt8]) { b = bytes }

    public static func parse(_ bytes: some Sequence<UInt8>) throws -> JSONValue {
        var p = JSONParser(Array(bytes))
        p.skipWhitespace()
        let v = try p.value()
        p.skipWhitespace()
        guard p.i == p.b.count else { throw JSONError.syntax(p.i) }
        return v
    }

    public static func parse(_ text: String) throws -> JSONValue { try parse(Array(text.utf8)) }

    private mutating func skipWhitespace() {
        while i < b.count, b[i] == 0x20 || b[i] == 0x0A || b[i] == 0x0D || b[i] == 0x09 { i += 1 }
    }

    private func peek() throws -> UInt8 {
        guard i < b.count else { throw JSONError.syntax(i) }
        return b[i]
    }

    private mutating func expect(_ c: UInt8) throws {
        guard try peek() == c else { throw JSONError.syntax(i) }
        i += 1
    }

    private mutating func value() throws -> JSONValue {
        switch try peek() {
        case UInt8(ascii: "{"): return try object()
        case UInt8(ascii: "["): return try array()
        case UInt8(ascii: "\""): return .string(try string())
        case UInt8(ascii: "t"): try literal("true"); return .bool(true)
        case UInt8(ascii: "f"): try literal("false"); return .bool(false)
        case UInt8(ascii: "n"): try literal("null"); return .null
        default: return try number()
        }
    }

    private mutating func literal(_ word: String) throws {
        for c in word.utf8 { try expect(c) }
    }

    private mutating func object() throws -> JSONValue {
        try expect(UInt8(ascii: "{"))
        var out: [String: JSONValue] = [:]
        skipWhitespace()
        if try peek() == UInt8(ascii: "}") { i += 1; return .object(out) }
        while true {
            skipWhitespace()
            let key = try string()
            skipWhitespace()
            try expect(UInt8(ascii: ":"))
            skipWhitespace()
            out[key] = try value()
            skipWhitespace()
            if try peek() == UInt8(ascii: ",") { i += 1; continue }
            try expect(UInt8(ascii: "}"))
            return .object(out)
        }
    }

    private mutating func array() throws -> JSONValue {
        try expect(UInt8(ascii: "["))
        var out: [JSONValue] = []
        skipWhitespace()
        if try peek() == UInt8(ascii: "]") { i += 1; return .array(out) }
        while true {
            skipWhitespace()
            out.append(try value())
            skipWhitespace()
            if try peek() == UInt8(ascii: ",") { i += 1; continue }
            try expect(UInt8(ascii: "]"))
            return .array(out)
        }
    }

    private mutating func hex4() throws -> UInt32 {
        guard i + 4 <= b.count else { throw JSONError.syntax(i) }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let c = b[i]
            let d: UInt32
            switch c {
            case 0x30...0x39: d = UInt32(c - 0x30)
            case 0x61...0x66: d = UInt32(c - 0x61 + 10)
            case 0x41...0x46: d = UInt32(c - 0x41 + 10)
            default: throw JSONError.syntax(i)
            }
            v = v << 4 | d
            i += 1
        }
        return v
    }

    private mutating func string() throws -> String {
        try expect(UInt8(ascii: "\""))
        var out: [UInt8] = []
        while true {
            let c = try peek()
            i += 1
            switch c {
            case UInt8(ascii: "\""):
                return String(decoding: out, as: UTF8.self)
            case UInt8(ascii: "\\"):
                let e = try peek()
                i += 1
                switch e {
                case UInt8(ascii: "\""): out.append(0x22)
                case UInt8(ascii: "\\"): out.append(0x5C)
                case UInt8(ascii: "/"): out.append(0x2F)
                case UInt8(ascii: "b"): out.append(0x08)
                case UInt8(ascii: "f"): out.append(0x0C)
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "u"):
                    var code = try hex4()
                    if (0xD800...0xDBFF).contains(code) {
                        try expect(UInt8(ascii: "\\"))
                        try expect(UInt8(ascii: "u"))
                        let low = try hex4()
                        guard (0xDC00...0xDFFF).contains(low) else { throw JSONError.syntax(i) }
                        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                    }
                    guard let scalar = Unicode.Scalar(code) else { throw JSONError.syntax(i) }
                    out.append(contentsOf: Array(String(Character(scalar)).utf8))
                default:
                    throw JSONError.syntax(i)
                }
            case 0x00..<0x20:
                throw JSONError.syntax(i)
            default:
                out.append(c)
            }
        }
    }

    private mutating func number() throws -> JSONValue {
        let start = i
        if try peek() == UInt8(ascii: "-") { i += 1 }
        let digitsStart = i
        while i < b.count, (0x30...0x39).contains(b[i]) { i += 1 }
        guard i > digitsStart else { throw JSONError.syntax(i) }
        if i < b.count, b[i] == UInt8(ascii: ".") || b[i] == UInt8(ascii: "e") || b[i] == UInt8(ascii: "E") {
            throw JSONError.float
        }
        let text = String(decoding: b[start..<i], as: UTF8.self)
        guard let n = Int64(text), n >= -maxSafeInteger, n <= maxSafeInteger else { throw JSONError.outOfRange }
        return .int(n)
    }
}

// MARK: - Canonical encoding

extension JSONValue {
    public var canonical: String {
        var out = ""
        Self.write(self, into: &out)
        return out
    }

    public var canonicalBytes: [UInt8] { Array(canonical.utf8) }

    private static func write(_ v: JSONValue, into out: inout String) {
        switch v {
        case .null: out += "null"
        case let .bool(x): out += x ? "true" : "false"
        case let .int(n): out += String(n)
        case let .string(s): writeString(s, into: &out)
        case let .array(a):
            out += "["
            for (k, e) in a.enumerated() {
                if k > 0 { out += "," }
                write(e, into: &out)
            }
            out += "]"
        case let .object(o):
            out += "{"
            let keys = o.keys.sorted { $0.utf16.lexicographicallyPrecedes($1.utf16) }
            for (k, key) in keys.enumerated() {
                if k > 0 { out += "," }
                writeString(key, into: &out)
                out += ":"
                write(o[key]!, into: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }
}

/// Byte order of the UTF-8 encoding (SPEC §1).
@inline(__always)
func byteLess(_ a: String, _ b: String) -> Bool {
    a.utf8.lexicographicallyPrecedes(b.utf8)
}
