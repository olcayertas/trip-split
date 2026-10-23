// A TCP connection as a Link.

import Foundation
import Network
import TripSplitCore

final class NWLink: Link, @unchecked Sendable {
    let connection: NWConnection
    let incoming: AsyncThrowingStream<[UInt8], Error>
    private let continuation: AsyncThrowingStream<[UInt8], Error>.Continuation
    private let queue: DispatchQueue

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
        (incoming, continuation) = AsyncThrowingStream.makeStream()
    }

    /// Starts the connection and waits until it is ready.
    func start(timeout: TimeInterval = 5) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            let once = Once()
            connection.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if once.fire() { done.resume() }
                case let .failed(error):
                    if once.fire() { done.resume(throwing: TransportError.failed(String(describing: error))) }
                    self?.continuation.finish(throwing: error)
                case .cancelled:
                    if once.fire() { done.resume(throwing: TransportError.cancelled) }
                    self?.continuation.finish()
                case let .waiting(error):
                    // No route yet. A connection to a closed port or a missing host waits; give up at the timeout.
                    _ = error
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                if once.fire() {
                    done.resume(throwing: TransportError.timeout)
                    self?.connection.cancel()
                }
            }
        }
        receiveLoop()
    }

    /// For an accepted connection: wait until ready, no timeout error handling beyond cancel.
    func startInbound() async throws { try await start(timeout: 10) }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data, !data.isEmpty { continuation.yield([UInt8](data)) }
            if let error {
                continuation.finish(throwing: error)
                return
            }
            if complete {
                continuation.finish()
                return
            }
            receiveLoop()
        }
    }

    func send(_ bytes: [UInt8]) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            connection.send(content: Data(bytes), completion: .contentProcessed { error in
                if let error { done.resume(throwing: error) } else { done.resume() }
            })
        }
    }

    func close() {
        continuation.finish()
        connection.cancel()
    }
}

/// Fires once. Used to resume a continuation from several callbacks.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var fired = false
    func fire() -> Bool {
        lock.withLock {
            if fired { return false }
            fired = true
            return true
        }
    }
}
