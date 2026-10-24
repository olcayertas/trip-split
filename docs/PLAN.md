# TripSplit — implementation plan (v1)

Status: reviewed (Fable 5.1, 2026-10-23); all 15 findings applied. Date: 2026-10-23.

## 1. Goal

TripSplit is a mobile app for a group on holiday. The members record shared expenses. The app shows
who owes whom and the smallest set of payments that settles the group.

Hard requirements from the owner:

1. The app runs on iOS and Android.
2. The app works fully offline. There is no server that holds plain data. (Owner change, 2026-10-23: an
   optional internet relay that stores only encrypted batches, §9.4.)
3. Devices sync with each other over the same Wi-Fi network (or a phone hotspot) and over Bluetooth.
4. Every member can add entries.
5. A user can create a group. A user can join a group later and receive its full history.
6. iOS ships through the owner's Apple Developer account (team `SNZ29V4PJZ`) with Fastlane.
7. Android ships as a signed APK that the owner shares directly.

Owner decisions (2026-10-23): native Swift (iOS) and native Kotlin (Android); Wi-Fi LAN and BLE both
in release 1; work tracked in GitHub issues; public repo `olcayertas/trip-split`; app name TripSplit;
any member can edit or delete any expense; multi-currency with a manual rate; split modes equal,
exact and weights.

## 2. Non-goals for v1

- No cloud backend that can read data, no accounts, no login. The relay of §9.4 stores only ciphertext.
- No live exchange-rate download. The user types a rate when the currency differs from the group currency.
- No receipt photos (they are large for BLE; see §16).
- No iPad or tablet-optimised layout. Phone layout only, but it must not break on a tablet.

## 3. Architecture overview

```
trip-split/
  protocol/            language-neutral spec + golden test vectors (the contract)
    SPEC.md
    vectors/*.json
  apple/               iOS app (SwiftUI) + TripSplitCore Swift package
    project.yml        XcodeGen spec (the .xcodeproj is generated, not committed)
    Core/              Swift package: model, CRDT fold, money, settlement, codec, crypto, sync engine
    App/               SwiftUI app, persistence, transports
    fastlane/
  android/             Android app (Compose) + :core pure-Kotlin module
    core/              JVM module: same responsibilities as apple/Core
    app/               Compose UI, Room persistence, transports
  design/              Material 3 tokens (tokens.json) + generator for Theme.kt / Theme.swift
  docs/                plan, privacy policy (GitHub Pages), release runbooks
  .github/workflows/   CI per platform, path-filtered
```

The two apps share **no code**. They share a **contract**: `protocol/SPEC.md` plus the JSON test
vectors. Each platform's core module must pass every vector. This is the same pattern that worked for
the NamazVakti iOS/Android pair (golden corpus ported from Swift to Kotlin).

Layers in each app (top to bottom):

| Layer | iOS | Android |
|---|---|---|
| UI | SwiftUI, Observation | Jetpack Compose, ViewModel + StateFlow |
| App services | `GroupRepository`, `SyncCoordinator` | same names |
| Sync engine (pure) | `Core/Sync` | `core/sync` |
| Transports | Network.framework (LAN), CoreBluetooth (BLE) | NsdManager + sockets (LAN), Android BLE APIs |
| Persistence | GRDB (SQLite) | Room (SQLite) |
| Domain (pure) | `Core/Domain` | `core/domain` |

The pure modules have no UI, no OS transport and no database imports. All sync logic is testable
with an in-memory pair of fake links.

## 4. Data model: an append-only operation log

### 4.1 Why an operation log

Every device can write while offline. Two devices can edit the same expense before they meet. A
server cannot decide the winner because there is no central server. So the source of truth is an
**append-only log of operations** (ops). Every device holds a copy of the log for each group. Sync is
"give me the ops that I do not have". The visible state (expenses, balances) is a deterministic
**fold** of the log. Two devices that hold the same set of ops always show the same state, in any
receive order. This is an operation-based CRDT with a grow-only set of ops.

### 4.2 Identity

- **Device**: on first launch the app makes a random `deviceId` (UUID v4) and keeps it.
  **Backup-restore guard (review finding 1):** the app also writes an *install marker* to a store that
  backups never restore (iOS Keychain item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`;
  Android: a Keystore key alias). On launch, if the database exists but the marker does not, the
  database came from a backup or another phone. The app then makes a **new** `deviceId`. The old ops
  stay valid under the old id; the user claims their member again. The database file is also excluded
  from backup (`isExcludedFromBackup` on iOS; `dataExtractionRules` / `fullBackupContent` on Android).
  A restored device that minted a new id downloads its own old ops from peers in the next sync.
- **Member**: a person in a group. A member has a `memberId` (UUID), a display name, and **at most one**
  claiming device at a time (§4.6). A member can exist with no device (a friend without the app). This allows
  "join later": the creator adds "Ayşe" on day 1; on day 3 Ayşe installs the app, joins, and
  **claims** the existing member "Ayşe". Her past expenses stay hers.
- **Group**: `groupId` (UUID), name, base currency, and a 32-byte random **group key**.

### 4.3 Op envelope

```json
{
  "v": 1,
  "group": "<groupId>",
  "author": "<deviceId>",
  "seq": 17,
  "hlc": "001761214530123-0005-<deviceId>",
  "type": "expense.add",
  "body": { ... },
  "hash": "<hex SHA-256 of the canonical JSON of the op without the hash field>"
}
```

- `hash` protects the op identity (review finding 1c). When a device receives `(author, seq)` that it
  already holds with a **different** hash, it keeps its own copy, does not store the new one, and marks
  that author as *forked* in the debug screen. This only happens after a bug or a restore that slipped
  past the guard; the hash makes it visible instead of silent.

- `(author, seq)` is the op identity. `seq` starts at 1 per author per group and has no gaps. A device
  never reuses a `seq`. The device stores its next `seq` per group in the database in the same
  transaction as the op.
- `hlc` is a Hybrid Logical Clock stamp `MMMMMMMMMMMMMMM-CCCC-<deviceId>` (15-digit millis, 4-hex
  counter, device id as tie-breaker). Byte order of the string is the HLC order (SPEC §3). HLC gives a total order that respects causality on each device and stays close to
  real time. HLC rules (review finding 5), pinned in `hlc.json`:
  1. `send`: `millis = max(last.millis, wall)`; `counter = (millis == last.millis) ? last.counter + 1 : 0`.
  2. `receive(remote)`: if `remote.millis > wall + 24 h`, do **not** adopt the remote millis; keep local
     rules and show "a phone in this group has a wrong clock". Else
     `millis = max(last.millis, remote.millis, wall)` and the counter follows the standard HLC rule.
  3. If the counter would pass 65535, `millis = millis + 1`, `counter = 0`.
  4. The device persists `lastHlc` in the same transaction as each new op and each received batch, so a
     reboot plus a clock step-back never makes a lower stamp than the author's previous op.
  5. `hello` carries the sender's wall time, so the UI can warn about clock skew before ops arrive.
- An op is immutable after creation. A device never changes or removes an op from the log.
- **Forward compatibility (review finding 9):** a device stores and relays every op that decrypts and
  parses as an envelope, even when `type` is unknown or `v` is higher than it knows. The fold ignores
  such ops. This keeps the version vector truthful: "I hold seq 1..n" is never a lie.

### 4.4 Op types (v1)

| type | body | rule |
|---|---|---|
| `group.create` | name, baseCurrency, creatorMemberId, creatorName | must be `seq 1` of the creator; exactly one per group; implicitly claims `creatorMemberId` for the author device. The base currency is fixed for the life of the group (no `group.currency` op in v1). |
| `group.rename` | name | LWW by HLC |
| `member.add` | memberId, name | adds a member with no device |
| `member.rename` | memberId, name | LWW by HLC |
| `member.claim` | memberId | links the author device to the member; see §4.6 |
| `member.unclaim` | memberId | releases the author device's claim |
| `member.remove` | memberId | soft remove: the member leaves the default split of new expenses and cannot be picked for new ones. Existing expenses that name the member (`paidBy` or `split`) keep counting. A removed member with a non-zero balance stays visible as "left". |
| `expense.add` | expenseId, title, amountMinor, currency, rate, paidBy, split, date, category | creates an expense |
| `expense.edit` | expenseId, fields{...} | LWW by HLC per **field group** (§4.5 rule 3) |
| `expense.delete` | expenseId | tombstone; see §4.5 |
| `settlement.add` | settlementId, fromMember, toMember, amountMinor, currency, rate | records a real payment |
| `settlement.delete` | settlementId | tombstone |

`split` in v1:

```json
{ "mode": "equal", "members": ["<memberId>", ...] }
{ "mode": "exact", "shares": { "<memberId>": 1250, ... } }       // sum must equal amountMinor
{ "mode": "weights", "weights": { "<memberId>": 2, ... } }       // integers ≥ 1
```

### 4.5 Fold rules (deterministic)

1. Sort all ops of a group by `hlc` (millis, then counter, then deviceId as byte string).
2. Apply them in that order to an empty state.
3. `expense.edit` uses LWW per **field group** (review finding 4). The groups are:
   `money = {amountMinor, currency, rate, split}` (always edited and replaced together, so the amount
   and the exact shares can never come from two different edits), `title`, `date`, `category`,
   `paidBy`. An edit that changes any money field must carry the whole money group. A group is set if
   the edit's HLC is greater than the HLC that last set that group.
4. `expense.delete` marks the expense deleted. An edit with a greater HLC than the delete does **not**
   revive it (delete wins). An "undo delete" is a new `expense.add` with a new id.
5. An op that refers to an unknown id is kept in the log but has no effect until its target arrives.
   Because the fold always re-runs over the sorted log, the order of arrival does not matter.
6. An op that fails validation (bad sum, unknown currency code, negative amount) is kept in the log and
   flagged; the fold ignores it. Devices never reject an op during sync, else two devices could
   disagree forever about the log content. Validation is a **pure function of the op alone**: it does
   not read the local time, peer state or the OS currency list. The currency table is part of the spec
   (`protocol/vectors/currencies.json`) and both apps embed the same copy (review finding 13).
7. A merged expense can still be invalid, for example when `paidBy` names a member whose
   `member.add` never arrived. The fold then keeps the expense, marks it `needsAttention`, shows it
   with a warning, and **excludes it from balances** until it is valid again. Balances therefore always
   sum to zero.

The fold is a pure function `fold(ops) -> GroupState`. v1 recomputes it from the full log. A holiday
group has hundreds of ops, not millions, so a full fold takes milliseconds. A cached projection is an
optimisation for later (§16).

### 4.6 Member claim conflicts

One algorithm, inside the fold (review finding 3). Process `group.create`, `member.claim` and
`member.unclaim` in HLC order:

1. `group.create` gives `creatorMemberId` to the author device.
2. `member.claim(M)` by device D succeeds **only if M has no claimant now**. On success, D's previous
   member (if any) is released, then M gets D.
3. `member.unclaim(M)` by D releases M only if D is its claimant now.
4. A claim that fails has no effect. The losing device sees "Ayşe was claimed by another phone" and
   picks another member or adds a new one.

So two devices that claim the same member while apart resolve to the lower HLC. A device holds at most
one member per group. `fold.json` covers: two claims of one member; D claims M1 then M2; E claims M2
after D released it; unclaim by a non-claimant.

## 5. Money

- Amounts are **integer minor units** (`Int64`/`Long`). No floating point anywhere in money code.
- Currency is an ISO 4217 code. The minor-unit exponent comes from a table in the spec
  (JPY = 0, EUR = 2, KWD = 3, ...), not from the OS locale APIs, so both platforms agree.
- An expense in a foreign currency stores `rate` as a decimal string "units of base currency per one
  unit of expense currency", for example `"34.75"`. The syntax is fixed:
  `^[0-9]{1,9}(\.[0-9]{1,8})?$` and the value must be greater than zero. When the expense currency is
  the base currency, `rate` is `"1"`. Conversion:
  `baseMinor = round_half_even(amountMinor × rate × 10^(baseExp − expenseExp))`. Both apps compute it
  with **integer** arithmetic only: parse the rate into `(mantissa: Int64, scale: Int)`, multiply in
  128-bit (`Int128` on Swift, `BigInteger` on Kotlin), then round half-even once. This
  avoids the `Decimal` / `BigDecimal` differences (review finding 12).
- **Where conversion happens:** each expense is converted to base minor units **once**, and the split
  runs on the base amount. So balances are always in one currency and sum to zero exactly.
- Equal split remainder: an equal split of 1000 among 3 gives 333, 333, 334. Sort the participant
  `memberId`s as strings (byte order); give one extra unit to each of the first `remainder` members.
  The extra cents therefore land on the same members each time; this is accepted and documented.
- Weights split: largest-remainder method. Each member gets `floor(total × w / Σw)`; the leftover units
  go one each to members ordered by **largest fractional part first, then `memberId`**.
- Exact split: the shares are in the **expense** currency and must sum to `amountMinor`. They are
  converted to base with the weights rule (shares act as weights), so the converted shares also sum
  exactly to the converted total.
- Canonical JSON: RFC 8785 (JCS) with these limits: no floats anywhere (all numbers are integers;
  decimals are strings), and non-ASCII characters are written as raw UTF-8, never `\u` escaped, so a
  Turkish name gives the same bytes on both platforms.

## 6. Balances and settlement

- `balance(member) = paid − owed` in base-currency minor units, summed over live expenses and
  settlements.
- The sum of all balances is exactly zero (the vectors assert it).
- "Settle up" suggests transfers with the greedy algorithm: repeatedly match the largest debtor with
  the largest creditor and transfer `min(|debt|, credit)`. Ties break by `memberId`. This gives at most
  `n − 1` transfers. It is not always the global minimum (that problem is NP-hard), but it is simple,
  explainable and deterministic.
- A user records a real payment as a `settlement.add` op. The balances update; nothing is "closed".

## 7. Group key, invites and joining

- The creator's device makes a 32-byte random group key when it creates the group.
- **Invite** = a QR code with `tripsplit://join?g=<groupId>#k=<base64url key>` (review finding 11).
  The key is in the URL fragment, so a future https universal link never sends it to a server. The
  invite holds no group name and no device id. The QR code is the primary path. A share-link fallback
  exists, but the share sheet shows a warning: "Anyone who gets this link can read and change the
  group. Send it only to group members." The app never copies the link to the clipboard by itself.
- A user who scans the invite stores the group id and key, then opens the sync screen. The first sync
  with any member device downloads the full log. Join is then complete: the new device sees all
  history and claims or adds its member.
- Anyone who holds the key is a full member. v1 has no roles and no key rotation. The plan accepts
  this because the group is a set of friends in the same place. The README states it plainly.

### 7.1 A newcomer and the past expenses (owner request, 2026-10-23)

A split names its members by id, so a person who joins later is not in any earlier split. The app makes
this a visible choice when a member is added or claimed for the first time:

- **From now on** (default): earlier expenses do not change. New expenses include every current member
  by default, so they include the newcomer.
- **Also earlier expenses**: the app writes one `expense.edit` for each earlier expense that is not
  deleted, with the newcomer added to its split. `equal`: add the id. `weights`: add the id with weight 1.
  `exact`: do not change it, because the amounts must still add up; the app lists these expenses so that
  someone can edit them by hand.

"Earlier" means every expense in the fold at that moment. The choice needs no protocol change: the edits
are normal ops and sync as usual. An edit replaces the money field group (§4.5), so a concurrent amount
edit on the same expense can lose. The confirmation tells the user how many expenses change.

## 8. Wire protocol

### 8.1 Framing and encryption

Review finding 2 replaced the first design (a static key with a 4-byte random nonce prefix can repeat a
GCM nonce over thousands of connections, and recorded frames could be replayed).

1. **Pre-handshake (plaintext):** each side sends one 33-byte record:
   `magic "TS1" (3 bytes) || sessionSalt (16 random bytes) || groupHint (14 bytes)`. `groupHint` is the
   discovery tag of the group it wants to sync (§9.1). If the peer does not hold that group, it closes.
2. **Session key:** `HKDF-SHA256(ikm = groupKey, salt = saltLow || saltHigh, info = "tripsplit/v1/frame/" || groupId)`.
   The deviceIds are not known yet at this point, so the two salts are ordered **by their bytes**
   (`saltLow` is the smaller one). If the salts are equal, both sides close and retry. Each connection
   gets a fresh key, so a nonce can only repeat inside one connection, and the counter prevents that.
3. **Frame:** `u32 length (big-endian) || AES-256-GCM(ciphertext || 16-byte tag)`. The nonce is not sent:
   `nonce = direction (1 byte: 0x01 for the smaller-salt side, 0x02 for the other) || 3 zero bytes || u64 counter`,
   counter starting at 0 for each direction. A receiver that sees a tag failure closes the connection.
4. The receiver rejects a `length` above 1 MiB before it allocates memory.
- AES-GCM and HKDF exist natively in CryptoKit and in `javax.crypto` (HKDF is ~15 lines on Android).
  No third-party crypto library. `crypto.json` pins the HKDF output and one encrypted frame.
- AES-GCM and HKDF exist natively in CryptoKit and in `javax.crypto` (HKDF is ~15 lines on Android).
  No third-party crypto library.
- The plaintext is UTF-8 JSON. Keys are sorted, no spaces (canonical JSON), so test vectors can pin
  bytes.
- A frame that fails to decrypt closes the connection. It means a wrong group or a damaged link.

### 8.2 Messages

| message | fields | purpose |
|---|---|---|
| `hello` | protocolVersion, deviceId, appVersion, groupId, vector, wallMillis, maxChunk | first frame from each side |
| `ops` | ops[] (≤ 64 per frame, ≤ 256 KiB) | ops the other side lacks |
| `done` | vector | "I sent everything you lacked" |

`vector` is the version vector `{deviceId: highest contiguous seq}`. If the two `protocolVersion`
values differ, both sides use the lower one. `maxChunk` is used by BLE only (§9.2). The session ends
when each side has sent and received `done`; then the initiator closes the link (no `bye` message).

### 8.3 Session (anti-entropy)

1. Both sides send `hello`.
2. Each side computes the ops the peer lacks: every own op `(a, s)` with `s > peer.vector[a]`.
3. Each side streams them in `seq` order per author, then sends `done`.
4. Each side inserts received ops in one database transaction per frame (idempotent on
   `(author, seq)`), then re-folds.
5. When both sides sent `done` and received `done`, the session ends.

The session is symmetric and stateless between connections. A dropped connection loses nothing; the
next session resends what is missing. A device can relay ops of a third device (gossip): A meets B,
B later meets C, so C gets A's ops without meeting A. This is important for a group that is rarely all
in one room.

### 8.4 One session per peer pair

Connection roles are **capability-based** (review finding 6). Over BLE, any device that can scan
connects to any device that advertises; some Android phones cannot advertise, and they must still be
able to sync. Over LAN, both sides may connect. So two connections between one pair can exist. The
dedup happens **after** `hello`, when both deviceIds are known: if a session with that peer for that
group is already running, the device with the higher `deviceId` closes the newer connection. Between
sessions, a device waits 30 seconds before it syncs again with the same peer (unless a new local op
exists), so the screen does not loop.

## 9. Transports

The sync engine sees one interface:

```
protocol Link { func send(_ frame: Data) async throws; var incoming: AsyncStream<Data> { get }; func close() }
interface Link { suspend fun send(frame: ByteArray); val incoming: Flow<ByteArray>; fun close() }
```

### 9.1 Wi-Fi LAN

- Discovery: DNS-SD service type `_tripsplit._tcp`. **SPEC §10.1 is normative** for the TXT keys
  (`v`, `d`, `t` as 24-hex-character discovery tags), the port rules and the timeouts. A stranger on
  hotel Wi-Fi learns no group names (SPEC §10.3 lists what the tag does reveal).
- iOS: `NWListener` with a Bonjour service + `NWBrowser`. Needs `NSLocalNetworkUsageDescription` and
  `NSBonjourServices = ["_tripsplit._tcp"]` in Info.plist. The local network prompt appears on first use.
- Android: `NsdManager` register/discover + `ServerSocket`/`Socket`. Needs a multicast lock while the
  sync screen is open on some devices. Android 14+ uses `NsdManager` callbacks; resolve one service at a
  time on older versions (known `FAILURE_ALREADY_ACTIVE` issue).
- **Fixed-port fallback (review finding 8):** every listener also binds TCP port **47831**. If DNS-SD
  finds no peer in 5 seconds, a client tries the default gateway IP on that port. This covers the
  Android hotspot host (NsdManager often cannot advertise or discover on the softAP interface) and
  networks that block mDNS but allow unicast.
- Phone hotspot works: the hotspot phone is on the same subnet as its clients. Hotel Wi-Fi with client
  isolation does **not** work; the sync screen tells the user to use a hotspot or Bluetooth.

### 9.2 Bluetooth Low Energy

- Each device is a GATT **peripheral** (advertises the TripSplit service UUID) and a **central** (scans
  for it) at the same time while the sync screen is open.
- GATT service `TripSplit` with:
  - `rx` characteristic (**write with response** in v1): central → peripheral bytes. Slower than write
    without response, but each chunk is acknowledged, so there is no silent loss (review finding 7).
  - `tx` characteristic (notify): peripheral → central bytes.
  - `info` characteristic (read): deviceId + discovery tags, so a central can skip foreign groups
    before it starts a session.
- The byte stream (handshake record, then frames) is split into chunks. **SPEC §10.2 is normative**
  for the GATT UUIDs, the 3-byte chunk header (flags and `seq`), reassembly and roles. Chunk size is
  **per direction**:
  - central → peripheral: iOS `peripheral.maximumWriteValueLength(for: .withResponse)`; Android
    `negotiatedMtu − 3` after `onMtuChanged`.
  - peripheral → central: iOS `central.maximumUpdateValueLength`; Android peripheral uses the MTU from
    `BluetoothGattServerCallback.onMtuChanged` (default 23 → 20 bytes until then).
  - Each side also sends its receive limit as `hello.maxChunk`; the sender uses the smaller value.
- Flow control: central writes wait for the write callback (`didWriteValueFor` /
  `onCharacteristicWrite`). Peripheral notifications: iOS `updateValue(...)` returning false →
  wait for `peripheralManagerIsReady(toUpdateSubscribers:)`; Android waits for `onNotificationSent`
  before the next `notifyCharacteristicChanged`.
- Request MTU 512 on Android; iOS negotiates by itself (usually 185 or more).
- Throughput is low (roughly 5–20 KB/s in practice). A holiday log is small (an op is ~300 bytes,
  1,000 ops ≈ 300 KB before compression), so a first full join can take up to ~1 minute over BLE.
  v1 accepts this; the sync screen shows progress. LAN is preferred when both are available.
- Platform limits:
  - iOS cannot advertise reliably in the background (the service UUID moves to an overflow area that
    Android scanners do not see). So BLE sync works **only while the sync screen is open** on both
    phones. The UI says this.
  - Android 12+ needs `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` at runtime
    (`neverForLocation`). Android 10–11 needs location permission for scans.
  - Some Android devices do not support the peripheral role (`isMultipleAdvertisementSupported`
    false). Such a device can still act as a central.
- Sync screen policy: run LAN and BLE discovery together. If a peer is reachable by both, use LAN.

### 9.3 When does sync run

- Foreground only in v1, while the group's **Sync** screen is open ("Nearby" with a live peer list).
- When the app returns to the foreground with the sync screen open, discovery restarts.
- The Nearby screen keeps the display on (`isIdleTimerDisabled` / `FLAG_KEEP_SCREEN_ON`), because a
  locked screen stops BLE advertising on iOS (review finding 14).
- Android allows at most 5 scan starts in 30 seconds. The app starts one long scan when the screen opens
  and never restarts it on peer events.
- Background sync (BGTask, foreground service) is out of scope for v1 (§16).

### 9.4 Internet relay (owner request, 2026-10-23)

Two phones in different cities could not sync in the first release. The owner asked for online sync with
a free server. The rules are in SPEC §10.4. The reasons:

- **A mailbox, not a backend.** The op log merges itself and every op is encrypted with the group key.
  So the server only has to keep encrypted batches and give them back in order. It has no logic, no
  accounts and no plain data. A broken or curious relay can drop or delay data. It cannot read or
  change it, because AES-GCM rejects a changed blob.
- **Cloudflare Workers with one Durable Object for each mailbox.** The free plan includes Workers and
  SQLite-backed Durable Objects. A Durable Object gives one ordered writer for each mailbox, so the
  cursor needs no locks. It does not sleep the way some free databases do. Alternatives that we did
  not choose: Supabase (a free project pauses after about a week with no use), Firebase (large SDKs on
  both apps), public Nostr relays (no promise of retention).
- **Separate names.** `mailboxId` and `relayToken` come from the group key with their own labels. A person
  who reads the discovery tag on a hotel network cannot find or read the mailbox.
- **Trust on first use for writes.** The first `POST` stores the token hash. The server never needs the
  key, and a stranger who guesses a mailbox id cannot write to it.
- **Pull before push.** A new device first reads the mailbox, so it uploads only what the relay lacks.
  Without this rule, every device would upload the full history once.
- **Same receive rules.** Ops from the relay go through the same checks and the same idempotent store
  as ops from a nearby session. The two paths can run at the same time.
- **User control.** Settings has a switch "Sync over the internet", on by default, with one line that
  says what the relay sees. The switch is for each device.
- **Not in scope now.** Live push over WebSocket, background sync, and compaction of old batches. A
  60-second poll while the app is open is enough for a trip.

Owner steps: a Cloudflare account, `wrangler login`, and one `wrangler deploy`. The relay URL goes into
both app builds. Until then, the apps use the URL `https://relay.invalid` and the relay sync stays off.
Done on 2026-10-24: the relay runs at `https://tripsplit-relay.olcayertas.workers.dev`, and both builds use it.

## 10. Persistence

Same schema on both platforms (SQLite):

```
ops(group_id, author, seq, hlc, type, op_json, hash, status, received_at, PRIMARY KEY(group_id, author, seq))
groups(group_id PK, group_key BLOB, next_seq, created_at)
device(singleton: device_id, display_name, last_hlc)
forks(group_id, author, seq, foreign_hash, seen_at)
```

`op_json` keeps the exact canonical bytes as received, so relaying an op never changes its hash, even
when the op has an unknown type. The group name and "my member" are fold results, not columns.

- `status` is `ok` or `invalid` (§4.5 rule 6).
- The group key stays in the database for v1. iOS: the database file uses
  `NSFileProtectionComplete`. Android: app-private storage. Moving the key to the Keychain / Android
  Keystore is a hardening step (§16).
- The fold result is in memory (and exposed as observable state). No projection tables in v1.

## 11. UI (both platforms, same flows)

1. **First run**: enter your name.
2. **Groups**: list of groups with your balance per group. Buttons: Create, Join.
3. **Create group**: name, base currency. Then add the members you know now (names only).
4. **Join group**: scan QR (camera) or paste link → "Open Nearby to download the group".
5. **Group**: tabs Expenses / Balances / Members. A sync badge shows last sync time and peers seen.
6. **Add/Edit expense**: title, amount, currency (+ rate if foreign), paid by, split (equal by default
   over all members; toggle members; exact or weights), date, category.
7. **Balances**: per-member balance + "Suggested payments" + "Record payment".
8. **Members**: list, add member, rename, invite (QR + share sheet), "This is me" (claim).
9. **Nearby (sync)**: live peer list with transport icon (Wi-Fi / Bluetooth), progress, last result,
   help text for client isolation and BLE foreground limits.
10. **History**: per-expense change log from the ops (who changed what, when). This is cheap because
    the data is already an op log, and it builds trust when anyone can edit.

Localisation: English and Turkish from the start (string catalogs on iOS, `strings.xml` on Android).

### 11.1 Design language: Material 3 (owner request, 2026-10-23)

- **One design system for both apps.** `design/tokens.json` holds the Material 3 tokens: a seed colour,
  the generated light and dark colour roles (primary, secondary, tertiary, surface containers, error,
  outline), the type scale (display → label), the shape scale (extra-small → extra-large corner radii)
  and the spacing steps. A small script generates `Theme.kt` and `Theme.swift` from it, so the two apps
  cannot drift.
- **Android:** Jetpack Compose **Material 3 Expressive** (`androidx.compose.material3`), dynamic colour
  on Android 12+ with the seed palette as fallback, edge-to-edge, predictive back. Components:
  `NavigationBar`, large top app bars, `ExtendedFloatingActionButton` for "Add expense", filled and
  tonal buttons, `ModalBottomSheet` for the split editor, segmented buttons for split mode, chips for
  members.
- **iOS:** SwiftUI with the same Material 3 tokens: colour roles, type scale (Roboto Flex is not
  bundled; SF Pro with the Material size/weight scale), corner shapes, tonal surfaces, a FAB-style
  "Add expense" button, Material cards and chips. Platform behaviour stays native: navigation stack,
  swipe-back, sheets, haptics, Dynamic Type and VoiceOver. This keeps one brand look without an
  iPhone app that fights iOS gestures.
- Visual identity: money is shown with tabular figures; positive balance uses a tertiary-green role,
  negative uses the error role, never colour alone (always a "+"/"−" sign and text).
- Motion: Material 3 Expressive spring motion on Android; matching spring durations on iOS.
- Accessibility: minimum touch target 48 dp / 44 pt; contrast AA on all colour roles in both themes.

## 12. Platform setup

### 12.1 iOS

- Minimum iOS 18 (Swift `Int128` for money arithmetic). SwiftUI + Observation. Swift 6 language mode, strict concurrency.
- XcodeGen `project.yml`; `TripSplitCore` is a local Swift package with its own `swift test`.
- Dependencies (SPM): GRDB. QR scan uses `AVFoundation` (or `VisionKit` `DataScannerViewController`);
  QR make uses CoreImage. No other third-party code.
- Bundle id `com.olcayertas.TripSplit`; team `SNZ29V4PJZ`.
- Info.plist: `NSLocalNetworkUsageDescription`, `NSBonjourServices`, `NSBluetoothAlwaysUsageDescription`,
  `NSCameraUsageDescription`. Export compliance: the app uses standard algorithms (AES-GCM, HKDF from
  CryptoKit) for its own protocol, so it **uses encryption** and is **exempt** (mass-market). The owner
  answers this once in App Store Connect; then `ITSAppUsesNonExemptEncryption = false` matches the
  answer and later builds skip the prompt (§14.1).

### 12.2 Android

- Known-good toolchain on this Mac: AGP 8.11.1, Kotlin 2.2.0 (K2), Compose BOM 2025.06.01,
  compileSdk/targetSdk 36, JDK = Android Studio JBR 21 (not JDK 25). Gradle wrapper bootstrapped from
  the cached 8.14.3 distribution.
- `minSdk 29` (Android 10). Modules: `:core` (pure Kotlin/JVM), `:app`.
- Dependencies: Room + KSP, kotlinx.serialization (canonical JSON writer is hand-written, see §13),
  CameraX + ML Kit barcode (bundled model, works offline) for QR scan, ZXing core for QR make.
- Application id `com.olcayertas.tripsplit`.

## 13. The contract: `protocol/`

`protocol/SPEC.md` is the normative text of §4–§9. `protocol/vectors/` holds JSON files that both core
modules load in their unit tests:

| vector file | input | expected |
|---|---|---|
| `currencies.json` | — | the ISO 4217 code → minor-unit exponent table both apps embed |
| `canonical_json.json` | JSON values | exact canonical UTF-8 bytes (hex) and op hashes |
| `hlc.json` | sequences of local/remote clock events | HLC stamps and their sort order |
| `money.json` | amount, currencies, rate | base minor units |
| `split.json` | amount, split spec | per-member shares |
| `fold.json` | op logs (shuffled several ways) | group state (expenses, members, flags) |
| `settlement.json` | balances | transfer list |
| `crypto.json` | group key, nonce, plaintext | HKDF key, ciphertext+tag, discovery tag |
| `session.json` | two logs | ops each side must send; fork detection on hash mismatch; unknown op types relayed |

Rule: a change to behaviour changes `SPEC.md` and a vector **first**; then both platforms change. CI
fails if either core module fails a vector.

## 14. Distribution

### 14.1 iOS with Fastlane

- `apple/Gemfile` pins `fastlane ~> 2.240`; run through `bundle exec`.
- `apple/fastlane/Appfile`: app id, team `SNZ29V4PJZ`.
- Lanes:
  - `ios inspect` — read-only App Store Connect check (key works, app record exists).
  - `ios beta` — `xcodegen generate`; bump build number from TestFlight latest + 1; `build_app`
    (Release, App Store export) with automatic signing and
    `xcargs: "-allowProvisioningUpdates -authenticationKeyPath … -authenticationKeyID … -authenticationKeyIssuerID …"`
    (review finding 10; no `match`, one owner); `upload_to_testflight` (skip waiting).
  - No `metadata` lane in v1; the app is shared through TestFlight, not the App Store.
- Known trap from sarp-mobil: `gym` passes `xcargs` into `-exportArchive` too. Check the export step
  on the first run.
- External TestFlight builds expire after 90 days. A trip longer than that needs a fresh build.
- Auth: App Store Connect API key (`.p8`, key id, issuer id) from a git-ignored `.env`
  (`~/.appstoreconnect/…`), never committed. The key needs the **App Manager** role.
- One-time manual steps for the owner: create the App Store Connect app record, accept agreements,
  answer export compliance and the privacy label ("Data Not Collected"), add TestFlight testers.
- Sharing: TestFlight internal/external testing is the channel to the group (no App Store review for
  internal testers; external testers need a one-time beta review).

### 14.2 Android APK

- Release build with R8. Signing config reads keystore path and passwords from git-ignored
  `android/keystore.properties`; the keystore file lives outside the repo and its passwords in the macOS
  keychain.
- `scripts/release-android.sh`: `./gradlew :app:assembleRelease`, verify the signature with
  `apksigner verify`, print the SHA-256, copy to `dist/TripSplit-<version>.apk`.
- Optional: attach the APK to a GitHub Release (`gh release create`), which gives one link to share.
- Users must allow "install unknown apps". Updates keep working only with the **same** keystore, so the
  plan adds a keystore backup step to the runbook.

## 15. Phases and acceptance

Each phase ends with green tests on both platforms. Each phase is one or more PRs linked to the
GitHub tracking issue.

| # | Phase | Done when |
|---|---|---|
| 0 | Repo, CI skeleton, `protocol/SPEC.md` v1, vectors for currencies, canonical JSON, HLC, money, split | vectors exist; both core modules compile; CI runs |
| 1 | Core domain on both platforms: ops, HLC, fold (incl. claims, field groups), money, split, settlement | all domain vectors pass on iOS and Android |
| 2 | Crypto + framing + sync session engine with in-memory fake links, **including join of an empty device** (invite parse → first session) | `crypto.json` + `session.json` pass; fuzz test: random op sets on N fake devices converge |
| 3 | Persistence (GRDB / Room), repositories, install marker + backup exclusion | op insert is idempotent; restart keeps state; restore simulation mints a new deviceId |
| 4 | UI: first run, groups, create, members, expenses, balances, history | manual walkthrough on simulator/emulator; UI tests for add-expense flow |
| 5 | LAN transport + Nearby screen | iPhone ↔ Android sync over one Wi-Fi and over a hotspot (both directions of host) |
| 6 | BLE transport | iPhone ↔ Android sync over BLE with Wi-Fi off; join of a 500-op group completes |
| 7 | Invite UI (QR make + scan, share warning), member claim UI | a new phone joins on day 3 and claims a pre-added member |
| 8 | Distribution: Fastlane `beta`, Android release script, privacy page | TestFlight build installs; APK installs on a clean phone |
| 9 | Field test with 3+ devices, mixed platforms; fix list | three phones converge after a day of offline edits |
| 10 | Internet relay (§9.4): SPEC §10.4, reference relay, `relay.json` vectors, Cloudflare Worker, client in both apps, settings switch, privacy page | both apps pass `relay.json`; the Worker passes its tests; an iPhone and an Android phone on two networks converge through the deployed relay |

Phases 0–2 hold most of the risk that unit tests can catch. Phases 5–6 hold the risk that only real
devices can catch; the owner must provide at least one real iPhone and one real Android phone for them
(simulators have no Bluetooth, and the iOS simulator's Bonjour behaviour differs from devices).

## 16. Later (not v1)

- Background sync (iOS BGTask is too limited for P2P; Android foreground service).
- Group key in Keychain / Keystore; key rotation; remove a member's access.
- Receipt photos (sync over LAN only, content-addressed blobs).
- Snapshot + log compaction for very long groups.
- CSV / PDF export of the final settlement.
- Exchange rates fetched when online.
- Rotating discovery tags (for example an HMAC over a day epoch; accept the previous, current and next
  epoch). v1 uses one static tag for each group (SPEC §10.3).
- Apple Multipeer Connectivity for iPhone↔iPhone speed (it does not talk to Android, so it is an extra
  transport, not a replacement).

## 17. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| iOS↔Android BLE quirks (MTU, bonding prompts, OEM stacks) | BLE sync fails on some phones | own GATT framing with small chunks; no bonding; LAN as first choice; test on 2+ Android OEMs |
| Hotel Wi-Fi client isolation | LAN discovery finds nothing | hotspot tip in UI; BLE fallback |
| Two platforms drift in fold/money rules | balances differ between phones | shared vectors; fuzz convergence test; a "state hash" shown in debug screen to compare phones |
| Wrong phone clocks | odd edit winners | HLC; the fold never trusts wall-clock alone |
| Lost Android keystore | users cannot update | keystore backup in runbook |
| Anyone with the invite can read and write | privacy | documented; QR in person is the main path; key rotation later |
| Restore from backup reuses `(author, seq)` | silent permanent divergence | install marker + new deviceId; DB excluded from backup; op hash detects forks |
| A phone with a far-future clock | its edits win every LWW forever | 24 h forward-drift cap on remote HLC; skew warning |
| The relay is down or the free plan limit is reached | no online sync | nearby sync still works; the client backs off; limits in SPEC §10.4.5 |
| Anyone with the invite can read the mailbox | privacy | same rule as nearby sync; the relay holds only ciphertext |
| Discovery tags and device ids are static | a stranger can track a device across networks | accepted for v1; documented; rotate tags per day later |

## 18. Decisions

All open decisions are closed (see §1). Review record: Fable 5.1 review on 2026-10-23 gave 2 blockers,
8 major and 5 minor findings; each is applied in the section that names "review finding N".
