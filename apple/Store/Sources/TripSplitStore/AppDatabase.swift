// The SQLite database (PLAN §10): schema, file protection and backup exclusion.

import Foundation
import GRDB

public struct AppDatabase: Sendable {
    public let pool: DatabasePool
    public let url: URL
    /// True when the file already held data before this open.
    public let existed: Bool

    /// Opens (and creates) the database in `directory`.
    public static func open(directory: URL, fileName: String = "tripsplit.sqlite") throws -> AppDatabase {
        try prepare(directory: directory)
        let url = directory.appending(path: fileName)
        let existed = FileManager.default.fileExists(atPath: url.path)
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        let pool = try DatabasePool(path: url.path, configuration: configuration)
        try migrator.migrate(pool)
        try protect(url: url)
        return AppDatabase(pool: pool, url: url, existed: existed)
    }

    /// The directory is excluded from backup and uses complete file protection (PLAN §4.2, §10).
    /// Files that SQLite creates later (WAL, SHM) inherit the class of the directory.
    static func prepare(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var dir = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try dir.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: directory.path)
        #endif
    }

    static func protect(url: URL) throws {
        var file = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try file.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        #endif
    }

    static var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        m.registerMigration("v1") { db in
            try db.create(table: "ops") { t in
                t.column("group_id", .text).notNull()
                t.column("author", .text).notNull()
                t.column("seq", .integer).notNull()
                t.column("hlc", .text).notNull()
                t.column("type", .text).notNull()
                t.column("op_json", .text).notNull()
                t.column("hash", .text).notNull()
                t.column("status", .text).notNull()
                t.column("received_at", .integer).notNull()
                t.primaryKey(["group_id", "author", "seq"])
            }
            try db.create(index: "ops_group_hlc", on: "ops", columns: ["group_id", "hlc"])
            try db.create(table: "groups") { t in
                t.primaryKey("group_id", .text)
                t.column("group_key", .blob).notNull()
                t.column("next_seq", .integer).notNull().defaults(to: 1)
                t.column("created_at", .integer).notNull()
            }
            try db.create(table: "device") { t in
                t.primaryKey("id", .integer).check { $0 == 1 }
                t.column("device_id", .text).notNull()
                t.column("display_name", .text).notNull().defaults(to: "")
                t.column("last_hlc", .text).notNull()
            }
            try db.create(table: "forks") { t in
                t.column("group_id", .text).notNull()
                t.column("author", .text).notNull()
                t.column("seq", .integer).notNull()
                t.column("foreign_hash", .text).notNull()
                t.column("seen_at", .integer).notNull()
                t.primaryKey(["group_id", "author", "seq", "foreign_hash"])
            }
        }
        // Phase 10: the relay cursor and vector of each group (SPEC §10.4.4). The vector is canonical JSON.
        m.registerMigration("v2-relay") { db in
            try db.alter(table: "groups") { t in
                t.add(column: "relay_cursor", .integer).notNull().defaults(to: 0)
                t.add(column: "relay_vector", .text).notNull().defaults(to: "{}")
            }
        }
        // The mailbox epoch (SPEC §10.4.4 step 4). Null until the relay has told it.
        m.registerMigration("v3-relay-epoch") { db in
            try db.alter(table: "groups") { t in t.add(column: "relay_epoch", .text) }
        }
        return m
    }
}
