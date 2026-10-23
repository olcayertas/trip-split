// CoreBluetooth transport, dual role (PLAN §9.2, SPEC §10.2). It runs only while the sync screen is open.
//
// All CoreBluetooth objects and all mutable state live on `queue`. This file cannot run in the
// iOS Simulator (no Bluetooth). The logic that can be tested is in Chunking.swift and BLELink.swift.

import CoreBluetooth
import Foundation
import TripSplitCore

public final class BLETransport: NSObject, PeerTransport, @unchecked Sendable {
    public let kind = TransportKind.bluetooth
    public let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private let queue = DispatchQueue(label: "tripsplit.ble")

    nonisolated(unsafe) private static let service = CBUUID(string: BLEUUID.service)
    nonisolated(unsafe) private static let rxUUID = CBUUID(string: BLEUUID.rx)
    nonisolated(unsafe) private static let txUUID = CBUUID(string: BLEUUID.tx)
    nonisolated(unsafe) private static let infoUUID = CBUUID(string: BLEUUID.info)

    /// Largest chunk we accept on `rx` and `tx`. ATT never delivers more than MTU − 3.
    private static let ownLimit = 512
    private static let seenTTL: TimeInterval = 20
    private static let waitAsCentral: TimeInterval = 15

    // Confined to `queue`.
    private var deviceId = ""
    private var tags: [[UInt8]] = []
    private var central: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var running = false

    // Peripheral role.
    private var rxChar: CBMutableCharacteristic?
    private var txChar: CBMutableCharacteristic?
    private var serviceAdded = false
    private var inboundLinks: [UUID: BLELink] = [:]
    private var readyWaiters: [CheckedContinuation<Void, Never>] = []

    // Central role.
    private struct Seen {
        var peripheral: CBPeripheral
        var info: BLEInfo?
        var firstSeen: Date
        var lastSeen: Date
        var probing = false
    }
    private var seen: [UUID: Seen] = [:]
    private var probeQueue: [UUID] = []
    private var probeRunning = false
    private var clients: [UUID: GATTClient] = [:]
    private var expiry: DispatchSourceTimer?

    public override init() {
        (events, continuation) = AsyncStream.makeStream()
        super.init()
    }

    // MARK: Start and stop

    public func start(deviceId: String, tags: [[UInt8]]) async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            queue.async {
                self.stopLocked()
                self.deviceId = deviceId
                self.tags = tags
                self.running = true
                // Creating the managers asks for the Bluetooth permission on first use.
                self.central = CBCentralManager(delegate: self, queue: self.queue, options: nil)
                self.peripheralManager = CBPeripheralManager(delegate: self, queue: self.queue, options: nil)
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + 5, repeating: 5)
                timer.setEventHandler { [weak self] in self?.expireAndPublish() }
                timer.resume()
                self.expiry = timer
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

    private func stopLocked() {
        running = false
        expiry?.cancel()
        expiry = nil
        central?.stopScan()
        for client in clients.values { client.shutdown() }
        clients = [:]
        for s in seen.values { central?.cancelPeripheralConnection(s.peripheral) }
        seen = [:]
        probeQueue = []
        probeRunning = false
        central = nil
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        peripheralManager = nil
        serviceAdded = false
        for link in inboundLinks.values { link.close() }
        inboundLinks = [:]
        for w in readyWaiters { w.resume() }
        readyWaiters = []
        continuation.yield(.peers([]))
    }

    // MARK: Peer list

    private func expireAndPublish() {
        let now = Date()
        seen = seen.filter { now.timeIntervalSince($0.value.lastSeen) < Self.seenTTL || clients[$0.key] != nil }
        publish()
    }

    private func publish() {
        let now = Date()
        var peers: [Peer] = []
        for (id, s) in seen {
            guard let info = s.info, info.deviceId != deviceId else { continue }
            var initiate = BLERole.localConnects(localDevice: deviceId, peer: info)
            // If the peer does not connect to us in 15 s, we connect (SPEC §10.2.3).
            if !initiate, now.timeIntervalSince(s.firstSeen) >= Self.waitAsCentral { initiate = true }
            peers.append(Peer(deviceId: info.deviceId, kind: .bluetooth, tags: info.tags, address: id.uuidString, initiate: initiate))
        }
        continuation.yield(.peers(peers.sorted { $0.deviceId < $1.deviceId }))
    }

    // MARK: Probing (connect, read info, disconnect)

    private func enqueueProbe(_ id: UUID) {
        guard !probeQueue.contains(id), seen[id]?.info == nil else { return }
        probeQueue.append(id)
        runNextProbe()
    }

    private func runNextProbe() {
        guard running, !probeRunning, !probeQueue.isEmpty, let central else { return }
        let id = probeQueue.removeFirst()
        guard let s = seen[id], s.info == nil else { return runNextProbe() }
        probeRunning = true
        let client = GATTClient(peripheral: s.peripheral, central: central, queue: queue)
        clients[id] = client
        Task {
            var info: BLEInfo?
            do {
                let deadline = Date().addingTimeInterval(10)
                try await client.connect(timeout: 10)
                try await client.discover(timeout: max(1, deadline.timeIntervalSinceNow))
                let data = try await client.readInfo(timeout: max(1, deadline.timeIntervalSinceNow))
                info = BLEInfo.parse(data)
            } catch {}
            client.shutdown()
            queue.async {
                self.clients[id] = nil
                self.probeRunning = false
                if var entry = self.seen[id] {
                    entry.info = info
                    if info == nil { entry.lastSeen = .distantPast }  // try again on the next advertisement
                    self.seen[id] = entry
                }
                self.publish()
                self.runNextProbe()
            }
        }
    }

    // MARK: Connecting as central

    public func connect(to peer: Peer) async throws -> any Link {
        guard let id = UUID(uuidString: peer.address) else { throw TransportError.failed("bad address") }
        let prepared: (CBPeripheral, CBCentralManager)? = await withCheckedContinuation { c in
            queue.async {
                if let s = self.seen[id], let central = self.central, self.clients[id] == nil {
                    c.resume(returning: (s.peripheral, central))
                } else {
                    c.resume(returning: nil)
                }
            }
        }
        guard let (peripheral, central) = prepared else { throw TransportError.failed("peer is gone") }
        let client = GATTClient(peripheral: peripheral, central: central, queue: queue)
        queue.async { self.clients[id] = client }
        do {
            let deadline = Date().addingTimeInterval(10)
            try await client.connect(timeout: 10)
            // Steps 2 to 5 of SPEC §10.2.3 share one limit of 10 s.
            let setup = Date().addingTimeInterval(10)
            try await client.discover(timeout: max(1, setup.timeIntervalSinceNow))
            guard let info = BLEInfo.parse(try await client.readInfo(timeout: max(1, setup.timeIntervalSinceNow))),
                  info.deviceId == peer.deviceId, info.protocolVersion >= 1
            else { throw TransportError.failed("bad info") }
            try await client.enableNotifications(timeout: max(1, setup.timeIntervalSinceNow))
            _ = deadline
            let writeLimit = peripheral.maximumWriteValueLength(for: .withResponse)
            // The write limit can still be 20 right after connect. The link reads it again before each message.
            let box = PeripheralBox(peripheral)
            let link = BLELink(writer: ClientWriter(client: client), receiveLimit: Self.ownLimit,
                               initialChunkSize: min(writeLimit, info.maxChunk), isPeripheral: false,
                               limitProvider: { box.peripheral.maximumWriteValueLength(for: .withResponse) },
                               peerCap: info.maxChunk,
                               onClose: { [weak self] in self?.dropClient(id, client) })
            client.onChunk = { [weak link] chunk in link?.receive(chunk) }
            client.onDisconnect = { [weak link] in link?.disconnected() }
            return link
        } catch {
            client.shutdown()
            queue.async { self.clients[id] = nil }
            throw error
        }
    }

    private struct PeripheralBox: @unchecked Sendable {
        let peripheral: CBPeripheral
        init(_ peripheral: CBPeripheral) { self.peripheral = peripheral }
    }

    private func dropClient(_ id: UUID, _ client: GATTClient) {
        queue.async {
            client.shutdown()
            if self.clients[id] === client { self.clients[id] = nil }
        }
    }

    private struct ClientWriter: BLEChunkWriter, @unchecked Sendable {
        let client: GATTClient
        func write(_ chunk: [UInt8]) async throws { try await client.write(chunk, timeout: 10) }
    }

    // MARK: Peripheral writer

    private struct NotifyWriter: BLEChunkWriter, @unchecked Sendable {
        let transport: BLETransport
        let central: CBCentral

        func write(_ chunk: [UInt8]) async throws {
            while true {
                let accepted: Bool = await withCheckedContinuation { c in
                    transport.queue.async {
                        guard let pm = transport.peripheralManager, let tx = transport.txChar else { return c.resume(returning: true) }
                        c.resume(returning: pm.updateValue(Data(chunk), for: tx, onSubscribedCentrals: [central]))
                    }
                }
                if accepted { return }
                await transport.waitForReady()
            }
        }
    }

    private func waitForReady() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            queue.async { self.readyWaiters.append(c) }
        }
    }

    private var infoData: Data {
        BLEInfo(deviceId: deviceId, maxChunk: Self.ownLimit, roles: ["central", "peripheral"],
                tags: tags.map(tagText)).data
    }
}

// MARK: - Central manager

extension BLETransport: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            central.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        case .unauthorized:
            continuation.yield(.problem("Bluetooth permission denied"))
        case .poweredOff:
            continuation.yield(.problem("Bluetooth is off"))
        default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let now = Date()
        if var s = seen[id] {
            s.lastSeen = now
            s.peripheral = peripheral
            seen[id] = s
        } else {
            seen[id] = Seen(peripheral: peripheral, info: nil, firstSeen: now, lastSeen: now)
        }
        if seen[id]?.info == nil { enqueueProbe(id) }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        clients[peripheral.identifier]?.didConnect()
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        clients[peripheral.identifier]?.didFail(error ?? TransportError.failed("connect failed"))
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        clients[peripheral.identifier]?.didDisconnect(error)
    }
}

// MARK: - Peripheral manager

extension BLETransport: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            guard !serviceAdded else { return }
            serviceAdded = true
            let rx = CBMutableCharacteristic(type: Self.rxUUID, properties: [.write], value: nil, permissions: [.writeable])
            let tx = CBMutableCharacteristic(type: Self.txUUID, properties: [.notify], value: nil, permissions: [.readable])
            let info = CBMutableCharacteristic(type: Self.infoUUID, properties: [.read], value: infoData, permissions: [.readable])
            let service = CBMutableService(type: Self.service, primary: true)
            service.characteristics = [rx, tx, info]
            rxChar = rx
            txChar = tx
            peripheral.add(service)
        case .unauthorized:
            continuation.yield(.problem("Bluetooth permission denied"))
        case .poweredOff:
            continuation.yield(.problem("Bluetooth is off"))
        default:
            break
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            continuation.yield(.problem("Bluetooth service failed"))
            return
        }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.service]])
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.txUUID, inboundLinks[central.identifier] == nil else { return }
        let id = central.identifier
        let link = BLELink(writer: NotifyWriter(transport: self, central: central), receiveLimit: Self.ownLimit,
                           initialChunkSize: BLEChunk.minChunkSize, isPeripheral: true,
                           txLimit: central.maximumUpdateValueLength,
                           onClose: { [weak self] in self?.queue.async { self?.inboundLinks[id] = nil } })
        inboundLinks[id] = link
        continuation.yield(.inbound(link, kind: .bluetooth))
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.txUUID, let link = inboundLinks[central.identifier] else { return }
        link.disconnected()
        inboundLinks[central.identifier] = nil
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == Self.rxUUID {
            if let value = request.value, let link = inboundLinks[request.central.identifier] {
                link.receive([UInt8](value))
            }
        }
        if let first = requests.first { peripheral.respond(to: first, withResult: .success) }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        let waiters = readyWaiters
        readyWaiters = []
        for w in waiters { w.resume() }
    }
}

// MARK: - GATT client of one connection

/// Wraps the delegate callbacks of a `CBPeripheral` in async calls. Each call has one pending
/// continuation; a timeout or a disconnect resumes it with an error.
final class GATTClient: NSObject, CBPeripheralDelegate, @unchecked Sendable {
    private enum Step: Hashable { case connect, services, characteristics, info, notify, write }

    private let peripheral: CBPeripheral
    private let central: CBCentralManager
    private let queue: DispatchQueue
    private var pending: [Step: (token: Int, continuation: CheckedContinuation<Data, Error>)] = [:]
    private var nextToken = 0
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var info: CBCharacteristic?
    private var dead = false

    var onChunk: (@Sendable ([UInt8]) -> Void)?
    var onDisconnect: (@Sendable () -> Void)?

    init(peripheral: CBPeripheral, central: CBCentralManager, queue: DispatchQueue) {
        self.peripheral = peripheral
        self.central = central
        self.queue = queue
        super.init()
        peripheral.delegate = self
    }

    private func run(_ step: Step, timeout: TimeInterval, _ start: @escaping @Sendable () -> Void) async throws -> Data {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Data, Error>) in
            queue.async {
                guard !self.dead else { return c.resume(throwing: TransportError.cancelled) }
                self.nextToken += 1
                let token = self.nextToken
                self.pending[step] = (token, c)
                start()
                self.queue.asyncAfter(deadline: .now() + timeout) {
                    if let p = self.pending[step], p.token == token {
                        self.pending[step] = nil
                        p.continuation.resume(throwing: TransportError.timeout)
                    }
                }
            }
        }
    }

    private func complete(_ step: Step, _ result: Result<Data, Error>) {
        guard let p = pending.removeValue(forKey: step) else { return }
        p.continuation.resume(with: result)
    }

    func shutdown() {
        queue.async {
            guard !self.dead else { return }
            self.dead = true
            for (_, p) in self.pending { p.continuation.resume(throwing: TransportError.cancelled) }
            self.pending = [:]
            self.central.cancelPeripheralConnection(self.peripheral)
        }
    }

    // Async API.

    func connect(timeout: TimeInterval) async throws {
        _ = try await run(.connect, timeout: timeout) { self.central.connect(self.peripheral, options: nil) }
    }

    func discover(timeout: TimeInterval) async throws {
        _ = try await run(.services, timeout: timeout) { self.peripheral.discoverServices([CBUUID(string: BLEUUID.service)]) }
    }

    func readInfo(timeout: TimeInterval) async throws -> Data {
        try await run(.info, timeout: timeout) {
            guard let info = self.info else { return self.complete(.info, .failure(TransportError.failed("no info"))) }
            self.peripheral.readValue(for: info)
        }
    }

    func enableNotifications(timeout: TimeInterval) async throws {
        _ = try await run(.notify, timeout: timeout) {
            guard let tx = self.tx else { return self.complete(.notify, .failure(TransportError.failed("no tx"))) }
            self.peripheral.setNotifyValue(true, for: tx)
        }
    }

    func write(_ chunk: [UInt8], timeout: TimeInterval) async throws {
        _ = try await run(.write, timeout: timeout) {
            guard let rx = self.rx else { return self.complete(.write, .failure(TransportError.failed("no rx"))) }
            self.peripheral.writeValue(Data(chunk), for: rx, type: .withResponse)
        }
    }

    // Callbacks from the transport and from CoreBluetooth (all on `queue`).

    func didConnect() { complete(.connect, .success(Data())) }
    func didFail(_ error: Error) { complete(.connect, .failure(error)) }

    func didDisconnect(_ error: Error?) {
        dead = true
        for (_, p) in pending { p.continuation.resume(throwing: error ?? TransportError.cancelled) }
        pending = [:]
        onDisconnect?()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { return complete(.services, .failure(error)) }
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: BLEUUID.service) }) else {
            return complete(.services, .failure(TransportError.failed("no service")))
        }
        peripheral.discoverCharacteristics(
            [CBUUID(string: BLEUUID.rx), CBUUID(string: BLEUUID.tx), CBUUID(string: BLEUUID.info)], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { return complete(.services, .failure(error)) }
        for c in service.characteristics ?? [] {
            switch c.uuid.uuidString.lowercased() {
            case BLEUUID.rx: rx = c
            case BLEUUID.tx: tx = c
            case BLEUUID.info: info = c
            default: break
            }
        }
        if rx != nil, tx != nil, info != nil { complete(.services, .success(Data())) }
        else { complete(.services, .failure(TransportError.failed("missing characteristics"))) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == CBUUID(string: BLEUUID.info) {
            if let error { complete(.info, .failure(error)) } else { complete(.info, .success(characteristic.value ?? Data())) }
        } else if characteristic.uuid == CBUUID(string: BLEUUID.tx), error == nil, let value = characteristic.value {
            onChunk?([UInt8](value))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error { complete(.notify, .failure(error)) } else { complete(.notify, .success(Data())) }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { complete(.write, .failure(error)) } else { complete(.write, .success(Data())) }
    }
}
