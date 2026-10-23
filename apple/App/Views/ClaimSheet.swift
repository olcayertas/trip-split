import SwiftUI
import TripSplitCore
import TripSplitStore

/// "Who are you?" for a phone that has no member in the group yet (PLAN §4.2, §7).
struct ClaimSheet: View {
    let repository: GroupRepository
    let groupId: String
    var onNewMember: (String) -> Void = { _ in }
    @Environment(\.dismiss) private var dismiss
    @State private var lost: GroupState.Member?
    @State private var busy = false
    @State private var takingOver: GroupState.Member?

    private var state: GroupState { repository.state(of: groupId) }
    /// Members that another phone holds. A restored or lost phone can take them over (SPEC §4.4).
    private var held: [GroupState.Member] {
        state.members.filter { !$0.removed && $0.claimedBy != nil && $0.claimedBy != repository.deviceId }
    }
    private var free: [GroupState.Member] { state.members.filter { !$0.removed && $0.claimedBy == nil } }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if repository.identity.mintedAfterRestore {
                        Label("This phone got a new identity after a restore from a backup. A member that you claimed before still belongs to the old identity. Add yourself as a new member.",
                              systemImage: "arrow.triangle.2.circlepath")
                            .m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurface).m3Row()
                    }
                    if let lost {
                        Label("\(lost.name) was claimed by another phone. Choose another member or add yourself as a new member.",
                              systemImage: "exclamationmark.triangle.fill")
                            .m3(M3Type.bodyMedium).foregroundStyle(M3Color.error).m3Row()
                    }
                } footer: {
                    Text("Choose the member that is you. Your expenses and balance then show on this phone.")
                }
                Section("Members without a phone") {
                    if free.isEmpty {
                        Text("No free member.").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
                    }
                    ForEach(free) { m in
                        Button { claim(m) } label: {
                            HStack {
                                Text(m.name).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                                Spacer()
                                Text("This is me").m3(M3Type.labelLarge).foregroundStyle(M3Color.primary)
                            }
                            .frame(minHeight: 48)
                        }
                        .accessibilityIdentifier("claimSheet-\(m.name)")
                        .m3Row()
                    }
                }
                if !held.isEmpty {
                    Section {
                        ForEach(held) { m in
                            HStack {
                                Text(m.name).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                                Spacer()
                                Button("Take over") { takingOver = m }
                                    .buttonStyle(M3TonalButtonStyle())
                                    .accessibilityIdentifier("takeOver-\(m.name)")
                            }
                            .frame(minHeight: 56)
                            .m3Row()
                        }
                    } header: {
                        Text("Members with another phone")
                    } footer: {
                        Text("Use this if the member was linked to your old phone, for example before a restore.")
                    }
                }
                Section {
                    Button { addMe() } label: { Label("I am not in the list. Add me.", systemImage: "person.badge.plus") }
                        .buttonStyle(M3TextButtonStyle())
                        .accessibilityIdentifier("addMe")
                        .m3Row()
                }
            }
            .m3Screen()
            .disabled(busy)
            .confirmationDialog("Take over \(takingOver?.name ?? "")?", isPresented: Binding(get: { takingOver != nil }, set: { if !$0 { takingOver = nil } }),
                                titleVisibility: .visible, presenting: takingOver) { m in
                Button("Take over") { takeOver(m) }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("This member is linked to another phone. Take it over only if that phone is yours or lost.")
            }
            .navigationTitle("Who are you?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Not now") { dismiss() } } }
        }
    }

    private func claim(_ m: GroupState.Member) {
        busy = true
        Task {
            _ = try? await repository.append(groupId: groupId, type: "member.claim", body: .object(["memberId": .string(m.id)]))
            busy = false
            // The fold decides. If another phone has the earlier claim, we stay without a member.
            if repository.myMember(in: groupId)?.id == m.id { dismiss() } else { lost = m }
        }
    }

    private func takeOver(_ m: GroupState.Member) {
        busy = true
        Task {
            _ = try? await repository.append(groupId: groupId, type: "member.unclaim", body: .object(["memberId": .string(m.id)]))
            _ = try? await repository.append(groupId: groupId, type: "member.claim", body: .object(["memberId": .string(m.id)]))
            busy = false
            if repository.myMember(in: groupId)?.id == m.id { dismiss() } else { lost = m }
        }
    }

    private func addMe() {
        busy = true
        let id = UUID().uuidString.lowercased()
        let name = repository.identity.displayName
        Task {
            _ = try? await repository.append(groupId: groupId, type: "member.add", body: .object(["memberId": .string(id), "name": .string(name)]))
            _ = try? await repository.append(groupId: groupId, type: "member.claim", body: .object(["memberId": .string(id)]))
            busy = false
            dismiss()
            onNewMember(id)
        }
    }
}
