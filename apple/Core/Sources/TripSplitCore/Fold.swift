// The fold: ops → group state (SPEC §4.4, §5, §6). Pure and independent of the input order.

func opRef(_ op: JSONValue) -> String {
    "\(op["author"]?.stringValue ?? ""):\(op["seq"]?.intValue ?? 0)"
}

private struct Member {
    var name: String
    var removed = false
    var claimedBy: String?
}

private struct Expense {
    var fields: [String: JSONValue]  // title, date, category, paidBy, money
    var deleted = false
    let createdBy: String
}

private struct Settlement {
    let body: [String: JSONValue]
    var deleted = false
}

/// Folds the ops of one group. The result has exactly the shape of `expected` in fold.json.
public func fold(groupId: String, ops: [JSONValue]) -> JSONValue {
    var invalid: [String] = []
    var unknown: [String] = []
    var candidates: [JSONValue] = []

    for op in ops {
        guard op["v"]?.intValue == 1, let type = op["type"]?.stringValue, knownTypes.contains(type) else {
            unknown.append(opRef(op))
            continue
        }
        guard op["group"]?.stringValue == groupId, (try? checkBody(type: type, body: op["body"])) != nil else {
            invalid.append(opRef(op))
            continue
        }
        candidates.append(op)
    }

    candidates.sort { a, b in
        let ha = a["hlc"]?.stringValue ?? "", hb = b["hlc"]?.stringValue ?? ""
        if ha != hb { return byteLess(ha, hb) }
        let aa = a["author"]?.stringValue ?? "", ab = b["author"]?.stringValue ?? ""
        if aa != ab { return byteLess(aa, ab) }
        return (a["seq"]?.intValue ?? 0) < (b["seq"]?.intValue ?? 0)
    }

    var group: (name: String, base: String)?
    var members: [String: Member] = [:]
    var claimOfDevice: [String: String] = [:]
    var expenses: [String: Expense] = [:]
    var settlements: [String: Settlement] = [:]

    func claim(_ memberId: String, _ device: String) {
        guard let m = members[memberId], !m.removed, m.claimedBy == nil else { return }
        if let previous = claimOfDevice[device] { members[previous]?.claimedBy = nil }
        members[memberId]?.claimedBy = device
        claimOfDevice[device] = memberId
    }

    for op in candidates {
        let type = op["type"]!.stringValue!
        let b = op["body"]!.objectValue!
        let author = op["author"]?.stringValue ?? ""
        let s = { (key: String) in b[key]!.stringValue! }
        switch type {
        case "group.create":
            guard group == nil, op["seq"]?.intValue == 1 else {
                invalid.append(opRef(op))
                continue
            }
            group = (s("name"), s("baseCurrency"))
            if members[s("creatorMemberId")] == nil {
                members[s("creatorMemberId")] = Member(name: s("creatorName"))
            }
            claim(s("creatorMemberId"), author)
        case "group.rename":
            if group != nil { group!.name = s("name") }
        case "member.add":
            if members[s("memberId")] == nil { members[s("memberId")] = Member(name: s("name")) }
        case "member.rename":
            if members[s("memberId")] != nil { members[s("memberId")]!.name = s("name") }
        case "member.claim":
            claim(s("memberId"), author)
        case "member.unclaim":
            // Any device may release any claim (SPEC §4.4).
            if let holder = members[s("memberId")]?.claimedBy {
                members[s("memberId")]!.claimedBy = nil
                claimOfDevice[holder] = nil
            }
        case "member.remove":
            let id = s("memberId")
            if let m = members[id], !m.removed {
                members[id]!.removed = true
                if let device = m.claimedBy {
                    claimOfDevice[device] = nil
                    members[id]!.claimedBy = nil
                }
            }
        case "expense.add":
            let id = s("expenseId")
            if expenses[id] == nil {
                var fields = b
                fields["expenseId"] = nil
                expenses[id] = Expense(fields: fields, createdBy: author)
            }
        case "expense.edit":
            let id = s("expenseId")
            if let e = expenses[id], !e.deleted {
                for (key, value) in b["fields"]!.objectValue! { expenses[id]!.fields[key] = value }
            }
        case "expense.delete":
            if expenses[s("expenseId")] != nil { expenses[s("expenseId")]!.deleted = true }
        case "settlement.add":
            let id = s("settlementId")
            if settlements[id] == nil {
                var body = b
                body["settlementId"] = nil
                settlements[id] = Settlement(body: body)
            }
        case "settlement.delete":
            if settlements[s("settlementId")] != nil { settlements[s("settlementId")]!.deleted = true }
        default:
            break
        }
    }

    // End-of-fold validity, conversions and balances.
    var balances: [String: Int64] = [:]
    if group != nil { for id in members.keys { balances[id] = 0 } }

    func baseAmount(_ currency: String, _ rate: String, _ amount: Int64) -> Int64? {
        guard let base = group?.base else { return nil }
        if currency == base && rate != "1" { return nil }
        return try? convert(amountMinor: amount, currency: currency, rate: rate, base: base)
    }

    var expenseOut: [JSONValue] = []
    for id in expenses.keys.sorted(by: byteLess) {
        let e = expenses[id]!
        let money = e.fields["money"]!
        let paidBy = e.fields["paidBy"]!.stringValue!
        let amount = baseAmount(money["currency"]!.stringValue!, money["rate"]!.stringValue!, money["amountMinor"]!.intValue!)
        let involved = [paidBy] + splitMembers(money["split"]!)
        var shares: [String: Int64]?
        if let amount, involved.allSatisfy({ members[$0] != nil }) {
            let sh = splitShares(baseTotal: amount, split: money["split"]!)
            shares = sh
            if !e.deleted {
                balances[paidBy]! += amount
                for (m, v) in sh { balances[m]! -= v }
            }
        }
        var o = e.fields
        o["id"] = .string(id)
        o["deleted"] = .bool(e.deleted)
        o["createdBy"] = .string(e.createdBy)
        o["needsAttention"] = .bool(shares == nil)
        o["baseAmountMinor"] = shares == nil ? .null : .int(amount!)
        o["baseShares"] = shares.map { .object($0.mapValues { .int($0) }) } ?? .null
        expenseOut.append(.object(o))
    }

    var settlementOut: [JSONValue] = []
    for id in settlements.keys.sorted(by: byteLess) {
        let st = settlements[id]!
        let from = st.body["fromMember"]!.stringValue!, to = st.body["toMember"]!.stringValue!
        var amount = baseAmount(st.body["currency"]!.stringValue!, st.body["rate"]!.stringValue!, st.body["amountMinor"]!.intValue!)
        if members[from] == nil || members[to] == nil { amount = nil }
        if let amount, !st.deleted {
            balances[from]! += amount
            balances[to]! -= amount
        }
        var o = st.body
        o["id"] = .string(id)
        o["deleted"] = .bool(st.deleted)
        o["needsAttention"] = .bool(amount == nil)
        o["baseAmountMinor"] = amount.map { .int($0) } ?? .null
        settlementOut.append(.object(o))
    }

    precondition(balances.values.reduce(0, +) == 0, "balances must sum to zero")

    let memberOut: [JSONValue] = members.keys.sorted(by: byteLess).map { id in
        let m = members[id]!
        return .object([
            "id": .string(id), "name": .string(m.name), "removed": .bool(m.removed),
            "claimedBy": m.claimedBy.map { .string($0) } ?? .null,
        ])
    }

    return .object([
        "group": group.map { .object(["name": .string($0.name), "baseCurrency": .string($0.base)]) } ?? .null,
        "members": .array(memberOut),
        "expenses": .array(expenseOut),
        "settlements": .array(settlementOut),
        "balances": .object(balances.mapValues { .int($0) }),
        "suggestedPayments": .array(settle(balances).map {
            .object(["from": .string($0.from), "to": .string($0.to), "amountMinor": .int($0.amountMinor)])
        }),
        "invalidOps": .array(invalid.sorted(by: byteLess).map { .string($0) }),
        "unknownOps": .array(unknown.sorted(by: byteLess).map { .string($0) }),
    ])
}

// MARK: - Version vector and anti-entropy diff (SPEC §7)

public func versionVector(_ ops: [JSONValue]) -> [String: Int64] {
    var seqs: [String: Set<Int64>] = [:]
    for op in ops {
        guard let a = op["author"]?.stringValue, let s = op["seq"]?.intValue else { continue }
        seqs[a, default: []].insert(s)
    }
    var vector: [String: Int64] = [:]
    for (author, set) in seqs {
        var n: Int64 = 0
        while set.contains(n + 1) { n += 1 }
        if n > 0 { vector[author] = n }
    }
    return vector
}

/// Refs ("author:seq") of the ops the peer lacks, ordered by author bytes, then seq.
public func missingFor(peerVector: [String: Int64], ops: [JSONValue]) -> [String] {
    ops.filter { ($0["seq"]?.intValue ?? 0) > peerVector[$0["author"]?.stringValue ?? "", default: 0] }
        .sorted { a, b in
            let aa = a["author"]?.stringValue ?? "", ab = b["author"]?.stringValue ?? ""
            return aa != ab ? byteLess(aa, ab) : (a["seq"]?.intValue ?? 0) < (b["seq"]?.intValue ?? 0)
        }
        .map(opRef)
}
