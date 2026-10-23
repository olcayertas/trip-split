// The state of the expense editor and its conversion to op bodies. Pure, so the core tests cover it.

public enum DraftError: Error, Equatable {
    case titleEmpty
    case titleTooLong
    case amountInvalid
    case rateInvalid
    case noPayer
    case noMembers
    /// Exact split: the shares differ from the amount by this many minor units (positive = still to assign).
    case exactMismatch(remaining: Int64)
    case dateInvalid
}

public struct ExpenseDraft: Equatable, Sendable {
    public enum Mode: String, Sendable, CaseIterable { case equal, weights, exact }

    public var title = ""
    public var amountText = ""
    public var currency: String
    public var rateText = ""
    public var paidBy: String?
    public var date: String
    public var category = "other"
    public var mode: Mode = .equal
    public var equalMembers: Set<String> = []
    public var weights: [String: Int64] = [:]
    public var exactTexts: [String: String] = [:]

    public init(currency: String, date: String, paidBy: String? = nil, members: [String] = []) {
        self.currency = currency
        self.date = date
        self.paidBy = paidBy
        equalMembers = Set(members)
    }

    /// Fills the editor from an existing expense.
    public init(expense e: GroupState.Expense) {
        title = e.title
        amountText = AmountFormat.format(e.amountMinor, currency: e.currency)
        currency = e.currency
        rateText = e.rate
        paidBy = e.paidBy
        date = e.date
        category = e.category
        switch e.split["mode"]?.stringValue {
        case "weights":
            mode = .weights
            weights = (e.split["weights"]?.objectValue ?? [:]).compactMapValues { $0.intValue }
        case "exact":
            mode = .exact
            exactTexts = (e.split["shares"]?.objectValue ?? [:]).compactMapValues {
                $0.intValue.map { AmountFormat.format($0, currency: e.currency) }
            }
        default:
            equalMembers = Set((e.split["members"]?.arrayValue ?? []).compactMap { $0.stringValue })
        }
    }

    public func amountMinor() -> Int64? { AmountFormat.parse(amountText, currency: currency) }

    /// The rate that goes into the op: "1" for the base currency.
    /// A comma works as the decimal separator, as in the amount (the decimal pad of Turkish shows ",").
    public func effectiveRate(baseCurrency: String) -> String {
        currency == baseCurrency ? "1" : ExpenseDraft.normalizedRate(rateText)
    }

    public static func normalizedRate(_ text: String) -> String {
        String(text.trimmingSpaces().map { $0 == "," ? "." : $0 })
    }

    /// For exact mode: amount minus the sum of the typed shares. nil when the amount is not valid.
    public func exactRemaining() -> Int64? {
        guard let total = amountMinor() else { return nil }
        let sum = exactTexts.values.compactMap { AmountFormat.parse($0, currency: currency) }.reduce(0, +)
        return total - sum
    }

    public func money(baseCurrency: String) throws -> JSONValue {
        guard let amount = amountMinor(), (1...maxAmountMinor).contains(amount) else { throw DraftError.amountInvalid }
        let rate = effectiveRate(baseCurrency: baseCurrency)
        guard (try? parseRate(rate)) != nil, let r = try? parseRate(rate), r.mantissa > 0 else { throw DraftError.rateInvalid }
        let split: JSONValue
        switch mode {
        case .equal:
            guard !equalMembers.isEmpty else { throw DraftError.noMembers }
            split = .object(["mode": .string("equal"), "members": .array(equalMembers.sorted(by: byteLess).map { .string($0) })])
        case .weights:
            let chosen = weights.filter { $0.value > 0 }
            guard !chosen.isEmpty else { throw DraftError.noMembers }
            split = .object(["mode": .string("weights"), "weights": .object(chosen.mapValues { .int($0) })])
        case .exact:
            var shares: [String: JSONValue] = [:]
            var sum: Int64 = 0
            for (id, text) in exactTexts where !text.trimmingSpaces().isEmpty {
                guard let n = AmountFormat.parse(text, currency: currency) else { throw DraftError.amountInvalid }
                shares[id] = .int(n)
                sum += n
            }
            guard !shares.isEmpty else { throw DraftError.noMembers }
            guard sum == amount else { throw DraftError.exactMismatch(remaining: amount - sum) }
            split = .object(["mode": .string("exact"), "shares": .object(shares)])
        }
        return .object([
            "amountMinor": .int(amount), "currency": .string(currency), "rate": .string(rate), "split": split,
        ])
    }

    private func scalarFields() throws -> [String: JSONValue] {
        let t = title
        guard !t.isEmpty, !t.trimmingSpaces().isEmpty else { throw DraftError.titleEmpty }
        guard t.unicodeScalars.count <= 100 else { throw DraftError.titleTooLong }
        guard let payer = paidBy else { throw DraftError.noPayer }
        return ["title": .string(t.trimmingSpaces()), "date": .string(date), "category": .string(category), "paidBy": .string(payer)]
    }

    /// Body of `expense.add`.
    public func addBody(expenseId: String, baseCurrency: String) throws -> JSONValue {
        var o = try scalarFields()
        o["expenseId"] = .string(expenseId)
        o["money"] = try money(baseCurrency: baseCurrency)
        let body = JSONValue.object(o)
        guard isValidBody(type: "expense.add", body: body) else { throw DraftError.dateInvalid }
        return body
    }

    /// Body of `expense.edit` with only the changed field groups, or nil when nothing changed.
    public func editBody(original: GroupState.Expense, baseCurrency: String) throws -> JSONValue? {
        let now = try scalarFields()
        var fields: [String: JSONValue] = [:]
        let before: [String: JSONValue] = [
            "title": .string(original.title), "date": .string(original.date),
            "category": .string(original.category), "paidBy": .string(original.paidBy),
        ]
        for (k, v) in now where before[k] != v { fields[k] = v }
        let m = try money(baseCurrency: baseCurrency)
        let originalMoney = JSONValue.object([
            "amountMinor": .int(original.amountMinor), "currency": .string(original.currency),
            "rate": .string(original.rate), "split": original.split,
        ])
        if m != originalMoney { fields["money"] = m }
        guard !fields.isEmpty else { return nil }
        let body = JSONValue.object(["expenseId": .string(original.id), "fields": .object(fields)])
        guard isValidBody(type: "expense.edit", body: body) else { throw DraftError.dateInvalid }
        return body
    }
}
