import SwiftUI
import TripSplitCore
import TripSplitStore

struct MembersTab: View {
    let repository: GroupRepository
    let groupId: String
    var onNewMember: (String) -> Void = { _ in }
    @State private var adding = false
    @State private var renaming: GroupState.Member?
    @State private var removing: GroupState.Member?
    @State private var claimFailed = false
    @State private var inviting = false
    @State private var claiming = false
    @State private var lostMember: GroupState.Member?

    private var state: GroupState { repository.state(of: groupId) }
    private var mine: GroupState.Member? { repository.myMember(in: groupId) }

    var body: some View {
        List {
            Section {
                ForEach(state.members.filter { !$0.removed }) { m in
                    HStack {
                        VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                            Text(m.name).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                            Text(status(m)).m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                        }
                        Spacer()
                        if m.claimedBy == nil, mine == nil {
                            Button("This is me") { claim(m) }
                                .buttonStyle(M3TonalButtonStyle())
                                .accessibilityIdentifier("claim-\(m.name)")
                        } else if m.id == mine?.id {
                            Button("Not me") { unclaim(m) }.buttonStyle(M3TextButtonStyle())
                        }
                    }
                    .frame(minHeight: 56)
                    .m3Row()
                    .swipeActions {
                        Button("Remove", role: .destructive) { removing = m }
                        Button("Rename") { renaming = m }.tint(M3Color.secondary)
                    }
                }
            } footer: {
                if claimFailed {
                    Text("This member was claimed by another phone. Choose another member or add a new one.")
                        .foregroundStyle(M3Color.error)
                }
            }
            if let lostMember {
                Section {
                    Label("Your claim of \(lostMember.name) did not count: another phone claimed it first. Choose another member or add a new one.",
                          systemImage: "exclamationmark.triangle.fill")
                        .m3(M3Type.bodyMedium).foregroundStyle(M3Color.error).m3Row()
                }
            }
            Section {
                if mine == nil {
                    Button { claiming = true } label: { Label("Who are you?", systemImage: "person.crop.circle.badge.questionmark") }
                        .buttonStyle(M3TextButtonStyle())
                        .accessibilityIdentifier("whoAreYou")
                        .m3Row()
                }
                Button { inviting = true } label: { Label("Invite", systemImage: "qrcode") }
                    .buttonStyle(M3TextButtonStyle())
                    .accessibilityIdentifier("invite")
                    .m3Row()
                Button { adding = true } label: { Label("Add member", systemImage: "person.badge.plus") }
                    .buttonStyle(M3TextButtonStyle())
                    .accessibilityIdentifier("addMember")
                    .m3Row()
            }
            let left = state.members.filter { $0.removed }
            if !left.isEmpty {
                Section("Left the group") {
                    ForEach(left) { m in
                        HStack {
                            Text(m.name).foregroundStyle(M3Color.onSurfaceVariant)
                            Spacer()
                            if let b = state.balances[m.id], b != 0, let base = state.group?.baseCurrency {
                                BalanceText(minor: b, currency: base)
                            }
                        }
                        .m3Row()
                    }
                }
            }
        }
        .m3Screen()
        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 56) }
        .task(id: state.members) {
            let history = (try? await repository.history(of: groupId)) ?? []
            lostMember = lostClaim(history: history, deviceId: repository.deviceId, state: state)
        }
        .sheet(isPresented: $inviting) { InviteView(repository: repository, groupId: groupId) }
        .sheet(isPresented: $claiming) { ClaimSheet(repository: repository, groupId: groupId, onNewMember: onNewMember) }
        .sheet(isPresented: $adding) {
            NameSheet(title: "Add member", initial: "", confirm: "Add") { name in
                let id = UUID().uuidString.lowercased()
                Task {
                    _ = try? await repository.append(groupId: groupId, type: "member.add", body: .object([
                        "memberId": .string(id), "name": .string(name)]))
                    onNewMember(id)
                }
            }
        }
        .sheet(item: $renaming) { m in
            NameSheet(title: "Rename member", initial: m.name, confirm: "Save") { name in
                Task { _ = try? await repository.append(groupId: groupId, type: "member.rename", body: .object([
                    "memberId": .string(m.id), "name": .string(name)])) }
            }
        }
        .confirmationDialog("Remove this member?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { m in
            Button("Remove", role: .destructive) {
                Task { _ = try? await repository.append(groupId: groupId, type: "member.remove", body: .object(["memberId": .string(m.id)])) }
            }
        } message: { _ in
            Text("Their old expenses still count. They cannot be chosen for new expenses.")
        }
    }

    private func status(_ m: GroupState.Member) -> String {
        if m.id == mine?.id { return String(localized: "You") }
        return m.claimedBy == nil ? String(localized: "No phone yet") : String(localized: "Has a phone")
    }

    private func claim(_ m: GroupState.Member) {
        Task {
            _ = try? await repository.append(groupId: groupId, type: "member.claim", body: .object(["memberId": .string(m.id)]))
            // The fold decides. If another phone won the claim, tell the user.
            claimFailed = repository.myMember(in: groupId)?.id != m.id
        }
    }

    private func unclaim(_ m: GroupState.Member) {
        Task { _ = try? await repository.append(groupId: groupId, type: "member.unclaim", body: .object(["memberId": .string(m.id)])) }
    }
}

struct NameSheet: View {
    let title: LocalizedStringKey
    let initial: String
    let confirm: LocalizedStringKey
    let onDone: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name).accessibilityIdentifier("nameField").m3Row()
            }
            .m3Screen()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm) {
                        onDone(name.trimmingCharacters(in: .whitespaces))
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("nameConfirm")
                }
            }
            .onAppear { name = initial }
        }
        .presentationDetents([.medium])
    }
}
