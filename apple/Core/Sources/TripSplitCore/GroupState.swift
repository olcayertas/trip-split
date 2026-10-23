// A typed view of the fold result (SPEC §4.4). The UI reads this, not the JSON.

public struct GroupState: Equatable, Sendable {
    public struct Group: Equatable, Sendable {
        public let name: String
        public let baseCurrency: String
    }

    public struct Member: Equatable, Sendable, Identifiable {
        public let id: String
        public let name: String
        public let removed: Bool
        public let claimedBy: String?
    }

    public struct Expense: Equatable, Sendable, Identifiable {
        public let id: String
        public let title: String
        public let date: String
        public let category: String
        public let paidBy: String
        public let amountMinor: Int64
        public let currency: String
        public let rate: String
        /// The split object as in the op: mode plus members, weights or shares.
        public let split: JSONValue
        public let deleted: Bool
        public let createdBy: String
        public let needsAttention: Bool
        public let baseAmountMinor: Int64?
        public let baseShares: [String: Int64]?
    }

    public struct Settlement: Equatable, Sendable, Identifiable {
        public let id: String
        public let fromMember: String
        public let toMember: String
        public let amountMinor: Int64
        public let currency: String
        public let rate: String
        public let date: String
        public let deleted: Bool
        public let needsAttention: Bool
        public let baseAmountMinor: Int64?
    }

    public let group: Group?
    public let members: [Member]
    public let expenses: [Expense]
    public let settlements: [Settlement]
    public let balances: [String: Int64]
    public let suggestedPayments: [Payment]
    public let invalidOps: [String]
    public let unknownOps: [String]

    public static let empty = GroupState(foldResult: fold(groupId: "", ops: []))

    public init(foldResult j: JSONValue) {
        func s(_ v: JSONValue?) -> String { v?.stringValue ?? "" }
        func i(_ v: JSONValue?) -> Int64 { v?.intValue ?? 0 }
        func b(_ v: JSONValue?) -> Bool { v == .bool(true) }
        func list(_ key: String) -> [JSONValue] { j[key]?.arrayValue ?? [] }

        if let g = j["group"], g.objectValue != nil {
            group = Group(name: s(g["name"]), baseCurrency: s(g["baseCurrency"]))
        } else {
            group = nil
        }
        members = list("members").map {
            Member(id: s($0["id"]), name: s($0["name"]), removed: b($0["removed"]), claimedBy: $0["claimedBy"]?.stringValue)
        }
        expenses = list("expenses").map { e in
            let m = e["money"] ?? .null
            return Expense(
                id: s(e["id"]), title: s(e["title"]), date: s(e["date"]), category: s(e["category"]),
                paidBy: s(e["paidBy"]), amountMinor: i(m["amountMinor"]), currency: s(m["currency"]),
                rate: s(m["rate"]), split: m["split"] ?? .null, deleted: b(e["deleted"]), createdBy: s(e["createdBy"]),
                needsAttention: b(e["needsAttention"]), baseAmountMinor: e["baseAmountMinor"]?.intValue,
                baseShares: e["baseShares"]?.objectValue?.compactMapValues { $0.intValue })
        }
        settlements = list("settlements").map { e in
            Settlement(
                id: s(e["id"]), fromMember: s(e["fromMember"]), toMember: s(e["toMember"]),
                amountMinor: i(e["amountMinor"]), currency: s(e["currency"]), rate: s(e["rate"]), date: s(e["date"]),
                deleted: b(e["deleted"]), needsAttention: b(e["needsAttention"]),
                baseAmountMinor: e["baseAmountMinor"]?.intValue)
        }
        balances = (j["balances"]?.objectValue ?? [:]).compactMapValues { $0.intValue }
        suggestedPayments = list("suggestedPayments").map {
            Payment(from: s($0["from"]), to: s($0["to"]), amountMinor: i($0["amountMinor"]))
        }
        invalidOps = list("invalidOps").compactMap { $0.stringValue }
        unknownOps = list("unknownOps").compactMap { $0.stringValue }
    }
}
