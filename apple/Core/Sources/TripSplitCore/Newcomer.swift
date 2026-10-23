// A newcomer and the past expenses (PLAN §7.1): the edits for "Also earlier expenses".

public struct NewcomerPlan: Equatable, Sendable {
    /// One `expense.edit` body for each earlier expense that changes.
    public let edits: [JSONValue]
    /// Expenses that stay as they are and need a hand edit: an exact split (the amounts must still add up)
    /// and a split that already has the most members (100). The user sees every one of them.
    public let untouched: [GroupState.Expense]
}

/// Adds `memberId` to the split of every earlier expense that is not deleted:
/// `equal` adds the id, `weights` adds it with weight 1, `exact` and a full split (100 members) are only listed in `untouched`.
/// An expense that already names the member is left alone.
public func newcomerEdits(memberId: String, state: GroupState) -> NewcomerPlan {
    var edits: [JSONValue] = []
    var untouched: [GroupState.Expense] = []
    for e in state.expenses where !e.deleted {
        var split = e.split.objectValue ?? [:]
        switch e.split["mode"]?.stringValue {
        case "equal":
            var members = (e.split["members"]?.arrayValue ?? []).compactMap { $0.stringValue }
            guard !members.contains(memberId) else { continue }
            guard members.count < maxSplitMembers else { untouched.append(e); continue }
            members.append(memberId)
            split["members"] = .array(members.sorted(by: byteLess).map { .string($0) })
        case "weights":
            var weights = e.split["weights"]?.objectValue ?? [:]
            guard weights[memberId] == nil else { continue }
            guard weights.count < maxSplitMembers else { untouched.append(e); continue }
            weights[memberId] = .int(1)
            split["weights"] = .object(weights)
        case "exact":
            if e.split["shares"]?.objectValue?[memberId] == nil { untouched.append(e) }
            continue
        default:
            continue
        }
        let money: JSONValue = .object([
            "amountMinor": .int(e.amountMinor), "currency": .string(e.currency), "rate": .string(e.rate),
            "split": .object(split),
        ])
        edits.append(.object(["expenseId": .string(e.id), "fields": .object(["money": money])]))
    }
    return NewcomerPlan(edits: edits, untouched: untouched)
}
