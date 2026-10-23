// A newcomer and the past expenses (PLAN §7.1): the edits for "Also earlier expenses".

public struct NewcomerPlan: Equatable, Sendable {
    /// One `expense.edit` body for each earlier expense that changes.
    public let edits: [JSONValue]
    /// Expenses with an exact split: not changed, because the amounts must still add up.
    public let exactExpenses: [GroupState.Expense]
}

/// Adds `memberId` to the split of every earlier expense that is not deleted:
/// `equal` adds the id, `weights` adds it with weight 1, `exact` is only listed.
/// An expense that already names the member is left alone.
public func newcomerEdits(memberId: String, state: GroupState) -> NewcomerPlan {
    var edits: [JSONValue] = []
    var exact: [GroupState.Expense] = []
    for e in state.expenses where !e.deleted {
        var split = e.split.objectValue ?? [:]
        switch e.split["mode"]?.stringValue {
        case "equal":
            var members = (e.split["members"]?.arrayValue ?? []).compactMap { $0.stringValue }
            guard !members.contains(memberId), members.count < maxSplitMembers else { continue }
            members.append(memberId)
            split["members"] = .array(members.sorted(by: byteLess).map { .string($0) })
        case "weights":
            var weights = e.split["weights"]?.objectValue ?? [:]
            guard weights[memberId] == nil, weights.count < maxSplitMembers else { continue }
            weights[memberId] = .int(1)
            split["weights"] = .object(weights)
        case "exact":
            if e.split["shares"]?.objectValue?[memberId] == nil { exact.append(e) }
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
    return NewcomerPlan(edits: edits, exactExpenses: exact)
}
