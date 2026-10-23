import SwiftUI
import TripSplitCore
import TripSplitStore

struct GroupView: View {
    let repository: GroupRepository
    let groupId: String
    @State private var tab = Tab.expenses

    enum Tab: Hashable { case expenses, balances, members }

    private var state: GroupState { repository.state(of: groupId) }

    var body: some View {
        TabView(selection: $tab) {
            ExpensesTab(repository: repository, groupId: groupId)
                .tabItem { Label("Expenses", systemImage: "receipt") }
                .tag(Tab.expenses)
            BalancesTab(repository: repository, groupId: groupId)
                .tabItem { Label("Balances", systemImage: "scale.3d") }
                .tag(Tab.balances)
            MembersTab(repository: repository, groupId: groupId)
                .tabItem { Label("Members", systemImage: "person.2.fill") }
                .tag(Tab.members)
        }
        .tint(M3Color.primary)
        .navigationTitle(state.group?.name ?? String(localized: "Group"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    NearbyView(repository: repository, groupId: groupId)
                } label: {
                    Label("Nearby", systemImage: "dot.radiowaves.left.and.right")
                }
                .accessibilityIdentifier("nearby")
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    HistoryView(repository: repository, groupId: groupId)
                } label: {
                    Label("History", systemImage: "clock.arrow.circlepath")
                }
            }
        }
    }
}

struct ExpensesTab: View {
    let repository: GroupRepository
    let groupId: String
    @State private var editing: GroupState.Expense?
    @State private var adding = false
    @State private var deleting: GroupState.Expense?

    private var state: GroupState { repository.state(of: groupId) }

    private var visible: [GroupState.Expense] {
        state.expenses.filter { !$0.deleted }.sorted { ($0.date, $0.id) > ($1.date, $1.id) }
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if visible.isEmpty {
                VStack(spacing: M3Spacing.m) {
                    Image(systemName: "receipt").font(.system(size: 48)).foregroundStyle(M3Color.outline)
                        .accessibilityHidden(true)
                    Text("No expenses yet").m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                    Text("Add the first expense of the trip.").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(visible) { e in
                        Button { editing = e } label: { row(e) }
                            .buttonStyle(.plain)
                            .m3Row()
                            .swipeActions {
                                Button("Delete", role: .destructive) { deleting = e }
                            }
                    }
                    Color.clear.frame(height: 72).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                .m3Screen()
            }
            M3FAB(title: "Add expense", systemImage: "plus") { adding = true }
                .accessibilityIdentifier("addExpense")
        }
        .background(M3Color.background)
        .sheet(isPresented: $adding) {
            ExpenseEditorView(repository: repository, groupId: groupId, expense: nil)
        }
        .sheet(item: $editing) { e in
            ExpenseEditorView(repository: repository, groupId: groupId, expense: e)
        }
        .confirmationDialog("Delete this expense?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { e in
            Button("Delete", role: .destructive) {
                Task { _ = try? await repository.append(groupId: groupId, type: "expense.delete", body: .object(["expenseId": .string(e.id)])) }
            }
        } message: { _ in
            Text("The expense stays in the history. A deleted expense cannot come back.")
        }
    }

    private func row(_ e: GroupState.Expense) -> some View {
        let base = state.group?.baseCurrency ?? e.currency
        return HStack(spacing: M3Spacing.m) {
            Image(systemName: Category.symbol(e.category))
                .frame(width: 40, height: 40)
                .foregroundStyle(M3Color.onSecondaryContainer)
                .background(M3Color.secondaryContainer, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                Text(e.title).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                Text("Paid by \(state.memberName(e.paidBy)) · \(Fmt.displayDate(e.date))")
                    .m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                if e.needsAttention {
                    Label("Needs attention", systemImage: "exclamationmark.triangle.fill")
                        .m3(M3Type.labelSmall).foregroundStyle(M3Color.error)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: M3Spacing.xxs) {
                MoneyText(text: Fmt.money(e.amountMinor, e.currency), style: M3Type.titleMedium)
                if e.currency != base, let b = e.baseAmountMinor {
                    MoneyText(text: "≈ " + Fmt.money(b, base), style: M3Type.bodySmall, color: M3Color.onSurfaceVariant)
                }
            }
        }
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }
}
