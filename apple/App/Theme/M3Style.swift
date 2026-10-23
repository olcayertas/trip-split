import SwiftUI

/// Material 3 text style with Dynamic Type. Uses only the generated tokens.
struct M3FontModifier: ViewModifier {
    let style: M3TextStyle
    @ScaledMetric private var size: CGFloat

    init(_ style: M3TextStyle) {
        self.style = style
        _size = ScaledMetric(wrappedValue: style.size, relativeTo: .body)
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: style.weight)).tracking(style.tracking)
    }
}

extension View {
    func m3(_ style: M3TextStyle) -> some View { modifier(M3FontModifier(style)) }
}

/// Amount text with tabular figures.
struct MoneyText: View {
    let text: String
    var style: M3TextStyle = M3Type.bodyLarge
    var color: Color = M3Color.onSurface

    var body: some View {
        Text(text).m3(style).monospacedDigit().foregroundStyle(color)
    }
}

/// A signed balance: green with "+", red with "−". The sign always shows, so colour is never alone.
struct BalanceText: View {
    let minor: Int64
    let currency: String
    var style: M3TextStyle = M3Type.titleMedium

    var body: some View {
        MoneyText(text: Fmt.signedMoney(minor, currency), style: style,
                  color: minor > 0 ? M3Color.positive : (minor < 0 ? M3Color.error : M3Color.onSurfaceVariant))
    }
}

struct M3Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(M3Spacing.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(M3Color.surfaceContainerLow, in: RoundedRectangle(cornerRadius: M3Corner.medium))
    }
}

struct M3FilledButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .m3(M3Type.labelLarge)
            .foregroundStyle(enabled ? M3Color.onPrimary : M3Color.onSurface.opacity(0.38))
            .padding(.horizontal, M3Spacing.xl)
            .frame(minHeight: 48)
            .background(enabled ? M3Color.primary : M3Color.onSurface.opacity(0.12), in: Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

struct M3TonalButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .m3(M3Type.labelLarge)
            .foregroundStyle(M3Color.onSecondaryContainer)
            .padding(.horizontal, M3Spacing.xl)
            .frame(minHeight: 48)
            .background(M3Color.secondaryContainer, in: Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

struct M3TextButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .m3(M3Type.labelLarge)
            .foregroundStyle(M3Color.primary)
            .padding(.horizontal, M3Spacing.m)
            .frame(minHeight: 44)
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Extended floating action button.
struct M3FAB: View {
    let title: LocalizedStringKey
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .m3(M3Type.labelLarge)
                .foregroundStyle(M3Color.onPrimaryContainer)
                .padding(.horizontal, M3Spacing.l)
                .frame(minHeight: 56)
                .background(M3Color.primaryContainer, in: RoundedRectangle(cornerRadius: M3Corner.large))
                .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
        }
        .padding(M3Spacing.l)
    }
}

struct M3Chip: View {
    let title: String
    var systemImage: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: M3Spacing.xs) {
                if selected { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)) }
                else if let systemImage { Image(systemName: systemImage).font(.system(size: 12)) }
                Text(title).m3(M3Type.labelLarge)
            }
            .foregroundStyle(selected ? M3Color.onSecondaryContainer : M3Color.onSurfaceVariant)
            .padding(.horizontal, M3Spacing.m)
            .frame(minHeight: 44)
            .background(selected ? M3Color.secondaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: M3Corner.small))
            .overlay(RoundedRectangle(cornerRadius: M3Corner.small).stroke(selected ? Color.clear : M3Color.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension View {
    /// The look of a screen: M3 background behind forms and lists.
    func m3Screen() -> some View {
        scrollContentBackground(.hidden).background(M3Color.background).tint(M3Color.primary)
    }

    func m3Row() -> some View { listRowBackground(M3Color.surfaceContainerLow) }
}
