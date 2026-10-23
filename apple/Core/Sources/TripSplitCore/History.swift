// The change log of a group, read from its ops (PLAN §11, screen 10).

public struct HistoryEntry: Equatable, Sendable, Identifiable {
    public let id: String
    public let hlc: String
    public let millis: Int64
    public let author: String
    public let type: String
    /// Expense id, settlement id or member id the op is about.
    public let subjectId: String?
    /// Name or title in the op, when it has one.
    public let text: String?
    /// For `expense.edit`: the changed field names, sorted.
    public let fields: [String]
}

/// Newest first. Ops that do not parse as an envelope are left out.
public func historyEntries(ops: [JSONValue]) -> [HistoryEntry] {
    var out: [HistoryEntry] = []
    for op in ops {
        guard let hlc = op["hlc"]?.stringValue, let stamp = HLC.parse(hlc), let type = op["type"]?.stringValue,
              let author = op["author"]?.stringValue
        else { continue }
        let body = op["body"] ?? .null
        let subject = ["expenseId", "settlementId", "memberId"].lazy.compactMap { body[$0]?.stringValue }.first
        let text = ["title", "name", "creatorName"].lazy.compactMap { body[$0]?.stringValue }.first
        let fields = (body["fields"]?.objectValue ?? [:]).keys.sorted()
        out.append(HistoryEntry(id: opRef(op), hlc: hlc, millis: stamp.millis, author: author, type: type,
                                subjectId: subject, text: text, fields: fields))
    }
    return out.sorted { byteLess($1.hlc, $0.hlc) }
}

/// The member this device tried to claim last, when the fold gave it to another device (SPEC §4.4: the
/// claim with the lower HLC wins). `history` is newest first. nil when there is no lost claim.
public func lostClaim(history: [HistoryEntry], deviceId: String, state: GroupState) -> GroupState.Member? {
    guard let last = history.first(where: { $0.author == deviceId && ($0.type == "member.claim" || $0.type == "member.unclaim") }),
          last.type == "member.claim", let id = last.subjectId,
          let member = state.members.first(where: { $0.id == id }), member.claimedBy != deviceId
    else { return nil }
    return member
}
