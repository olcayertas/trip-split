import SwiftUI
import TripSplitCore
import TripSplitStore

struct ExpenseEditorView: View {
    let repository: GroupRepository
    let groupId: String
    let expense: GroupState.Expense?
    @Environment(\.dismiss) private var dismiss
    @State private var draft: ExpenseDraft
    @State private var date: Date
    @State private var saving = false

    init(repository: GroupRepository, groupId: String, expense: GroupState.Expense?) {
        self.repository = repository
        self.groupId = groupId
        self.expense = expense
        let state = repository.state(of: groupId)
        if let expense {
            _draft = State(initialValue: ExpenseDraft(expense: expense))
            _date = State(initialValue: Fmt.day(expense.date))
        } else {
            let me = repository.myMember(in: groupId)?.id ?? state.activeMembers.first?.id
            let today = Fmt.dayString(Date())
            _draft = State(initialValue: ExpenseDraft(
                currency: state.group?.baseCurrency ?? "EUR", date: today, paidBy: me, members: state.activeMembers.map(\.id)))
            _date = State(initialValue: Date())
        }
    }

    private var state: GroupState { repository.state(of: groupId) }
    private var base: String { state.group?.baseCurrency ?? "EUR" }

    /// Members that can be picked: active ones, plus any member already in this expense.
    private var members: [GroupState.Member] {
        let used = Set(draft.equalMembers).union(draft.weights.keys).union(draft.exactTexts.keys).union([draft.paidBy].compactMap { $0 })
        return state.members.filter { !$0.removed || (expense != nil && used.contains($0.id)) }
    }

    private var validation: DraftError? {
        do {
            if let expense { _ = try draft.editBody(original: expense, baseCurrency: base) }
            else { _ = try draft.addBody(expenseId: "00000000-0000-4000-8000-000000000000", baseCurrency: base) }
            return nil
        } catch let e as DraftError { return e } catch { return .dateInvalid }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $draft.title).accessibilityIdentifier("expenseTitle").m3Row()
                    HStack {
                        TextField("Amount", text: $draft.amountText)
                            .keyboardType(.decimalPad).monospacedDigit()
                            .accessibilityIdentifier("expenseAmount")
                        Divider()
                        Picker("Currency", selection: $draft.currency) {
                            ForEach(currencies.keys.sorted(), id: \.self) { Text($0).tag($0) }
                        }
                        .labelsHidden()
                    }
                    .m3Row()
                    if draft.currency != base {
                        HStack {
                            Text("1 \(draft.currency) =").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant)
                            TextField("Rate", text: $draft.rateText).keyboardType(.decimalPad).monospacedDigit()
                                .accessibilityIdentifier("expenseRate")
                            Text(base).m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant)
                        }
                        .m3Row()
                        if let minor = draft.amountMinor(), let converted = try? convert(
                            amountMinor: minor, currency: draft.currency, rate: ExpenseDraft.normalizedRate(draft.rateText), base: base)
                        {
                            Text("≈ \(Fmt.money(converted, base))").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
                        }
                    }
                }
                Section {
                    Picker("Paid by", selection: Binding(get: { draft.paidBy ?? "" }, set: { draft.paidBy = $0 })) {
                        ForEach(members) { Text($0.name).tag($0.id) }
                    }
                    .m3Row()
                    DatePicker("Date", selection: $date, displayedComponents: .date)
                        .onChange(of: date) { draft.date = Fmt.dayString(date) }
                        .m3Row()
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(Category.all, id: \.self) { c in
                                M3Chip(title: Category.title(c), systemImage: Category.symbol(c), selected: draft.category == c) {
                                    draft.category = c
                                }
                            }
                        }
                    }
                    .m3Row()
                }
                Section {
                    Picker("Split", selection: $draft.mode) {
                        Text("Equal").tag(ExpenseDraft.Mode.equal)
                        Text("Weights").tag(ExpenseDraft.Mode.weights)
                        Text("Exact").tag(ExpenseDraft.Mode.exact)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("splitMode")
                    .onChange(of: draft.mode) {
                        if draft.mode == .weights, draft.weights.isEmpty {
                            for id in draft.equalMembers { draft.weights[id] = 1 }
                        }
                    }
                    .m3Row()
                    ForEach(members) { m in splitRow(m).m3Row() }
                    if draft.mode == .exact, let remaining = draft.exactRemaining() {
                        HStack {
                            Text(LocalizedStringKey(remaining == 0 ? "All assigned" : "Still to assign")).m3(M3Type.labelLarge)
                            Spacer()
                            MoneyText(text: Fmt.money(remaining, draft.currency), style: M3Type.labelLarge,
                                      color: remaining == 0 ? M3Color.positive : M3Color.error)
                        }
                        .m3Row()
                    }
                } header: {
                    Text("Split between")
                }
                if let message = errorText {
                    Section { Text(message).m3(M3Type.bodyMedium).foregroundStyle(M3Color.error).m3Row() }
                }
            }
            .m3Screen()
            .navigationTitle(expense == nil ? "Add expense" : "Edit expense")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(saving || validation != nil)
                        .accessibilityIdentifier("saveExpense")
                }
            }
        }
    }

    private var errorText: String? {
        // Do not nag about an empty form; show the problem once the user typed an amount.
        guard !draft.amountText.isEmpty || !draft.title.isEmpty, let error = validation else { return nil }
        switch error {
        case .titleEmpty, .noPayer: return nil
        case .titleTooLong: return String(localized: "The title is too long.")
        case .amountInvalid: return draft.amountText.isEmpty ? nil : String(localized: "Enter a valid amount.")
        case .rateInvalid: return String(localized: "Enter an exchange rate, for example 34.75.")
        case .noMembers: return String(localized: "Choose at least one member.")
        case let .exactMismatch(remaining):
            return String(localized: "The shares differ from the amount by \(Fmt.money(abs(remaining), draft.currency)).")
        case .dateInvalid: return String(localized: "The date is not valid.")
        }
    }

    @ViewBuilder
    private func splitRow(_ m: GroupState.Member) -> some View {
        switch draft.mode {
        case .equal:
            Toggle(m.name, isOn: Binding(
                get: { draft.equalMembers.contains(m.id) },
                set: { if $0 { draft.equalMembers.insert(m.id) } else { draft.equalMembers.remove(m.id) } }))
        case .weights:
            Stepper(value: Binding(get: { draft.weights[m.id] ?? 0 }, set: { draft.weights[m.id] = $0 }), in: 0...1000) {
                HStack {
                    Text(m.name)
                    Spacer()
                    Text("\(draft.weights[m.id] ?? 0)").monospacedDigit().foregroundStyle(M3Color.onSurfaceVariant)
                }
            }
        case .exact:
            HStack {
                Text(m.name)
                TextField("0", text: Binding(get: { draft.exactTexts[m.id] ?? "" }, set: { draft.exactTexts[m.id] = $0 }))
                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing).monospacedDigit()
            }
        }
    }

    private func save() {
        saving = true
        Task {
            do {
                if let expense {
                    if let body = try draft.editBody(original: expense, baseCurrency: base) {
                        try await repository.append(groupId: groupId, type: "expense.edit", body: body)
                    }
                } else {
                    let id = UUID().uuidString.lowercased()
                    try await repository.append(groupId: groupId, type: "expense.add", body: try draft.addBody(expenseId: id, baseCurrency: base))
                }
                dismiss()
            } catch {
                saving = false
            }
        }
    }
}
