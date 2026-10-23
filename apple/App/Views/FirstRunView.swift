import SwiftUI
import TripSplitStore

struct FirstRunView: View {
    let repository: GroupRepository
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: M3Spacing.xl) {
            Spacer()
            Image(systemName: "airplane.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(M3Color.primary)
                .accessibilityHidden(true)
            Text("Welcome to TripSplit").m3(M3Type.headlineMedium).foregroundStyle(M3Color.onSurface)
            Text("Split holiday costs with your friends. No account and no server. Phones in the group sync with each other.")
                .m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurfaceVariant)
            VStack(alignment: .leading, spacing: M3Spacing.s) {
                Text("Your name").m3(M3Type.labelLarge).foregroundStyle(M3Color.onSurfaceVariant)
                TextField("Your name", text: $name)
                    .textContentType(.givenName)
                    .accessibilityIdentifier("firstRunName")
                    .padding(M3Spacing.m)
                    .frame(minHeight: 56)
                    .overlay(RoundedRectangle(cornerRadius: M3Corner.extraSmall).stroke(M3Color.outline))
            }
            Button("Continue") {
                try? repository.setDisplayName(name.trimmingCharacters(in: .whitespaces))
            }
            .buttonStyle(M3FilledButtonStyle())
            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityIdentifier("firstRunContinue")
            Spacer()
        }
        .padding(M3Spacing.xl)
    }
}
