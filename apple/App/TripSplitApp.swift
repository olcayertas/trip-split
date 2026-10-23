import SwiftUI
import TripSplitCore
import TripSplitNet
import TripSplitStore

@main
struct TripSplitApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(M3Color.primary)
        }
    }
}

/// Opens the database and the repository once. Launch arguments serve the UI tests and the screenshots:
/// `-uitest` uses a temporary database and an in-memory install marker; `-seed-demo` adds sample data.
@MainActor
@Observable
final class AppModel {
    private(set) var repository: GroupRepository?
    private(set) var failure: String?
    private(set) var coordinator: SyncCoordinator?
    /// An invite from a `tripsplit://` link or a scan, waiting for the user to confirm.
    var pendingInvite: Invite?

    init() {
        let args = ProcessInfo.processInfo.arguments
        do {
            let testing = args.contains("-uitest")
            let directory: URL
            let marker: any InstallMarkerStore
            if testing {
                directory = FileManager.default.temporaryDirectory.appending(path: "tripsplit-ui-" + UUID().uuidString)
                marker = InMemoryInstallMarker()
            } else {
                directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                    .appending(path: "TripSplit", directoryHint: .isDirectory)
                marker = KeychainInstallMarker()
            }
            let database = try AppDatabase.open(directory: directory)
            let wall = Int64(Date().timeIntervalSince1970 * 1000)
            let identity = try database.loadIdentity(marker: marker, wall: wall)
            let repo = GroupRepository(database: database, identity: identity)
            repo.start()
            repository = repo
            coordinator = SyncCoordinator(repository: repo, transports: [LANTransport(), BLETransport()])
            #if DEBUG
            if args.contains("-seed-demo") || args.contains("-demo-fixed") {
                let fixed = args.contains("-demo-fixed")
                Task { await DemoData.seed(repo, fixed: fixed) }
            }
            if args.contains("-auto-sync"), let coordinator {
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    await coordinator.start()
                }
            }
            #else
            if args.contains("-seed-demo") {
                Task { await DemoData.seed(repo) }
            }
            #endif
        } catch {
            failure = String(describing: error)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if let repo = model.repository {
                if repo.identity.displayName.isEmpty {
                    FirstRunView(repository: repo)
                } else {
                    GroupsView(repository: repo)
                }
            } else {
                ContentUnavailableView("Cannot open the data", systemImage: "exclamationmark.triangle",
                                       description: Text(model.failure ?? ""))
            }
        }
        .background(M3Color.background)
        .onOpenURL { url in
            if let invite = Invite.parse(url.absoluteString) { model.pendingInvite = invite }
        }
    }
}
