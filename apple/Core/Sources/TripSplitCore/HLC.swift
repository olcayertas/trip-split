// Hybrid logical clock (SPEC §3). The caller passes the wall time; the core never reads the clock.

public let maxForwardSkewMillis: Int64 = 86_400_000

public struct HLC: Equatable, Sendable {
    public let device: String
    public private(set) var millis: Int64
    public private(set) var counter: Int

    public init(device: String, millis: Int64 = 0, counter: Int = 0) {
        self.device = device
        self.millis = millis
        self.counter = counter
    }

    public var stamp: String { Self.format(millis: millis, counter: counter, device: device) }

    public static func format(millis: Int64, counter: Int, device: String) -> String {
        let m = String(millis)
        let c = String(counter, radix: 16)
        return String(repeating: "0", count: max(0, 15 - m.count)) + m + "-"
            + String(repeating: "0", count: max(0, 4 - c.count)) + c + "-" + device
    }

    public static func parse(_ stamp: String) -> (millis: Int64, counter: Int, device: String)? {
        let u = Array(stamp.utf8)
        guard u.count > 21, u[15] == UInt8(ascii: "-"), u[20] == UInt8(ascii: "-"),
              let m = Int64(String(decoding: u[0..<15], as: UTF8.self)), u[0..<15].allSatisfy({ (0x30...0x39).contains($0) }),
              u[16..<20].allSatisfy({ (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }),
              let c = Int(String(decoding: u[16..<20], as: UTF8.self), radix: 16)
        else { return nil }
        return (m, c, String(decoding: u[21...], as: UTF8.self))
    }

    private mutating func normalize() {
        if counter > 0xFFFF {
            millis += 1
            counter = 0
        }
    }

    /// Stamp for a new local op.
    public mutating func send(wall: Int64) -> String {
        if wall > millis {
            millis = wall
            counter = 0
        } else {
            counter += 1
        }
        normalize()
        return stamp
    }

    /// Merge a remote stamp. Returns true when the stamp is refused for clock skew.
    @discardableResult
    public mutating func receive(_ remote: String, wall: Int64) -> Bool {
        guard let r = Self.parse(remote) else { return true }
        if r.millis > wall + maxForwardSkewMillis { return true }
        let m = max(millis, r.millis, wall)
        let c: Int
        if m == millis && m == r.millis {
            c = max(counter, r.counter) + 1
        } else if m == millis {
            c = counter + 1
        } else if m == r.millis {
            c = r.counter + 1
        } else {
            c = 0
        }
        millis = m
        counter = c
        normalize()
        return false
    }
}
