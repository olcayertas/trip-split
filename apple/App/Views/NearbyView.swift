import SwiftUI
import TripSplitStore

/// Placeholder until the transports exist (phases 5 and 6).
struct NearbyView: View {
    let repository: GroupRepository
    let groupId: String

    var body: some View {
        VStack(spacing: M3Spacing.l) {
            Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 56)).foregroundStyle(M3Color.primary)
                .accessibilityHidden(true)
            Text("Nearby").m3(M3Type.headlineSmall).foregroundStyle(M3Color.onSurface)
            Text("Phones of this group on the same Wi-Fi or hotspot, or close by with Bluetooth, will show here. Keep this screen open on both phones while they sync.")
                .m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).multilineTextAlignment(.center)
            Text("Sync is not available in this build yet.").m3(M3Type.labelLarge).foregroundStyle(M3Color.onSurfaceVariant)
        }
        .padding(M3Spacing.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(M3Color.background)
        .navigationTitle("Nearby")
        .navigationBarTitleDisplayMode(.inline)
    }
}
