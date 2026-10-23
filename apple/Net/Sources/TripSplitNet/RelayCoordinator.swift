// Decides when the relay sync runs (SPEC §10.4.4): when a group screen opens, after a save, and every 60 s
// while the app is in the foreground and online. A failure starts the back-off of SPEC §10.4.3.

import Foundation
import Network
import Observation
import TripSplitCore
import TripSplitStore

@MainActor
@Observable
public final class RelayCoordinator {
    public enum Status: Equatable, Sendable {
        /// The build has no relay URL. The switch shows "Not set up yet".
        case notSetUp
        case off
        case idle
        case syncing
        case done(Date, received: Int, sent: Int)
        /// A failure; the next try starts at the date.
        case waiting(until: Date, reason: String)
    }

    public static let defaultsKey = "relaySyncEnabled"

    public private(set) var status: Status
    public private(set) var isOnline = true
    public private(set) var isEnabled: Bool

    @ObservationIgnored private let repository: GroupRepository
    @ObservationIgnored private let baseURL: URL?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let interval: TimeInterval
    @ObservationIgnored private let makeAPI: @Sendable (URL, [UInt8]) -> any RelayAPI
    @ObservationIgnored private let monitor: NWPathMonitor?
    @ObservationIgnored private var active = false
    @ObservationIgnored private var running = false
    @ObservationIgnored private var again: Set<String> = []
    @ObservationIgnored private var backoff = RelayBackoff()
    @ObservationIgnored private var blockedUntil = Date.distantPast
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var retry: Task<Void, Never>?

    /// `watchNetwork: false` keeps the path monitor away in tests.
    public init(repository: GroupRepository, baseURL: URL?, defaults: UserDefaults = .standard,
                interval: TimeInterval = 60, watchNetwork: Bool = true,
                makeAPI: @escaping @Sendable (URL, [UInt8]) -> any RelayAPI = { RelayHTTPClient(baseURL: $0, groupKey: $1) }) {
        self.repository = repository
        self.baseURL = baseURL
        self.defaults = defaults
        self.interval = interval
        self.makeAPI = makeAPI
        // On by default (PLAN §9.4): a missing value means on.
        let enabled = defaults.object(forKey: Self.defaultsKey) as? Bool ?? true
        isEnabled = enabled
        status = baseURL == nil ? .notSetUp : (enabled ? .idle : .off)
        monitor = watchNetwork ? NWPathMonitor() : nil
        repository.onLocalWrite = { [weak self] groupId in self?.trigger(groupId) }
        monitor?.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.setOnline(online) }
        }
        monitor?.start(queue: DispatchQueue(label: "tripsplit.relay.path"))
    }

    deinit { monitor?.cancel() }

    public var isConfigured: Bool { baseURL != nil }

    // MARK: Switches

    public func setEnabled(_ value: Bool) {
        isEnabled = value
        defaults.set(value, forKey: Self.defaultsKey)
        if baseURL == nil { status = .notSetUp } else if !value { status = .off } else { status = .idle; trigger(nil) }
    }

    /// The app calls this when the scene becomes active or leaves the foreground.
    public func setActive(_ value: Bool) {
        guard value != active else { return }
        active = value
        ticker?.cancel()
        retry?.cancel()
        ticker = nil
        guard value else { return }
        trigger(nil)
        let seconds = interval
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(seconds))
                if Task.isCancelled { return }
                self?.trigger(nil)
            }
        }
    }

    private func setOnline(_ value: Bool) {
        let came = value && !isOnline
        isOnline = value
        if came { trigger(nil) }
    }

    // MARK: Sync

    /// Starts a relay sync for one group, or for all groups when `groupId` is nil. Returns at once.
    public func trigger(_ groupId: String?) {
        guard baseURL != nil, isEnabled, active, isOnline, Date() >= blockedUntil else { return }
        again.insert(groupId ?? "*")
        guard !running else { return }
        running = true
        Task { await drain() }
    }

    /// Runs one pass for every trigger that waits. A trigger that comes during a pass makes one more pass.
    private func drain() async {
        while !again.isEmpty {
            let wanted = again
            again = []
            await pass(only: wanted.contains("*") ? nil : wanted)
        }
        running = false
    }

    private func pass(only groupIds: Set<String>?) async {
        guard let baseURL, let targets = try? await repository.relayTargets() else { return }
        status = .syncing
        var received = 0
        var sent = 0
        var failure: RelayError?
        for target in targets where groupIds?.contains(target.groupId) ?? true {
            let sync = RelaySync(groupId: target.groupId, groupKey: target.groupKey, store: target.store,
                                 api: makeAPI(baseURL, target.groupKey),
                                 now: { Int64(Date().timeIntervalSince1970 * 1000) })
            do {
                let result = try await sync.run()
                received += result.received
                sent += result.pushedOps
            } catch let error as RelayError {
                failure = error
                break
            } catch {
                failure = .retryLater
                break
            }
        }
        if received > 0 { await repository.refreshAll() }
        if let failure {
            let delay = backoff.failed()
            let until = Date().addingTimeInterval(delay)
            blockedUntil = until
            status = .waiting(until: until, reason: Self.describe(failure))
            scheduleRetry(after: delay)
        } else {
            backoff.succeeded()
            blockedUntil = .distantPast
            status = .done(Date(), received: received, sent: sent)
        }
    }

    private func scheduleRetry(after delay: Double) {
        retry?.cancel()
        retry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            if Task.isCancelled { return }
            self?.trigger(nil)
        }
    }

    static func describe(_ error: RelayError) -> String {
        switch error {
        case .retryLater: "Relay not reachable"
        case .forbidden: "Relay refused the token"
        case .full: "Relay mailbox is full"
        case let .rejected(code): "Relay answered \(code)"
        }
    }
}
