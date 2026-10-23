import SwiftUI
import TripSplitCore
import TripSplitStore
import UIKit

/// Shows the invite QR code and a share button. The link holds the group key, so sharing asks first.
struct InviteView: View {
    let repository: GroupRepository
    let groupId: String
    @Environment(\.dismiss) private var dismiss
    @State private var invite: Invite?
    @State private var confirmShare = false
    @State private var sharing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: M3Spacing.l) {
                    Text("Let a friend scan this code in the TripSplit app. Then open Nearby on both phones.")
                        .m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurfaceVariant).multilineTextAlignment(.center)
                    if let invite, let image = QRCode.image(for: invite.url) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 280)
                            .padding(M3Spacing.l)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: M3Corner.large))
                            .accessibilityLabel("Invite QR code")
                    }
                    M3Card {
                        Label("Anyone who has this code or link can read and change the group. Show it only to members of the group.",
                              systemImage: "lock.open.trianglebadge.exclamationmark")
                            .m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurface)
                    }
                    Button { confirmShare = true } label: { Label("Share link", systemImage: "square.and.arrow.up") }
                        .buttonStyle(M3TonalButtonStyle())
                        .disabled(invite == nil)
                        .accessibilityIdentifier("shareLink")
                }
                .padding(M3Spacing.l)
            }
            .background(M3Color.background)
            .navigationTitle("Invite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { invite = try? await repository.invite(for: groupId) }
            .alert("Share the link?", isPresented: $confirmShare) {
                Button("Share") { sharing = true }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Anyone who gets this link can read and change the group. Send it only to group members.")
            }
            .sheet(isPresented: $sharing) {
                if let invite { ShareSheet(items: [invite.url]).presentationDetents([.medium, .large]) }
            }
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
