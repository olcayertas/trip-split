import SwiftUI
import TripSplitNet
import TripSplitStore
import UIKit

/// Live list of phones around, with the transport, progress and the last result (PLAN §11, screen 9).
struct NearbyView: View {
    let repository: GroupRepository
    let groupId: String
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            if let relay = model.relay { internetSection(relay) }
            if let coordinator = model.coordinator {
                content(coordinator)
            }
        }
        .m3Screen()
        .navigationTitle("Nearby")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // The display stays on while this screen is open: a locked phone stops Bluetooth advertising.
            UIApplication.shared.isIdleTimerDisabled = true
            await model.coordinator?.start()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            Task { await model.coordinator?.stop() }
        }
        .onChange(of: scenePhase) {
            guard let coordinator = model.coordinator else { return }
            Task {
                if scenePhase == .active {
                    UIApplication.shared.isIdleTimerDisabled = true
                    await coordinator.start()
                } else {
                    await coordinator.stop()
                }
            }
        }
    }

    /// The per-device switch of the internet relay (PLAN §9.4). The line below it says what the relay sees.
    @ViewBuilder
    private func internetSection(_ relay: RelayCoordinator) -> some View {
        Section("Internet") {
            Toggle(isOn: Binding(get: { relay.isEnabled && relay.isConfigured }, set: { relay.setEnabled($0) })) {
                VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                    Text("Sync over the internet").m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurface)
                    if let line = relayLine(relay) {
                        Text(line).m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                    }
                }
            }
            .disabled(!relay.isConfigured)
            .accessibilityIdentifier("relaySwitch")
            .m3Row()
            Text("The relay server sees your IP address and the time of each sync. It cannot read your group.")
                .m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
        }
    }

    private func relayLine(_ relay: RelayCoordinator) -> String? {
        switch relay.status {
        case .notSetUp: String(localized: "Not set up yet")
        case let .done(date, _, _): String(localized: "Synced over the internet at \(date.formatted(date: .omitted, time: .shortened))")
        case let .waiting(until, _): String(localized: "Cannot reach the relay. Next try at \(until.formatted(date: .omitted, time: .shortened)).")
        case .off, .idle, .syncing: nil
        }
    }

    @ViewBuilder
    private func content(_ coordinator: SyncCoordinator) -> some View {
        Section {
            HStack(spacing: M3Spacing.m) {
                if coordinator.isRunning { ProgressView() }
                Text(coordinator.isRunning ? "Looking for phones of this group…" : "Not looking for phones")
                    .m3(M3Type.bodyLarge).foregroundStyle(M3Color.onSurface)
            }
            .m3Row()
            if let problem = coordinator.problem {
                Label(problemText(problem), systemImage: "exclamationmark.triangle.fill")
                    .m3(M3Type.bodyMedium).foregroundStyle(M3Color.error).m3Row()
                    .accessibilityHint(problem)
            }
            if coordinator.clockSkewWith != nil {
                Label("A phone in this group has a wrong clock.", systemImage: "clock.badge.exclamationmark")
                    .m3(M3Type.bodyMedium).foregroundStyle(M3Color.error).m3Row()
            }
        }
        Section("Phones") {
            let rows = coordinator.rows.filter { $0.groupIds.contains(groupId) || $0.groupIds.isEmpty }
            if rows.isEmpty {
                Text("No phone found yet.").m3(M3Type.bodyMedium).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
            }
            ForEach(rows) { row in
                HStack(spacing: M3Spacing.m) {
                    Image(systemName: row.kind == .wifi ? "wifi" : "dot.radiowaves.left.and.right")
                        .foregroundStyle(M3Color.primary)
                        .accessibilityLabel(row.kind == .wifi ? "Wi-Fi" : "Bluetooth")
                    VStack(alignment: .leading, spacing: M3Spacing.xxs) {
                        Text(label(for: row)).m3(M3Type.titleMedium).foregroundStyle(M3Color.onSurface)
                        Text(statusText(row.status)).m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant)
                    }
                    Spacer()
                    if case .syncing = row.status { ProgressView() }
                }
                .frame(minHeight: 56)
                .m3Row()
            }
        }
        if let last = coordinator.lastResult {
            Section("Last sync") { Text(last).m3(M3Type.bodyMedium).monospacedDigit().m3Row() }
        }
        Section {
            Text("Sync works while this screen is open on both phones. Phones must be on the same Wi-Fi network or hotspot, or close to each other with Bluetooth. Some hotel networks block Wi-Fi sync. Then use a phone hotspot or Bluetooth.")
                .m3(M3Type.bodySmall).foregroundStyle(M3Color.onSurfaceVariant).m3Row()
        }
    }

    private func problemText(_ problem: String) -> String {
        if problem.contains("Bluetooth permission") { return String(localized: "Bluetooth is not allowed. Allow it for TripSplit in Settings.") }
        if problem.contains("Bluetooth is off") { return String(localized: "Bluetooth is off. Turn it on to find phones by Bluetooth.") }
        return String(localized: "Network problem. Check that TripSplit may use the local network in Settings.")
    }

    private func label(for row: SyncCoordinator.Row) -> String {
        let state = repository.state(of: groupId)
        if let member = state.members.first(where: { $0.claimedBy == row.deviceId }) { return member.name }
        return String(localized: "Phone \(String(row.deviceId.prefix(4)))")
    }

    private func statusText(_ status: SyncCoordinator.Status) -> String {
        switch status {
        case .idle: String(localized: "Waiting")
        case let .syncing(sent, received): String(localized: "Syncing: \(received) received, \(sent) sent")
        case let .done(date, sent, received):
            String(localized: "Synced \(date.formatted(date: .omitted, time: .shortened)): \(received) received, \(sent) sent")
        case let .failed(date, _): String(localized: "Failed \(date.formatted(date: .omitted, time: .shortened))")
        }
    }
}
