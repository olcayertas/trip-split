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
                loopbackOnly: Bool = false) {
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
                self.scheduleProbe()
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

    private func startListeners(tags: [[UInt8]]) {
        // Listener with the fixed port. It can fail when another process holds the port; that is not fatal.
        let parameters = NWParameters.tcp
        if loopbackOnly {
            parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: port) ?? .any)
        }
        let fixed = loopbackOnly ? try? NWListener(using: parameters) : NWEndpoint.Port(rawValue: port).flatMap { try? NWListener(using: .tcp, on: $0) }
        if let l = fixed { attach(l, announceTags: nil) }
        if advertise, let l = try? NWListener(using: .tcp) {
            attach(l, announceTags: tags)
        }
    }

    private func attach(_ listener: NWListener, announceTags tags: [[UInt8]]?) {
        if let tags {
            var txt = NWTXTRecord()
            txt["d"] = deviceId
            txt["t"] = Advert.encodeTags(tags)
            listener.service = NWListener.Service(name: String(deviceId.prefix(8)), type: Self.serviceType, txtRecord: txt)
        }
        let isFixed = tags == nil
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
                if isFixed {
                    boundPort = listener.port?.rawValue
                    for w in portWaiters { w.resume(returning: boundPort) }
                    portWaiters = []
                }
            case let .failed(error):
                if !isFixed { continuation.yield(.problem(String(describing: error))) }
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

    /// When Bonjour finds nobody after `probeDelay`, offer the default gateway on the fixed port.
    private func scheduleProbe() {
        let work = DispatchWorkItem { [weak self] in
            guard let self, found.isEmpty, let gateway = Gateway.ipv4() else { return }
            probe = Peer(deviceId: "", kind: .wifi, tags: [], address: gateway + ":" + String(Self.fixedPort), isProbe: true)
            publish()
        }
        probeWork = work
        queue.asyncAfter(deadline: .now() + probeDelay, execute: work)
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
        let link = NWLink(connection: NWConnection(to: endpoint, using: .tcp), queue: queue)
        do {
            try await link.start()
        } catch {
            link.close()
            throw error
        }
        return link
    }
}
