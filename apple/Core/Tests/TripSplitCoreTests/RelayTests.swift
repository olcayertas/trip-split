import Testing
@testable import TripSplitCore

// MARK: - A relay in the process

typealias FakeRelay = InMemoryRelay
typealias FakeRelayClient = InMemoryRelayClient

/// A relay whose mailbox is full: every push gets 507.
struct FullMailbox: RelayAPI {
    let inner: FakeRelayClient
    func pull(after cursor: Int64, limit: Int) async throws -> RelayPage { try await inner.pull(after: cursor, limit: limit) }
    func push(_ blob: [UInt8]) async throws -> Int64 { throw RelayError.full }
}

private func device(_ n: Int, ops count: Int, seed: UInt64) async -> InMemoryOpStore {
    let store = InMemoryOpStore(groupId: groupId, deviceId: deviceId(n))
    var rng = SeededRNG(seed: seed &+ UInt64(n))
    for op in randomOps(device: n, count: count, rng: &rng) { await store.insertLocal(op) }
    return store
}

private func sync(_ store: InMemoryOpStore, with relay: FakeRelay) async throws -> RelayResult {
    try await RelaySync(groupId: groupId, groupKey: groupKey, store: store, api: FakeRelayClient(relay, groupKey: groupKey)).run()
}

/// Puts blobs in the mailbox of the test group, as another phone would.
private func seed(_ relay: FakeRelay, batches: [[JSONValue]], extra: [[UInt8]] = []) async throws {
    let mailbox = RelayCrypto.mailboxId(groupKey: groupKey)
    let token = RelayCrypto.relayToken(groupKey: groupKey)
    let key = RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId)
    for batch in batches {
        await relay.append(raw: try RelayCrypto.seal(ops: batch, batchKey: key, mailboxId: mailbox), mailbox: mailbox, token: token)
    }
    for blob in extra { await relay.append(raw: blob, mailbox: mailbox, token: token) }
}

// MARK: - Names, keys and batches

@Suite("Relay crypto")
struct RelayCryptoTests {
    @Test func namesHaveTheLengthOfTheSpec() {
        let mailbox = RelayCrypto.mailboxId(groupKey: groupKey)
        let token = RelayCrypto.relayToken(groupKey: groupKey)
        #expect(mailbox.count == 32)
        #expect(token.count == 64)
        #expect(Hex.decode(mailbox) != nil && mailbox == mailbox.lowercased())
        #expect(RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId).count == 32)
        // The mailbox must not come from the discovery tag (SPEC §10.4.1).
        #expect(!mailbox.hasPrefix(Hex.encode(SyncCrypto.discoveryTag(groupKey: groupKey))))
        #expect(RelayCrypto.mailboxId(groupKey: groupKey.reversed()) != mailbox)
        #expect(RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId) != RelayCrypto.batchKey(groupKey: groupKey, groupId: "x"))
    }

    @Test func sealAndOpen() throws {
        var rng = SeededRNG(seed: 5)
        let ops = randomOps(device: 1, count: 6, rng: &rng)
        let key = RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId)
        let blob = try RelayCrypto.seal(ops: ops, batchKey: key, mailboxId: "m1")
        #expect(RelayCrypto.open(blob: blob, batchKey: key, mailboxId: "m1") == ops)
        // The AAD is the mailbox id: the same blob does not open in another mailbox.
        #expect(RelayCrypto.open(blob: blob, batchKey: key, mailboxId: "m2") == nil)
        var bad = blob
        bad[bad.count / 2] ^= 1
        #expect(RelayCrypto.open(blob: bad, batchKey: key, mailboxId: "m1") == nil)
        #expect(RelayCrypto.open(blob: [1, 2, 3], batchKey: key, mailboxId: "m1") == nil)
    }

    @Test func fixedNonceGivesTheSameBlob() throws {
        var rng = SeededRNG(seed: 6)
        let ops = randomOps(device: 1, count: 2, rng: &rng)
        let key = RelayCrypto.batchKey(groupKey: groupKey, groupId: groupId)
        let nonce = [UInt8](repeating: 7, count: 12)
        let a = try RelayCrypto.seal(ops: ops, batchKey: key, mailboxId: "m", nonce: nonce)
        #expect(a == (try RelayCrypto.seal(ops: ops, batchKey: key, mailboxId: "m", nonce: nonce)))
        #expect(Array(a.prefix(12)) == nonce)
    }

    @Test func batchesHoldAtMost64Ops() {
        var rng = SeededRNG(seed: 7)
        let ops = randomOps(device: 1, count: 146, rng: &rng)
        let parts = RelayCrypto.batches(of: ops)
        #expect(parts.map(\.count) == [64, 64, ops.count - 128])
        #expect(parts.flatMap { $0 } == ops)
    }

    @Test func batchesHoldAtMost256KiB() {
        let big = JSONValue.object(["pad": .string(String(repeating: "x", count: 100_000))])
        let parts = RelayCrypto.batches(of: [big, big, big, big])
        #expect(parts.map(\.count) == [2, 2])
        #expect(parts.allSatisfy { RelayCrypto.plaintext(ops: $0).count <= RelayCrypto.maxPlaintext })
    }

    @Test func backOffDoublesFromFiveSecondsToFiveMinutes() {
        var b = RelayBackoff()
        #expect((0..<8).map { _ in b.failed() } == [5, 10, 20, 40, 80, 160, 300, 300])
        b.succeeded()
        #expect(b.failed() == 5)
    }
}

// MARK: - Sync

@Suite("Relay sync")
struct RelaySyncTests {
    @Test func twoDevicesConvergeThroughTheRelay() async throws {
        let relay = FakeRelay()
        let a = await device(1, ops: 90, seed: 11)
        let b = await device(2, ops: 70, seed: 11)
        let r1 = try await sync(a, with: relay)
        #expect(r1.pushedOps == 94 && r1.received == 0)
        let r2 = try await sync(b, with: relay)
        #expect(r2.received == 94)
        _ = try await sync(a, with: relay)
        let va = await a.versionVector(), vb = await b.versionVector()
        #expect(va == vb)
        #expect(va[deviceId(1)] == 94 && va[deviceId(2)] == 70)
        let fa = await a.fold(), fb = await b.fold()
        #expect(fa == fb)
    }

    @Test func aNewDeviceSendsOnlyWhatTheRelayLacks() async throws {
        let relay = FakeRelay()
        let a = await device(1, ops: 20, seed: 12)
        _ = try await sync(a, with: relay)
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let first = try await sync(b, with: relay)
        #expect(first.received == 24 && first.pushedOps == 0)
        let again = try await sync(b, with: relay)
        #expect(again.received == 0 && again.pushedOps == 0 && again.pushedBatches == 0)
        #expect(await relay.pushes == 1)
    }

    @Test func cursorAndVectorAreKept() async throws {
        let relay = FakeRelay()
        let a = await device(1, ops: 10, seed: 13)
        _ = try await sync(a, with: relay)
        let state = await a.relayState()
        #expect(state.cursor == 0)
        #expect(state.vector[deviceId(1)] == 14)
        let b = await device(2, ops: 3, seed: 13)
        _ = try await sync(b, with: relay)
        let stateB = await b.relayState()
        #expect(stateB.cursor == 1)
        #expect(stateB.vector[deviceId(1)] == 14 && stateB.vector[deviceId(2)] == 3)
    }

    @Test func negativeControlADroppingRelayDoesNotConverge() async throws {
        let relay = FakeRelay()
        await relay.setDrops(true)
        let a = await device(1, ops: 30, seed: 14)
        let b = await device(2, ops: 30, seed: 14)
        _ = try await sync(a, with: relay)
        _ = try await sync(b, with: relay)
        _ = try await sync(a, with: relay)
        let va = await a.versionVector(), vb = await b.versionVector()
        #expect(va != vb)
        let fa = await a.fold(), fb = await b.fold()
        #expect(fa != fb)
    }

    @Test func manyPagesAreRead() async throws {
        let relay = FakeRelay()
        var rng = SeededRNG(seed: 15)
        let ops = randomOps(device: 1, count: 236, rng: &rng)
        try await seed(relay, batches: ops.map { [$0] })
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let result = try await sync(b, with: relay)
        #expect(result.received == 240)
        #expect(await b.relayState().cursor == 240)
        #expect(await relay.pulls == 3)
        #expect(result.pushedOps == 0)
    }

    @Test func aBadHashSkipsOnlyThatBatch() async throws {
        let relay = FakeRelay()
        var rng = SeededRNG(seed: 16)
        let ops = randomOps(device: 1, count: 6, rng: &rng)
        // Batch 2 has one op with a changed body, so its hash is wrong. Batches 1 and 3 are good.
        var broken = ops[4].objectValue!
        broken["body"] = .object(["name": .string("Other")])
        try await seed(relay, batches: [Array(ops[0..<3]), [ops[3], .object(broken)], [ops[5]]],
                       extra: [[UInt8](repeating: 9, count: 40)])
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let result = try await sync(b, with: relay)
        #expect(result.skippedBatches == 2)
        #expect(result.received == 4)
        let state = await b.relayState()
        #expect(state.cursor == 4)
        // Seq 4 was in the skipped batch, so seq 6 does not count.
        #expect(state.vector[deviceId(1)] == 3)
    }

    @Test func opsOfAnotherGroupAreDropped() async throws {
        let relay = FakeRelay()
        var rng = SeededRNG(seed: 17)
        let own = randomOps(device: 1, count: 1, rng: &rng)
        let other = buildOp(group: "00000000-0000-4000-8000-0000000000ff", author: deviceId(1), seq: 9,
                            hlc: HLC(device: deviceId(1)).stamp, type: "group.rename", body: .object(["name": .string("x")]))
        try await seed(relay, batches: [[own[0], other]])
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        let result = try await sync(b, with: relay)
        #expect(result.received == 1)
        #expect(result.skippedBatches == 0)
    }

    @Test func aWrongTokenIsForbidden() async throws {
        let relay = FakeRelay()
        let a = await device(1, ops: 3, seed: 18)
        _ = try await sync(a, with: relay)
        let intruder = FakeRelayClient(relay: relay, mailbox: RelayCrypto.mailboxId(groupKey: groupKey), token: "00")
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        await #expect(throws: RelayError.forbidden) {
            _ = try await RelaySync(groupId: groupId, groupKey: groupKey, store: b, api: intruder).run()
        }
    }

    @Test func aFailureLeavesTheStateAndTheNextTryWorks() async throws {
        let relay = FakeRelay()
        let a = await device(1, ops: 10, seed: 19)
        _ = try await sync(a, with: relay)
        let b = InMemoryOpStore(groupId: groupId, deviceId: deviceId(2))
        await relay.fail(with: [.retryLater])
        await #expect(throws: RelayError.retryLater) { _ = try await sync(b, with: relay) }
        #expect(await b.relayState() == RelayState())
        let result = try await sync(b, with: relay)
        #expect(result.received == 14)
    }

    @Test func aFullMailboxStopsThePush() async throws {
        let a = await device(1, ops: 5, seed: 20)
        let full = FullMailbox(inner: FakeRelayClient(FakeRelay(), groupKey: groupKey))
        await #expect(throws: RelayError.full) {
            _ = try await RelaySync(groupId: groupId, groupKey: groupKey, store: a, api: full).run()
        }
        #expect(await a.relayState().vector.isEmpty)
    }
}
