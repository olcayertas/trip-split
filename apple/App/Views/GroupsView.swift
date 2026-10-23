import SwiftUI
import TripSplitCore
import TripSplitStore

struct GroupRoute: Hashable {
    let id: String
    var openNearby = false
}

struct GroupsView: View {
    let repository: GroupRepository
    @Environment(AppModel.self) private var model
    @State private var path: [GroupRoute] = []
    @State private var creating = false
    @State private var joining = false

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if repository.groups.isEmpty {
                    emptyState
                } else {
                    List {
                        ForEach(repository.groups) { group in
                            NavigationLink(value: GroupRoute(id: group.id)) { row(group.id) }.m3Row()
                        }
                    }
                    .m3Screen()
                }
            }
            .background(M3Color.background)
            .navigationTitle("Groups")
            .navigationDestination(for: GroupRoute.self) { GroupView(repository: repository, groupId: $0.id, openNearby: $0.openNearby) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Join", systemImage: "person.badge.plus") { joining = true }
                        .accessibilityIdentifier("joinGroup")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Create", systemImage: "plus") { creating = true }
                        .accessibilityIdentifier("createGroup")
                }
            }
            .sheet(isPresented: $creating) {
                CreateGroupView(repository: repository) { id in path = [GroupRoute(id: id)] }
            }
            .confirmationDialog("Join this group?", isPresented: Binding(get: { model.pendingInvite != nil }, set: { if !$0 { model.pendingInvite = nil } }),
                                titleVisibility: .visible, presenting: model.pendingInvite) { invite in
                Button("Join") {
                    Task {
                        try? await repository.join(invite)
                        model.pendingInvite = nil
                        path = [GroupRoute(id: invite.groupId, openNearby: true)]
                    }
                }
            } message: { _ in
                Text("You will get all expenses of this group, and you can read and change them.")
            }
            .sheet(isPresented: $joining) {
                JoinGroupView(repository: repository) { id in path = [GroupRoute(id: id, openNearby: true)] }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: M3Spacing.l) {
            Image(systemName: "suitcase.rolling.fill").font(.system(size: 56)).foregroundStyle(M3Color.primary)
                .accessibilityHidden(true)
            Text("No groups yet").m3(M3Type.titleLarge).foregroundStyle(M3Color.onSurface)
            Text("Create a group for your trip, or join the group of a friend.")
                .m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).multilineTextAlignment(.center)
            Button("Create group") { creating = true }
                .buttonStyle(M3FilledButtonStyle()).accessibilityIdentifier("createGroupEmpty")
            Button("Join group") { joining = true }.buttonStyle(M3TonalButtonStyle())
        }
        .padding(M3Spacing.xl)
    }

    private func row(_ id: String) -> some View {
        let state = repository.state(of: id)
        return HStack {
            VStack(alignment: .leading, spacing: M3Spacing.xs) {
                Text(state.group?.name ?? String(localized: "Waiting for the first sync"))
                    .m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                Text("\(state.activeMembers.count) members").m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
            }
            Spacer()
            if let me = repository.myMember(in: id), let base = state.group?.baseCurrency {
                BalanceText(minor: state.balances[me.id] ?? 0, currency: base)
            }
        }
        .frame(minHeight: 56)
    }
}

struct CurrencyPicker: View {
    let title: LocalizedStringKey
    @Binding var code: String

    var body: some View {
        Picker(title, selection: $code) {
            ForEach(currencies.keys.sorted(), id: \.self) { c in
                Text("\(c) · \(Fmt.currencyName(c))").tag(c)
            }
        }
    }
}

struct CreateGroupView: View {
    let repository: GroupRepository
    let onCreated: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var currency = Locale.current.currency?.identifier.uppercased().nonEmpty(in: currencies) ?? "EUR"
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Group name", text: $name).accessibilityIdentifier("groupName").m3Row()
                    CurrencyPicker(title: "Group currency", code: $currency).m3Row()
                } footer: {
                    Text("All balances are shown in the group currency. You cannot change it later.")
                }
            }
            .m3Screen()
            .navigationTitle("Create group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        busy = true
                        Task {
                            let id = try? await repository.createGroup(
                                name: name.trimmingCharacters(in: .whitespaces), baseCurrency: currency,
                                creatorName: repository.identity.displayName)
                            busy = false
                            dismiss()
                            if let id { onCreated(id) }
                        }
                    }
                    .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("createGroupConfirm")
                }
            }
        }
    }
}

extension String {
    /// Self when it is a key of the table, else nil.
    func nonEmpty(in table: [String: Int]) -> String? { table[self] != nil ? self : nil }
}

struct JoinGroupView: View {
    let repository: GroupRepository
    let onJoined: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var failed = false
    @State private var scanning = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button { scanning = true } label: { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                        .accessibilityIdentifier("scanQR").m3Row()
                }
                Section {
                    TextField("tripsplit://join?g=…", text: $text, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("inviteLink").m3Row()
                } header: {
                    Text("Invite link")
                } footer: {
                    Text(failed ? "This link is not valid." : "Paste the invite link of the group.")
                        .foregroundStyle(failed ? M3Color.error : M3Color.onSurfaceVariant)
                }
            }
            .m3Screen()
            .sheet(isPresented: $scanning) {
                ScanInviteView { invite in
                    Task {
                        try? await repository.join(invite)
                        dismiss()
                        onJoined(invite.groupId)
                    }
                }
            }
            .navigationTitle("Join group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Join") {
                        guard let invite = Invite.parse(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                            failed = true
                            return
                        }
                        Task {
                            try? await repository.join(invite)
                            dismiss()
                            onJoined(invite.groupId)
                        }
                    }
                    .disabled(text.isEmpty)
                }
            }
        }
    }
}
