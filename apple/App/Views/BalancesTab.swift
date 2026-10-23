import SwiftUI
import TripSplitCore
import TripSplitStore

struct BalancesTab: View {
    let repository: GroupRepository
    let groupId: String
    @State private var recording: PaymentDraftSeed?

    private var state: GroupState { repository.state(of: groupId) }
    private var base: String { state.group?.baseCurrency ?? "EUR" }

    struct PaymentDraftSeed: Identifiable {
        let id = UUID()
        var from: String?
        var to: String?
        var amountMinor: Int64?
    }

    var body: some View {
        List {
            Section("Balances") {
                ForEach(state.members) { m in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(m.name).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                            let balance = state.balances[m.id] ?? 0
                            Text(LocalizedStringKey(balance > 0 ? "Gets back" : (balance < 0 ? "Owes" : "Settled")))
                                .m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                        }
                        Spacer()
                        BalanceText(minor: state.balances[m.id] ?? 0, currency: base)
                    }
                    .frame(minHeight: 48)
                    .accessibilityElement(children: .combine)
                    .m3Row()
                }
            }
            Section("Suggested payments") {
                if state.suggestedPayments.isEmpty {
                    Text("Everyone is settled.").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
                }
                ForEach(Array(state.suggestedPayments.enumerated()), id: \.offset) { _, p in
                    HStack {
                        VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                            Text("\(state.memberName(p.from)) pays \(state.memberName(p.to))")
                                .m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurface)
                            MoneyText(text: Fmt.money(p.amountMinor, base), style: M3Type.titleMedium)
                        }
                        Spacer()
                        Button("Record") { recording = PaymentDraftSeed(from: p.from, to: p.to, amountMinor: p.amountMinor) }
                            .buttonStyle(M3TonalButtonStyle())
                    }
                    .m3Row()
                }
            }
            Section {
                Button { recording = PaymentDraftSeed() } label: {
                    Label("Record payment", systemImage: "banknote")
                }
                .buttonStyle(M3TextButtonStyle())
                .accessibilityIdentifier("recordPayment")
                .m3Row()
            }
            let payments = state.settlements.filter { !$0.deleted }
            if !payments.isEmpty {
                Section("Recorded payments") {
                    ForEach(payments) { s in
                        HStack {
                            VStack(alignment: .leading) {
                                Text("\(state.memberName(s.fromMember)) paid \(state.memberName(s.toMember))").m3(M3Type.bodyLarge)
                                Text(Fmt.displayDate(s.date)).m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                            }
                            Spacer()
                            MoneyText(text: Fmt.money(s.amountMinor, s.currency))
                        }
                        .m3Row()
                        .swipeActions {
                            Button("Delete", role: .destructive) {
                                Task { _ = try? await repository.append(groupId: groupId, type: "settlement.delete", body: .object(["settlementId": .string(s.id)])) }
                            }
                        }
                    }
                }
            }
        }
        .m3Screen()
        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 56) }
        .sheet(item: $recording) { seed in
            RecordPaymentView(repository: repository, groupId: groupId, seed: seed)
        }
    }
}

struct RecordPaymentView: View {
    let repository: GroupRepository
    let groupId: String
    let seed: BalancesTab.PaymentDraftSeed
    @Environment(\.dismiss) private var dismiss
    @State private var from = ""
    @State private var to = ""
    @State private var amountText = ""
    @State private var currency = ""
    @State private var rateText = ""
    @State private var date = Date()

    private var state: GroupState { repository.state(of: groupId) }
    private var base: String { state.group?.baseCurrency ?? "EUR" }
    private var rate: String { currency == base ? "1" : ExpenseDraft.normalizedRate(rateText) }

    private var body_: JSONValue? {
        guard let amount = AmountFormat.parse(amountText, currency: currency), amount > 0, from != to, !from.isEmpty, !to.isEmpty else { return nil }
        let body: JSONValue = .object([
            "settlementId": .string(UUID().uuidString.lowercased()), "fromMember": .string(from), "toMember": .string(to),
            "amountMinor": .int(amount), "currency": .string(currency), "rate": .string(rate), "date": .string(Fmt.dayString(date)),
        ])
        return isValidBody(type: "settlement.add", body: body) ? body : nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("From", selection: $from) { ForEach(state.activeMembers) { Text($0.name).tag($0.id) } }.m3Row()
                    Picker("To", selection: $to) { ForEach(state.activeMembers) { Text($0.name).tag($0.id) } }.m3Row()
                    HStack {
                        TextField("Amount", text: $amountText).keyboardType(.decimalPad).monospacedDigit()
                            .accessibilityIdentifier("paymentAmount")
                        Divider()
                        Picker("Currency", selection: $currency) {
                            ForEach(currencies.keys.sorted(), id: \.self) { Text($0).tag($0) }
                        }
                        .labelsHidden()
                    }
                    .m3Row()
                    if currency != base {
                        HStack {
                            Text("1 \(currency) =").foregroundStyle(M3Color.onSurfaceVariant)
                            TextField("Rate", text: $rateText).keyboardType(.decimalPad).monospacedDigit()
                            Text(base).foregroundStyle(M3Color.onSurfaceVariant)
                        }
                        .m3Row()
                    }
                    DatePicker("Date", selection: $date, displayedComponents: .date).m3Row()
                }
            }
            .m3Screen()
            .navigationTitle("Record payment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let body = body_ else { return }
                        Task {
                            _ = try? await repository.append(groupId: groupId, type: "settlement.add", body: body)
                            dismiss()
                        }
                    }
                    .disabled(body_ == nil)
                    .accessibilityIdentifier("savePayment")
                }
            }
            .onAppear {
                let members = state.activeMembers
                from = seed.from ?? repository.myMember(in: groupId)?.id ?? members.first?.id ?? ""
                to = seed.to ?? members.first { $0.id != from }?.id ?? ""
                currency = base
                if let a = seed.amountMinor { amountText = AmountFormat.format(a, currency: base) }
            }
        }
    }
}
