// Runs sessions: picks peers from the transports, starts initiator sessions, answers inbound links.
// It follows PLAN §8.4 and §9.3: LAN before Bluetooth, 30 s pause per peer and group, foreground only.

import Foundation
import Observation
import TripSplitCore
import TripSplitStore

@MainActor
@Observable
public final class SyncCoordinator {
    public enum Status: Equatable, Sendable {
        case idle
        case syncing(sent: Int, received: Int)
        case done(Date, sent: Int, received: Int)
        case failed(Date, String)
    }

    public struct Row: Identifiable, Equatable, Sendable {
        public let id: String
        public let deviceId: String
        public let kind: TransportKind
        public let groupIds: [String]
        public let status: Status
    }

    public private(set) var rows: [Row] = []
    public private(set) var isRunning = false
    public private(set) var lastResult: String?
    public private(set) var problem: String?
    public private(set) var clockSkewWith: String?
    public private(set) var forkCount = 0

    @ObservationIgnored private let repository: GroupRepository
    @ObservationIgnored private let transports: [any PeerTransport]
    @ObservationIgnored private let cooldown: TimeInterval
    @ObservationIgnored private let registry = SessionRegistry()
    @ObservationIgnored private var groups: [SyncGroup] = []
    @ObservationIgnored private var latest: [TransportKind: [Peer]] = [:]
    @ObservationIgnored private var statuses: [String: Status] = [:]
    @ObservationIgnored private var running: Set<String> = []
    @ObservationIgnored private var lastEnd: [String: (date: Date, opCount: Int64)] = [:]
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    public init(repository: GroupRepository, transports: [any PeerTransport], cooldown: TimeInterval = 30) {
        self.repository = repository
        self.transports = transports
        self.cooldown = cooldown
    }

    // MARK: Start and stop

    public func start() async {
        guard !isRunning else { return }
        isRunning = true
        problem = nil
        groups = (try? await repository.syncGroups()) ?? []
        let tags = groups.map(\.tag)
        for transport in transports {
            let events = transport.events
            tasks.append(Task { [weak self] in
                for await event in events { await self?.handle(event, from: transport) }
            })
            await transport.start(deviceId: repository.deviceId, tags: tags)
        }
    }

    public func stop() async {
        guard isRunning else { return }
        isRunning = false
        tasks.forEach { $0.cancel() }
        tasks = []
        for transport in transports { await transport.stop() }
        latest = [:]
        running = []
        rebuildRows()
    }

    // MARK: Events

    private func handle(_ event: TransportEvent, from transport: any PeerTransport) async {
        guard isRunning else { return }
        switch event {
        case let .peers(list):
            latest[transport.kind] = list
            rebuildRows()
            await evaluate()
        case let .inbound(link, kind):
            tasks.append(Task { [weak self] in await self?.answer(link, kind: kind) })
        case let .problem(text):
            problem = text
        }
    }

    private func matchingGroups(_ peer: Peer) -> [SyncGroup] {
        guard !peer.isProbe else { return groups }
        return groups.filter { peer.tags.contains(tagText($0.tag)) }
    }

    /// One peer per device: the Wi-Fi one when both transports see it (PLAN §9.2).
    private func candidates() -> [Peer] {
        var byDevice: [String: Peer] = [:]
        var probes: [Peer] = []
        for kind in [TransportKind.wifi, .bluetooth] {
            for peer in latest[kind] ?? [] {
                if peer.isProbe { probes.append(peer); continue }
                if byDevice[peer.deviceId] == nil { byDevice[peer.deviceId] = peer }
            }
        }
        return byDevice.values.sorted { $0.deviceId < $1.deviceId } + probes
    }

    private func evaluate() async {
        for peer in candidates() {
            for group in matchingGroups(peer) {
                let key = (peer.isProbe ? peer.address : peer.deviceId) + "|" + group.groupId
                guard !running.contains(key) else { continue }
                let count = (try? await group.store.versionVector().values.reduce(0, +)) ?? 0
                if let last = lastEnd[key], Date().timeIntervalSince(last.date) < cooldown, last.opCount == count { continue }
                running.insert(key)
                tasks.append(Task { [weak self] in await self?.initiate(peer, group: group, key: key) })
            }
        }
    }

    // MARK: Sessions

    private func transport(for kind: TransportKind) -> (any PeerTransport)? { transports.first { $0.kind == kind } }

    /// Remembers which row a session reports to. An inbound session learns it from the handshake.
    private final class RowBox: @unchecked Sendable {
        private let lock = NSLock()
        private var value: String?
        init(_ value: String?) { self.value = value }
        var id: String? {
            get { lock.withLock { value } }
            set { lock.withLock { value = newValue } }
        }
    }

    private func options(kind: TransportKind, row: RowBox) -> SessionOptions {
        SessionOptions(deviceId: repository.deviceId, admission: registry, onEvent: { [weak self] event in
            if case let .handshake(peerDevice, _, _) = event, row.id == nil { row.id = kind.rawValue + "|" + peerDevice }
            let id = row.id
            Task { @MainActor in self?.note(event, row: id) }
        })
    }

    private func note(_ event: SessionEvent, row: String?) {
        switch event {
        case let .progress(sent, received):
            if let id = row { statuses[id] = .syncing(sent: sent, received: received); rebuildRows() }
        case let .clockSkew(peer):
            clockSkewWith = peer
        case .handshake:
            break
        }
    }

    private func initiate(_ peer: Peer, group: SyncGroup, key: String) async {
        let rowId = peer.id
        statuses[rowId] = .syncing(sent: 0, received: 0)
        rebuildRows()
        defer { running.remove(key) }
        guard let transport = transport(for: peer.kind) else { return }
        do {
            let link = try await transport.connect(to: peer)
            let session = SyncSession(role: .initiator, link: link, groups: [group],
                                       options: options(kind: peer.kind, row: RowBox(rowId)))
            let result = try await session.run()
            await finished(result, rowId: rowId, key: key, group: group)
        } catch {
            // A failed probe is tried again with the next probe offer (every 15 s); no pause applies.
            if !peer.isProbe { lastEnd[key] = (Date(), (try? await group.store.versionVector().values.reduce(0, +)) ?? 0) }
            // A probe that finds nobody is normal; do not show it as an error.
            statuses[rowId] = peer.isProbe ? .idle : .failed(Date(), Self.describe(error))
            rebuildRows()
        }
    }

    private func answer(_ link: any Link, kind: TransportKind) async {
        let session = SyncSession(role: .responder, link: link, groups: groups, options: options(kind: kind, row: RowBox(nil)))
        do {
            let result = try await session.run()
            let key = result.peerDeviceId + "|" + result.groupId
            if let group = groups.first(where: { $0.groupId == result.groupId }) {
                await finished(result, rowId: kind.rawValue + "|" + result.peerDeviceId, key: key, group: group)
            }
        } catch {
            // The peer may be a stranger or a duplicate; the session already closed the link.
        }
    }

    private func finished(_ result: SessionResult, rowId: String, key: String, group: SyncGroup) async {
        let count = (try? await group.store.versionVector().values.reduce(0, +)) ?? 0
        lastEnd[key] = (Date(), count)
        statuses[rowId] = .done(Date(), sent: result.sentOps, received: result.receivedOps)
        forkCount += result.forks.count
        if result.clockSkew { clockSkewWith = result.peerDeviceId }
        lastResult = "\(result.peerDeviceId.prefix(8)): +\(result.receivedOps) / −\(result.sentOps)"
        await repository.refreshAll()
        rebuildRows()
    }

    private static func describe(_ error: Error) -> String {
        if let e = error as? SessionError { return String(describing: e) }
        return String(describing: error)
    }

    private func rebuildRows() {
        var out: [Row] = []
        for peer in candidates() where !peer.isProbe {
            out.append(Row(id: peer.id, deviceId: peer.deviceId, kind: peer.kind,
                           groupIds: matchingGroups(peer).map(\.groupId), status: statuses[peer.id] ?? .idle))
        }
        // Peers that connected to us and are not in range lists (inbound only).
        for (id, status) in statuses where !out.contains(where: { $0.id == id }) && !id.contains("probe") {
            let parts = id.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2, let kind = TransportKind(rawValue: parts[0]) {
                out.append(Row(id: id, deviceId: parts[1], kind: kind, groupIds: [], status: status))
            }
        }
        rows = out
    }
}
