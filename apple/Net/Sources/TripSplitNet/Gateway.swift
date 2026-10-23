// The address of the default gateway, best effort (PLAN §9.1 fixed-port fallback).
//
// iOS has no public API for the routing table. A phone hotspot gives the client a /24 network and
// the hotspot phone sits at .1, so the first address of the network of the Wi-Fi interface is a good
// guess. It must be verified on real devices.

import Foundation

public enum Gateway {
    /// The first host address of the network that holds `address` under `mask`. nil when the guess
    /// would be the address itself.
    public static func candidate(address: UInt32, mask: UInt32) -> UInt32? {
        let guess = (address & mask) | 1
        return guess == address ? nil : guess
    }

    public static func dotted(_ v: UInt32) -> String {
        "\(v >> 24 & 255).\(v >> 16 & 255).\(v >> 8 & 255).\(v & 255)"
    }

    /// The guess for the Wi-Fi interface (`en0`), or nil.
    public static func ipv4() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var pointer: UnsafeMutablePointer<ifaddrs>? = first
        while let p = pointer {
            defer { pointer = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, let addr = p.pointee.ifa_addr, let mask = p.pointee.ifa_netmask,
                  addr.pointee.sa_family == UInt8(AF_INET), String(cString: p.pointee.ifa_name) == "en0"
            else { continue }
            let a = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            if let g = candidate(address: a, mask: m) { return dotted(g) }
        }
        return nil
    }
}
