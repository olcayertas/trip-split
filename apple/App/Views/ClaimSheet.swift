import SwiftUI
import TripSplitCore
import TripSplitStore

/// "Who are you?" for a phone that has no member in the group yet (PLAN §4.2, §7).
struct ClaimSheet: View {
    let repository: GroupRepository
    let groupId: String
    @Environment(\.dismiss) private var dismiss
    @State private var lost: GroupState.Member?
    @State private var busy = false

    private var state: GroupState { repository.state(of: groupId) }
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
                Section {
                    Button { addMe() } label: { Label("I am not in the list. Add me.", systemImage: "person.badge.plus") }
                        .buttonStyle(M3TextButtonStyle())
                        .accessibilityIdentifier("addMe")
                        .m3Row()
                }
            }
            .m3Screen()
            .disabled(busy)
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

    private func addMe() {
        busy = true
        let id = UUID().uuidString.lowercased()
        let name = repository.identity.displayName
        Task {
            _ = try? await repository.append(groupId: groupId, type: "member.add", body: .object(["memberId": .string(id), "name": .string(name)]))
            _ = try? await repository.append(groupId: groupId, type: "member.claim", body: .object(["memberId": .string(id)]))
            busy = false
            dismiss()
        }
    }
}
