// Body validation (SPEC §4.3). A pure function of the op: no group state, clock or OS data.

struct Invalid: Error {}

@inline(__always)
func need(_ condition: Bool) throws {
    if !condition { throw Invalid() }
}

let knownTypes: Set<String> = [
    "group.create", "group.rename", "member.add", "member.rename", "member.claim", "member.unclaim",
    "member.remove", "expense.add", "expense.edit", "expense.delete", "settlement.add", "settlement.delete",
]

let categories: Set<String> = ["food", "transport", "lodging", "activities", "shopping", "other"]
let maxSplitMembers = 100
let maxWeight: Int64 = 1000

func isUUID(_ s: String) -> Bool {
    let u = Array(s.utf8)
    guard u.count == 36 else { return false }
    for (k, c) in u.enumerated() {
        if k == 8 || k == 13 || k == 18 || k == 23 {
            if c != UInt8(ascii: "-") { return false }
        } else if !((0x30...0x39).contains(c) || (0x61...0x66).contains(c)) {
            return false
        }
    }
    return true
}

func checkUUID(_ v: JSONValue?) throws {
    guard let s = v?.stringValue else { throw Invalid() }
    try need(isUUID(s))
}

func checkText(_ v: JSONValue?, max: Int) throws {
    guard let s = v?.stringValue else { throw Invalid() }
    try need((1...max).contains(s.unicodeScalars.count))
}

func checkDate(_ v: JSONValue?) throws {
    guard let s = v?.stringValue else { throw Invalid() }
    let u = Array(s.utf8)
    try need(u.count == 10 && u[4] == UInt8(ascii: "-") && u[7] == UInt8(ascii: "-"))
    let digits = [0, 1, 2, 3, 5, 6, 8, 9]
    try need(digits.allSatisfy { (0x30...0x39).contains(u[$0]) })
    let month = Int(u[5] - 0x30) * 10 + Int(u[6] - 0x30)
    let day = Int(u[8] - 0x30) * 10 + Int(u[9] - 0x30)
    try need((1...12).contains(month) && (1...31).contains(day))
}

func checkCurrency(_ v: JSONValue?) throws {
    guard let s = v?.stringValue else { throw Invalid() }
    try need(currencies[s] != nil)
}

func checkRate(_ v: JSONValue?) throws {
    guard let s = v?.stringValue else { throw Invalid() }
    do { _ = try parseRate(s) } catch { throw Invalid() }
}

@discardableResult
func checkKeys(_ v: JSONValue?, _ required: Set<String>, optional: Set<String> = []) throws -> [String: JSONValue] {
    guard let o = v?.objectValue else { throw Invalid() }
    let keys = Set(o.keys)
    try need(required.isSubset(of: keys) && keys.isSubset(of: required.union(optional)))
    return o
}

func checkAmount(_ v: JSONValue?) throws {
    guard let n = v?.intValue else { throw Invalid() }
    try need((1...maxAmountMinor).contains(n))
}

/// The money field group: amountMinor, currency, rate, split.
func checkMoney(_ v: JSONValue?) throws {
    let m = try checkKeys(v, ["amountMinor", "currency", "rate", "split"])
    try checkAmount(m["amountMinor"])
    try checkCurrency(m["currency"])
    try checkRate(m["rate"])
    let amount = m["amountMinor"]!.intValue!
    guard let split = m["split"]?.objectValue, let mode = split["mode"]?.stringValue else { throw Invalid() }
    switch mode {
    case "equal":
        let s = try checkKeys(m["split"], ["mode", "members"])
        guard let members = s["members"]?.arrayValue else { throw Invalid() }
        try need((1...maxSplitMembers).contains(members.count))
        for member in members { try checkUUID(member) }
        try need(Set(members.compactMap { $0.stringValue }).count == members.count)
    case "weights":
        let s = try checkKeys(m["split"], ["mode", "weights"])
        guard let weights = s["weights"]?.objectValue else { throw Invalid() }
        try need((1...maxSplitMembers).contains(weights.count))
        for (member, w) in weights {
            try need(isUUID(member))
            guard let n = w.intValue else { throw Invalid() }
            try need((1...maxWeight).contains(n))
        }
    case "exact":
        let s = try checkKeys(m["split"], ["mode", "shares"])
        guard let shares = s["shares"]?.objectValue else { throw Invalid() }
        try need((1...maxSplitMembers).contains(shares.count))
        var sum: Int64 = 0
        for (member, share) in shares {
            try need(isUUID(member))
            guard let n = share.intValue else { throw Invalid() }
            try need((0...maxAmountMinor).contains(n))
            sum += n
        }
        try need(sum == amount)
    default:
        throw Invalid()
    }
}

func splitMembers(_ split: JSONValue) -> [String] {
    switch split["mode"]?.stringValue {
    case "equal": return (split["members"]?.arrayValue ?? []).compactMap { $0.stringValue }
    case "weights": return Array((split["weights"]?.objectValue ?? [:]).keys)
    default: return Array((split["shares"]?.objectValue ?? [:]).keys)
    }
}

let expenseScalarFields: [String: @Sendable (JSONValue?) throws -> Void] = [
    "title": { try checkText($0, max: 100) },
    "date": checkDate,
    "category": { v in
        guard let s = v?.stringValue else { throw Invalid() }
        try need(categories.contains(s))
    },
    "paidBy": checkUUID,
]

func checkBody(type: String, body: JSONValue?) throws {
    guard body?.objectValue != nil else { throw Invalid() }
    switch type {
    case "group.create":
        let b = try checkKeys(body, ["name", "baseCurrency", "creatorMemberId", "creatorName"])
        try checkText(b["name"], max: 60)
        try checkCurrency(b["baseCurrency"])
        try checkUUID(b["creatorMemberId"])
        try checkText(b["creatorName"], max: 60)
    case "group.rename":
        let b = try checkKeys(body, ["name"])
        try checkText(b["name"], max: 60)
    case "member.add", "member.rename":
        let b = try checkKeys(body, ["memberId", "name"])
        try checkUUID(b["memberId"])
        try checkText(b["name"], max: 60)
    case "member.claim", "member.unclaim", "member.remove":
        let b = try checkKeys(body, ["memberId"])
        try checkUUID(b["memberId"])
    case "expense.add":
        let b = try checkKeys(body, ["expenseId", "title", "date", "category", "paidBy", "money"])
        try checkUUID(b["expenseId"])
        for (key, check) in expenseScalarFields { try check(b[key]) }
        try checkMoney(b["money"])
    case "expense.edit":
        let b = try checkKeys(body, ["expenseId", "fields"])
        try checkUUID(b["expenseId"])
        guard let fields = b["fields"]?.objectValue, !fields.isEmpty else { throw Invalid() }
        for (key, value) in fields {
            if key == "money" {
                try checkMoney(value)
            } else if let check = expenseScalarFields[key] {
                try check(value)
            } else {
                throw Invalid()
            }
        }
    case "expense.delete":
        let b = try checkKeys(body, ["expenseId"])
        try checkUUID(b["expenseId"])
    case "settlement.add":
        let b = try checkKeys(body, ["settlementId", "fromMember", "toMember", "amountMinor", "currency", "rate", "date"])
        try checkUUID(b["settlementId"])
        try checkUUID(b["fromMember"])
        try checkUUID(b["toMember"])
        try need(b["fromMember"] != b["toMember"])
        try checkAmount(b["amountMinor"])
        try checkCurrency(b["currency"])
        try checkRate(b["rate"])
        try checkDate(b["date"])
    case "settlement.delete":
        let b = try checkKeys(body, ["settlementId"])
        try checkUUID(b["settlementId"])
    default:
        throw Invalid()
    }
}
