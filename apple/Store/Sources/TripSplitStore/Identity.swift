// Device identity and the install marker (PLAN §4.2).
//
// The marker lives in a store that backups never restore. It holds the deviceId. If the database has
// a device row but the marker is missing or different, the database came from a backup or another
// phone. The app then mints a new deviceId and restarts the op counters.

import Foundation
import GRDB
import Security
import TripSplitCore

public protocol InstallMarkerStore: Sendable {
    func read() -> String?
    func write(_ deviceId: String) throws
}

public final class InMemoryInstallMarker: InstallMarkerStore, @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?
    public init(_ value: String? = nil) { self.value = value }
    public func read() -> String? { lock.withLock { value } }
    public func write(_ deviceId: String) throws { lock.withLock { value = deviceId } }
}

public struct KeychainInstallMarker: InstallMarkerStore {
    public let service: String
    public let account: String

    public init(service: String = "com.olcayertas.TripSplit.install", account: String = "marker") {
        self.service = service
        self.account = account
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    public func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func write(_ deviceId: String) throws {
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = Data(deviceId.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else { throw StoreError.keychain(status) }
    }
}

public enum StoreError: Error, Equatable {
    case keychain(OSStatus)
    case invalidOp
    case unknownGroup
}

public struct DeviceIdentity: Equatable, Sendable {
    public let deviceId: String
    public let displayName: String
    /// True when this launch minted a new deviceId although the database already held data.
    public let mintedAfterRestore: Bool
}

func newId() -> String { UUID().uuidString.lowercased() }

extension AppDatabase {
    /// Loads the device row, or creates it. Applies the restore rule of PLAN §4.2.
    public func loadIdentity(marker: any InstallMarkerStore, wall: Int64) throws -> DeviceIdentity {
        let identity: DeviceIdentity = try pool.write { db in
            let markerId = marker.read()
            if let row = try Row.fetchOne(db, sql: "SELECT device_id, display_name, last_hlc FROM device WHERE id = 1") {
                let current: String = row["device_id"]
                let name: String = row["display_name"]
                if markerId == current {
                    return DeviceIdentity(deviceId: current, displayName: name, mintedAfterRestore: false)
                }
                let fresh = newId()
                let old = HLC.parse(row["last_hlc"] as String)
                let clock = HLC(device: fresh, millis: max(old?.millis ?? 0, 0), counter: 0)
                try db.execute(sql: "UPDATE device SET device_id = ?, last_hlc = ? WHERE id = 1", arguments: [fresh, clock.stamp])
                try db.execute(sql: "UPDATE groups SET next_seq = 1")
                return DeviceIdentity(deviceId: fresh, displayName: name, mintedAfterRestore: true)
            }
            let fresh = newId()
            try db.execute(sql: "INSERT INTO device (id, device_id, display_name, last_hlc) VALUES (1, ?, '', ?)",
                           arguments: [fresh, HLC(device: fresh, millis: wall).stamp])
            return DeviceIdentity(deviceId: fresh, displayName: "", mintedAfterRestore: false)
        }
        if marker.read() != identity.deviceId { try marker.write(identity.deviceId) }
        return identity
    }

    public func setDisplayName(_ name: String) throws {
        try pool.write { db in try db.execute(sql: "UPDATE device SET display_name = ? WHERE id = 1", arguments: [name]) }
    }
}
