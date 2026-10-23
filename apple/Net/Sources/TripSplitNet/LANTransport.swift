// Wi-Fi LAN transport (PLAN §9.1): Bonjour `_tripsplit._tcp` plus the fixed port 47831.

import Foundation
import Network
import TripSplitCore

public final class LANTransport: PeerTransport, @unchecked Sendable {
    public static let serviceType = "_tripsplit._tcp"
    public static let fixedPort: UInt16 = 47831

    public let kind = TransportKind.wifi
    public let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private let queue = DispatchQueue(label: "tripsplit.lan")

    private let port: UInt16
    private let advertise: Bool
    private let probeDelay: TimeInterval
    private let probeInterval: TimeInterval
    private let loopbackOnly: Bool

    // Everything below is touched only on `queue`.
    private var deviceId = ""
    private var listeners: [NWListener] = []
    private var browser: NWBrowser?
    private var found: [String: (peer: Peer, endpoint: NWEndpoint)] = [:]
    private var probe: Peer?
    private var probeWork: DispatchWorkItem?
    private var boundPort: UInt16?
    private var portWaiters: [CheckedContinuation<UInt16?, Never>] = []

    /// `port`: 47831 in the app, 0 in tests. `advertise`: false in tests that do not need Bonjour.
    public init(port: UInt16 = LANTransport.fixedPort, advertise: Bool = true, probeDelay: TimeInterval = 5,
                probeInterval: TimeInterval = 15, loopbackOnly: Bool = false) {
        self.probeInterval = probeInterval
        self.loopbackOnly = loopbackOnly
        self.port = port
        self.advertise = advertise
        self.probeDelay = probeDelay
        (events, continuation) = AsyncStream.makeStream()
    }

    // MARK: Start and stop

    public func start(deviceId: String, tags: [[UInt8]]) async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            queue.async {
                self.stopLocked()
                self.deviceId = deviceId
                self.startListeners(tags: tags)
                if self.advertise { self.startBrowser() }
                self.scheduleProbe(after: self.probeDelay)
                done.resume()
            }
        }
    }

    public func stop() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            queue.async {
                self.stopLocked()
                done.resume()
            }
        }
    }

    /// The port the fixed listener is bound to (useful when it was started with port 0).
    public func boundPortValue() async -> UInt16? {
        await withCheckedContinuation { (c: CheckedContinuation<UInt16?, Never>) in
            queue.async {
                if let p = self.boundPort { c.resume(returning: p) } else { self.portWaiters.append(c) }
            }
        }
    }

    private func stopLocked() {
        listeners.forEach { $0.cancel() }
        listeners = []
        browser?.cancel()
        browser = nil
        probeWork?.cancel()
        probeWork = nil
        found = [:]
        probe = nil
        boundPort = nil
        for w in portWaiters { w.resume(returning: nil) }
        portWaiters = []
        continuation.yield(.peers([]))
    }

    // MARK: Listening

    private static func parameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        return NWParameters(tls: nil, tcp: tcp)
    }

    /// One listener: port 47831 first, any free port when that one is busy (SPEC §10.1.1). The Bonjour
    /// service uses the port that was bound.
    private func startListeners(tags: [[UInt8]]) {
        startListener(tags: tags, wantedPort: port)
    }

    private func startListener(tags: [[UInt8]], wantedPort: UInt16) {
        let parameters = Self.parameters()
        if loopbackOnly {
            parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: wantedPort) ?? .any)
        }
        let listener: NWListener
        do {
            if !loopbackOnly, wantedPort != 0, let p = NWEndpoint.Port(rawValue: wantedPort) {
                listener = try NWListener(using: parameters, on: p)
            } else {
                listener = try NWListener(using: parameters)
            }
        } catch {
            if wantedPort != 0 { startListener(tags: tags, wantedPort: 0) }
            return
        }
        if advertise {
            var txt = NWTXTRecord()
            txt["v"] = "1"
            txt["d"] = deviceId
            txt["t"] = Advert.encodeTags(tags)
            listener.service = NWListener.Service(name: "TripSplit-" + String(deviceId.prefix(8)), type: Self.serviceType, txtRecord: txt)
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            let link = NWLink(connection: connection, queue: queue)
            Task {
                do {
                    try await link.startInbound()
                    self.continuation.yield(.inbound(link, kind: .wifi))
                } catch {
                    link.close()
                }
            }
        }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                boundPort = listener.port?.rawValue
                for w in portWaiters { w.resume(returning: boundPort) }
                portWaiters = []
            case let .failed(error):
                listener.cancel()
                listeners.removeAll { $0 === listener }
                if wantedPort != 0 { startListener(tags: tags, wantedPort: 0) } else { continuation.yield(.problem(String(describing: error))) }
            default:
                break
            }
        }
        listener.start(queue: queue)
        listeners.append(listener)
    }

    // MARK: Browsing

    private func startBrowser() {
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            var next: [String: (peer: Peer, endpoint: NWEndpoint)] = [:]
            for r in results {
                guard case let .bonjour(txt) = r.metadata, let d = txt["d"], d != deviceId, isId(d) else { continue }
                let tags = Advert.decodeTags(txt["t"] ?? "")
                let peer = Peer(deviceId: d, kind: .wifi, tags: tags, address: String(describing: r.endpoint))
                next[d] = (peer, r.endpoint)
            }
            found = next
            publish()
        }
        b.stateUpdateHandler = { [weak self] state in
            if case let .failed(error) = state { self?.continuation.yield(.problem(String(describing: error))) }
            // A denied local network permission shows as a waiting state with a policy error.
            if case let .waiting(error) = state { self?.continuation.yield(.problem(String(describing: error))) }
        }
        b.start(queue: queue)
        browser = b
    }

    private func isId(_ s: String) -> Bool { s.utf8.count == 36 }

    private func publish() {
        var list = found.values.map(\.peer)
        if let probe, found.isEmpty { list.append(probe) }
        continuation.yield(.peers(list))
    }

    /// When Bonjour finds nobody after `probeDelay`, offer the default gateway on the fixed port. The offer
    /// repeats every `probeInterval` while no peer is known (SPEC §10.1.2 step 4).
    private func scheduleProbe(after delay: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if found.isEmpty, let gateway = Gateway.ipv4() {
                probe = Peer(deviceId: "", kind: .wifi, tags: [], address: gateway + ":" + String(Self.fixedPort), isProbe: true)
                publish()
            }
            if probeInterval > 0 { scheduleProbe(after: probeInterval) }
        }
        probeWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: Connecting

    public func connect(to peer: Peer) async throws -> any Link {
        if let entry = await endpoint(for: peer) {
            return try await open(entry)
        }
        let parts = peer.address.split(separator: ":")
        guard peer.isProbe, parts.count == 2, let p = UInt16(parts[1]) else { throw TransportError.failed("unknown peer") }
        return try await connect(host: String(parts[0]), port: p)
    }

    public func connect(host: String, port: UInt16) async throws -> any Link {
        guard let p = NWEndpoint.Port(rawValue: port) else { throw TransportError.failed("bad port") }
        return try await open(.hostPort(host: NWEndpoint.Host(host), port: p))
    }

    private func endpoint(for peer: Peer) async -> NWEndpoint? {
        await withCheckedContinuation { (c: CheckedContinuation<NWEndpoint?, Never>) in
            queue.async { c.resume(returning: self.found[peer.deviceId]?.endpoint) }
        }
    }

    private func open(_ endpoint: NWEndpoint) async throws -> any Link {
        let link = NWLink(connection: NWConnection(to: endpoint, using: Self.parameters()), queue: queue)
        do {
            try await link.start()
        } catch {
            link.close()
            throw error
        }
        return link
    }
}
