// A Bluetooth connection as a Link: messages in, chunks out (SPEC §10.2.4 to §10.2.7).

import Foundation
import TripSplitCore

/// Sends one chunk and returns when the platform accepted it (a write response, or room for the next
/// notification). The platform layer implements it.
public protocol BLEChunkWriter: Sendable {
    func write(_ chunk: [UInt8]) async throws
}

public enum BLELinkError: Error, Equatable {
    case chunk(BLEChunkError)
    case unfinishedMessage
    case closed
}

public final class BLELink: Link, @unchecked Sendable {
    public let incoming: AsyncThrowingStream<[UInt8], Error>
    private let continuation: AsyncThrowingStream<[UInt8], Error>.Continuation
    private let writer: any BLEChunkWriter
    private let isPeripheral: Bool
    private let ownTxLimit: Int
    private let unfinishedTimeout: TimeInterval
    private let onClose: @Sendable () -> Void
    private let limitProvider: (@Sendable () -> Int)?
    private let peerCap: Int

    private let lock = NSLock()
    private var reassembler: BLEReassembler
    private var sendSeq: UInt16 = 0
    private var sendChunkSize: Int
    private var closed = false
    private var generation = 0

    /// `receiveLimit`: the largest chunk we accept. `initialChunkSize`: the chunk size we send with.
    /// `limitProvider` (central): the current platform write limit, read before each message; `peerCap`: `info.maxChunk`.
    /// A peripheral starts with 20 and moves up when it has read the hello of the central; `txLimit` is
    /// then the largest notification the platform allows for that central.
    public init(writer: any BLEChunkWriter, receiveLimit: Int, initialChunkSize: Int, isPeripheral: Bool,
                txLimit: Int = 512, unfinishedTimeout: TimeInterval = 15,
                limitProvider: (@Sendable () -> Int)? = nil, peerCap: Int = BLEChunk.maxChunkSize,
                onClose: @escaping @Sendable () -> Void = {}) {
        self.limitProvider = limitProvider
        self.peerCap = BLEChunk.clamp(peerCap)
        self.writer = writer
        self.isPeripheral = isPeripheral
        ownTxLimit = BLEChunk.clamp(txLimit)
        self.unfinishedTimeout = unfinishedTimeout
        self.onClose = onClose
        reassembler = BLEReassembler(receiveLimit: receiveLimit)
        sendChunkSize = BLEChunk.clamp(initialChunkSize)
        (incoming, continuation) = AsyncThrowingStream.makeStream()
    }

    public var currentSendChunkSize: Int { lock.withLock { sendChunkSize } }

    // MARK: Link

    public func send(_ bytes: [UInt8]) async throws {
        // iOS finishes its MTU exchange some time after the connection starts. Read the platform limit
        // again before each message and raise the chunk size when it grew. Never lower it.
        if let limitProvider {
            let wanted = BLEChunk.clamp(min(limitProvider(), peerCap))
            lock.withLock { if wanted > sendChunkSize { sendChunkSize = wanted } }
        }
        let chunks: [[UInt8]] = try lock.withLock {
            guard !closed else { throw BLELinkError.closed }
            return BLEChunk.split(bytes, chunkSize: sendChunkSize, seq: &sendSeq)
        }
        for chunk in chunks {
            try await writer.write(chunk)
            if lock.withLock({ closed }) { throw BLELinkError.closed }
        }
    }

    public func close() {
        let abort: [UInt8]? = lock.withLock {
            guard !closed else { return nil }
            closed = true
            return BLEChunk.abortChunk(seq: &sendSeq)
        }
        guard let abort else { return }
        continuation.finish()
        let writer = writer
        // Best effort: a peripheral cannot drop a central, so it tells the central to leave.
        Task { try? await writer.write(abort) }
        onClose()
    }

    public func peerAnnounced(maxChunk: Int64) {
        guard isPeripheral, maxChunk > 0 else { return }
        lock.withLock { sendChunkSize = BLEChunk.clamp(min(ownTxLimit, Int(min(maxChunk, 512)))) }
    }

    // MARK: From the platform

    /// A chunk arrived (a write on `rx`, or a notification on `tx`). Calls must come in order.
    public func receive(_ chunk: [UInt8]) {
        let outcome: Result<[UInt8]?, BLEChunkError>? = lock.withLock {
            guard !closed else { return nil }
            do {
                let message = try reassembler.receive(chunk)
                if message == nil, chunk[0] & BLEChunk.first != 0 {
                    generation += 1
                    scheduleUnfinishedCheck(generation)
                }
                return .success(message)
            } catch let e as BLEChunkError {
                return .failure(e)
            } catch {
                return .failure(.badFlags)
            }
        }
        switch outcome {
        case let .success(message?): continuation.yield(message)
        case .success(nil), nil: break
        case let .failure(error): fail(BLELinkError.chunk(error))
        }
    }

    /// The platform lost the connection.
    public func disconnected() {
        let already: Bool = lock.withLock {
            let was = closed
            closed = true
            return was
        }
        guard !already else { return }
        continuation.finish()
        onClose()
    }

    private func fail(_ error: BLELinkError) {
        let already: Bool = lock.withLock {
            let was = closed
            closed = true
            return was
        }
        guard !already else { return }
        continuation.finish(throwing: error)
        onClose()
    }

    /// An open message that does not end in time closes the session. Call with the lock held.
    private func scheduleUnfinishedCheck(_ g: Int) {
        let timeout = unfinishedTimeout
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard let self else { return }
            let stuck = lock.withLock { generation == g && reassembler.hasOpenMessage && !closed }
            if stuck { fail(.unfinishedMessage) }
        }
    }
}
