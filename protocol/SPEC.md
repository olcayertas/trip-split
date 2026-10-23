# TripSplit protocol and domain specification — v1

This document is normative. The iOS core (`apple/Core`) and the Android core (`android/core`) must
implement it exactly. `protocol/reference/tripsplit_ref.py` is its executable form, and
`protocol/reference/gen_vectors.py` writes the golden vectors in `protocol/vectors/` from it.

To change behaviour, do these steps in this order:

1. Change this document.
2. Change the reference model.
3. Run `python3 protocol/reference/gen_vectors.py`.
4. Change both platform cores until their vector tests pass.

The design reasons are in [docs/PLAN.md](../docs/PLAN.md). This file only states the rules.

## 1. Identifiers and text

- Every id (group, device, member, expense, settlement) is a **lowercase** UUID string that matches
  `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`.
- "Order by id" means byte order of the UTF-8 string. For lowercase UUIDs this is plain ASCII order.
- Text length is counted in **Unicode scalar values** (Swift `unicodeScalars.count`, Kotlin
  `codePointCount`). The core does not trim or normalise text. The UI trims before it writes an op.

## 2. Canonical JSON

Used for op hashes and for every frame on the wire. Rules (a subset of RFC 8785 / JCS):

- Allowed values: object, array, string, `true`, `false`, `null`, integer.
- Integers are in `[-(2^53-1), 2^53-1]`. A number with a fraction or exponent is **rejected**. A
  decimal value (an exchange rate) is a string.
- No whitespace.
- Object keys are sorted by their **UTF-16 code units**. For ASCII keys this is byte order.
- Strings: escape `"` as `\"`, `\` as `\\`, U+0008 `\b`, U+000C `\f`, U+000A `\n`, U+000D `\r`,
  U+0009 `\t`, and every other code point below U+0020 as `\u00XX` with **lowercase** hex. Write every
  other character as raw UTF-8. Do not escape `/`, U+007F or non-ASCII.

Vectors: `canonical_json.json`.

## 3. Hybrid logical clock (HLC)

A stamp is the string `MMMMMMMMMMMMMMM-CCCC-<deviceId>`:

- `M`: wall-clock milliseconds since the Unix epoch, 15 decimal digits, zero padded.
- `C`: counter, 4 lowercase hex digits (`0000`–`ffff`).
- Stamps are ordered by **byte order of the whole string**. The fixed widths make this equal to
  ordering by (millis, counter, deviceId).

Each device keeps `(millis, counter)` and persists it in the same transaction as every new op and
every received batch.

- **send(wall):** if `wall > millis` then `millis = wall, counter = 0`, else `counter += 1`.
- **receive(remote, wall):** if `remote.millis > wall + 86_400_000`, refuse: the state does not change
  and the UI shows a clock-skew warning. Otherwise `m = max(millis, remote.millis, wall)` and
  - `m == millis == remote.millis` → `counter = max(counter, remote.counter) + 1`
  - `m == millis` only → `counter = counter + 1`
  - `m == remote.millis` only → `counter = remote.counter + 1`
  - else → `counter = 0`;
  then `millis = m`.
- After send and receive: if `counter > 0xffff` then `millis += 1, counter = 0`.

The core takes the wall time as a parameter. It never reads the system clock itself.

Vectors: `hlc.json`.

## 4. Operations

### 4.1 Envelope

```json
{"v":1,"group":"<id>","author":"<deviceId>","seq":1,"hlc":"<stamp>","type":"group.create","body":{},"hash":"<hex>"}
```

- `(author, seq)` identifies the op inside a group. `seq` starts at 1 for each author in each group
  and increases by 1 for each op that author writes.
- An op never changes after it is written.
- A device stores and relays every op whose envelope parses, also when `v` or `type` is unknown.

### 4.2 Hash

`hash` is the lowercase hex SHA-256 of the canonical JSON of the envelope **without** the `hash` key.
A device that receives an `(author, seq)` it already holds with a different hash keeps its own copy and
records a fork for that author (see `docs/PLAN.md` §4.3). The fold does not read `hash`.

### 4.3 Types and bodies

Each body has exactly the keys listed: a missing key or an extra key makes the op invalid.
`name` texts are 1–60 scalars; `title` is 1–100 scalars.

| type | body keys |
|---|---|
| `group.create` | `name`, `baseCurrency`, `creatorMemberId`, `creatorName` |
| `group.rename` | `name` |
| `member.add` | `memberId`, `name` |
| `member.rename` | `memberId`, `name` |
| `member.claim` | `memberId` |
| `member.unclaim` | `memberId` |
| `member.remove` | `memberId` |
| `expense.add` | `expenseId`, `title`, `date`, `category`, `paidBy`, `money` |
| `expense.edit` | `expenseId`, `fields` (non-empty object; keys from `title`, `date`, `category`, `paidBy`, `money`) |
| `expense.delete` | `expenseId` |
| `settlement.add` | `settlementId`, `fromMember`, `toMember` (≠ `fromMember`), `amountMinor`, `currency`, `rate`, `date` |
| `settlement.delete` | `settlementId` |

- `date`: `YYYY-MM-DD`, month 01–12, day 01–31 (no calendar check).
- `category`: one of `food`, `transport`, `lodging`, `activities`, `shopping`, `other`.
- `baseCurrency`, `currency`: a code from `currencies.json`.
- `money` is the **money field group**:
  `{"amountMinor": 1..10^12, "currency": code, "rate": rate string, "split": split}`.
- `split` is one of:
  - `{"mode":"equal","members":[id, ...]}` — 1–100 unique ids.
  - `{"mode":"weights","weights":{id: 1..1000, ...}}` — 1–100 entries.
  - `{"mode":"exact","shares":{id: 0..10^12, ...}}` — 1–100 entries; the values sum to `amountMinor`.

Validation is a pure function of the op. It does not read the group state, the clock or the OS.

### 4.4 Fold

`fold(groupId, ops) → state`:

1. Put aside every op with `v ≠ 1` or an unknown `type` → `unknownOps`.
2. Put aside every op with `group ≠ groupId` or an invalid body → `invalidOps`.
3. Sort the rest by (hlc bytes, author bytes, seq).
4. Apply them in that order to an empty state. An op whose target does not exist **at that point**
   has no effect. Because ops are applied in HLC order, "last writer wins" is "the later op
   overwrites".
   - `group.create`: if a group already exists or `seq ≠ 1` → `invalidOps`. Else set
     `group = {name, baseCurrency}`, add the creator member if it is new, then claim it for the author.
   - `group.rename`: set the name if the group exists.
   - `member.add`: add `{name, removed: false, claimedBy: null}` if the id is new.
   - `member.rename`: set the name if the member exists.
   - `member.claim(M)` by device D: if M exists, is not removed and `claimedBy` is null, then release
     the member D holds now (if any) and set `M.claimedBy = D`.
   - `member.unclaim(M)` by any device: if `M.claimedBy` is not null, set it to null. The device that held
     M then holds no member. Any device may release any claim, so a restored phone (new `deviceId`) can
     free its old member and claim it again. A claim is not an access control: every member can already
     edit and remove members.
   - `member.remove(M)`: if M exists and is not removed, set `removed = true` and release its claim.
   - `expense.add`: add the expense if the id is new (`deleted: false`, `createdBy: author`).
   - `expense.edit`: if the expense exists and is not deleted, overwrite each given field. `money` is
     one field: it replaces all four money values together.
   - `expense.delete`: if the expense exists, set `deleted = true`. A later edit cannot undo it.
   - `settlement.add` / `settlement.delete`: like expenses.
5. Compute validity, conversions and balances (§5, §6) on the final state.

An expense (or settlement) **needs attention** when any of these is true. Then it does not count in
balances:

- the group does not exist (no base currency);
- `paidBy` or a split member (or `fromMember` / `toMember`) is not a member (removed members count as
  members);
- `currency == baseCurrency` and `rate ≠ "1"`;
- the conversion is out of range.

The state (vector `expected`) is:

```
group             {name, baseCurrency} or null
members           [{id, name, removed, claimedBy}] ordered by id
expenses          [{id, title, date, category, paidBy, money, deleted, createdBy,
                    needsAttention, baseAmountMinor, baseShares}] ordered by id
settlements       [{id, fromMember, toMember, amountMinor, currency, rate, date, deleted,
                    needsAttention, baseAmountMinor}] ordered by id
balances          {memberId: base minor units}; one entry per member; empty when group is null
suggestedPayments [{from, to, amountMinor}] (§6)
invalidOps        ["<author>:<seq>", ...] ordered by string bytes
unknownOps        ["<author>:<seq>", ...] ordered by string bytes
```

`baseAmountMinor` and `baseShares` are null when the item needs attention. They are present for
deleted items that are otherwise valid. The fold result must not depend on the input order of `ops`.

Vectors: `fold.json`.

## 5. Money

### 5.1 Rate

A rate matches `^[0-9]{1,9}(\.[0-9]{1,8})?$` and is greater than zero. It means "units of base
currency for one unit of the expense currency". Parse it as `(mantissa, scale)`:
`"34.75"` → `(3475, 2)`.

### 5.2 Conversion

```
shift = exp(base) − exp(currency) − scale
num   = amountMinor × mantissa                       (needs 128-bit integers)
base  = num × 10^shift            if shift ≥ 0
      = roundHalfEven(num / 10^(−shift)) otherwise
```

If `base > 10^15` the conversion is out of range. Use integer arithmetic only.

Vectors: `money.json`, `currencies.json`.

### 5.3 Allocation and splits

`allocate(total, [(id, w)])` with `W = Σw > 0`:

1. `share(id) = floor(total × w / W)`, `rem(id) = (total × w) mod W`.
2. `leftover = total − Σ share`.
3. Order the ids by `rem` descending, then by id ascending. Give `+1` to each of the first `leftover`.

The split converts the expense **once** to `baseTotal`, then:

- `equal`: `allocate(baseTotal, each member weight 1)`.
- `weights`: `allocate(baseTotal, weights)`.
- `exact`: `allocate(baseTotal, shares)` — the expense-currency shares act as weights.

Shares always sum to `baseTotal`.

Vectors: `split.json`.

## 6. Balances and settlement

- For each counted expense: `balance[paidBy] += baseAmount`; `balance[m] −= share(m)`.
- For each counted settlement: `balance[from] += baseAmount`; `balance[to] −= baseAmount`.
- Σ balances is exactly 0.

Suggested payments (greedy):

```
loop:
  debtor   = member with the most negative balance (ties: smallest id)
  creditor = member with the most positive balance (ties: smallest id)
  stop when either is missing
  amount = min(−balance[debtor], balance[creditor])
  emit {from: debtor, to: creditor, amountMinor: amount}; apply it
```

Vectors: `settlement.json`.

## 7. Version vector and anti-entropy

- `vector[author]` = the highest `n` such that the device holds seq `1..n` of that author. Authors with
  `n = 0` are left out.
- A device sends the peer every op it holds with `seq > peerVector[author]` (0 when absent), ordered
  by author bytes, then seq.

Vectors: `session.json`.

## 8. Wire protocol

### 8.1 Handshake and crypto

The **initiator** is the side that connects (TCP client, BLE central). The other side is the
**responder**. One connection syncs one group.

1. Each group has a 32-byte random `groupKey`.
   `discoveryTag = HMAC-SHA256(key = groupKey, msg = "tripsplit/v1/discover")[0..12]`.
2. The initiator sends a 31-byte plaintext record: `"TS1" || salt (16 random bytes) || discoveryTag`.
3. The responder reads it. If it holds no group with that tag, or the magic is wrong, it closes.
   Else it sends its own record with a new random salt and the **same** tag.
4. If the two salts are equal, both sides close.
5. `sessionKey = HKDF-SHA256(ikm = groupKey, salt = lowSalt || highSalt, info = "tripsplit/v1/frame/" || groupId, L = 32)`.
   `lowSalt` is the salt that is smaller in byte order. HKDF is RFC 5869.
6. The side that sent `lowSalt` uses direction byte `0x01`; the other side uses `0x02`.
7. Each side keeps a send counter (u64, starts at 0) and a receive counter for the other direction.
8. A **frame** is `u32 big-endian length || AES-256-GCM(sessionKey, nonce, plaintext)` (ciphertext followed
   by the 16-byte tag; no additional data). `nonce = direction || 0x000000 || u64 big-endian counter`.
   The receiver uses the sender's direction byte and its receive counter.
9. A receiver closes the connection when `length > 1 MiB` (it checks before it allocates memory) or when
   a frame does not decrypt.

Vectors: `crypto.json` (HKDF RFC 5869 case 1, tag, records, session key, five frames, one tampered frame).

### 8.2 Messages

Each frame holds one message: canonical JSON with a key `t`.

| `t` | keys |
|---|---|
| `hello` | `protocolVersion` (1), `deviceId`, `appVersion`, `groupId`, `vector`, `wallMillis`, `maxChunk` |
| `ops` | `ops`: 1–64 op envelopes, and the frame plaintext is at most 256 KiB |
| `done` | `vector` |

A receiver ignores keys it does not know and closes on a message with an unknown `t`.
`maxChunk` is the largest BLE chunk the sender accepts (§10.2.5). LAN sends `0` and ignores the peer value.

### 8.3 Session

1. Both sides send `hello` as their first frame. If `groupId` differs from the group of the tag, close.
   If `wallMillis` is more than 24 h ahead of the local clock, show the clock-skew warning.
2. Each side sends every op that the peer lacks (§7) in `ops` messages, then sends `done`.
3. For each received op the receiver checks the envelope (vector `received_op.json`):
   - the keys are exactly `v, group, author, seq, hlc, type, body, hash`;
   - `v` is an integer ≥ 1; `author` is an id; `seq` is an integer ≥ 1; `hlc` parses and its device part
     equals `author`; `type` is a string; `body` is an object; `hash` is 64 lowercase hex;
   - `group` equals the session group, else drop the op (`group`);
   - a shape error drops the op (`shape`);
   - `hash` must equal the computed hash, else **close the session** (`hash`): the peer is broken.
   Unknown `v` or `type` is `ok`: store and relay it.
4. Store the accepted ops of one `ops` message in one transaction. An `(author, seq)` that exists with the
   same hash is skipped. One with a different hash is not stored; record a fork.
5. Merge each stored op's `hlc` into the local clock (§3).
6. When a side has sent `done` and received `done`, the session is complete. The initiator closes.
7. Two sessions for one peer pair and one group: after both `hello`s, the side with the higher `deviceId`
   closes the newer connection.

## 9. Invite link

`tripsplit://join?g=<groupId>#k=<base64url(groupKey) without padding>`

The parser accepts exactly this form: lowercase id, 43 base64url characters, a key of 32 bytes.
Anything else is invalid. Vectors: `invite.json`.

## 10. Transports

The nearby transports are Wi-Fi LAN (§10.1) and BLE (§10.2). The internet relay (§10.4) is different: it
stores encrypted batches and does not carry the session.

Wi-Fi LAN and BLE carry the same messages: the two handshake records (§8.1), then frames. LAN sends
them as a plain TCP byte stream. BLE sends each record and each frame as a **message** split into
chunks (§10.2.4). Everything above the transport (§8) is identical.

A device that holds several groups runs one session per connection. The group of a connection is
chosen by the discovery tag in the handshake record.

### 10.1 Wi-Fi LAN

#### 10.1.1 Advertise and listen

- A device that has the sync screen open listens on TCP. It first tries to bind port **47831**. If that
  port is busy, it binds any free port.
- It publishes DNS-SD service type `_tripsplit._tcp` (domain `local.`) on the port it bound.
- The instance name is `TripSplit-` followed by the first 8 hex characters of its `deviceId`. If the
  name is taken, the platform appends its own suffix. The name carries no meaning.
- The TXT record holds exactly these keys:

| key | value |
|---|---|
| `v` | `1` (the highest protocol version the device speaks) |
| `d` | the `deviceId` |
| `t` | the discovery tags (§8.1) of the groups the device holds, as 24 lowercase hex characters each, joined by `,`. At most 8 tags. The value is at most 199 bytes. |

A device that holds more than 8 groups publishes the 8 groups it used last. A reader ignores TXT keys
it does not know.

#### 10.1.2 Discover and connect

1. A device browses for `_tripsplit._tcp` while the sync screen is open.
2. It ignores its own `deviceId`. It ignores a service whose `t` value shares no tag with a local
   group.
3. For each shared tag it may open one TCP connection to the resolved address and port. The connecting
   side is the **initiator**. Both devices may connect to each other. §8.3 step 7 closes the
   duplicate.
4. **Fallback:** if no service appears in 5 seconds, the device tries TCP port 47831 on the default
   gateway address. On success the session starts as usual. The device repeats the attempt every 15
   seconds while the sync screen is open and no peer is known.
5. The initiator sets `TCP_NODELAY`. Both sides use it for all sockets.

#### 10.1.3 Who writes first

- The initiator writes its 31-byte record immediately after the TCP connection opens.
- The responder reads 31 bytes, checks them (§8.1 step 3), then writes its own record.
- When a side has read the other record, it derives the session key and writes its `hello` frame. It
  does not wait for the `hello` of the other side.
- Records and frames are written back to back. No padding. No chunk header. Frames use the `u32` length
  prefix of §8.1.

#### 10.1.4 Timeouts and close

| event | limit | action |
|---|---|---|
| TCP connect | 5 s | give up on this attempt |
| responder record, after the initiator wrote its record | 5 s | close |
| `hello` of the peer, after both records | 10 s | close |
| no byte received while the session is open | 30 s | close |
| whole session | 10 min | close |

- When both `done` messages are sent and received, the initiator closes the TCP connection. The
  responder closes when it reads the end of the stream. If the responder has not seen it after 5 s, it
  closes.
- A protocol error (§8.1 step 9, §8.3 step 3 `hash`, an unknown message `t`, a bad `ops` size) closes the
  TCP connection at once. The device sends nothing first.
- After any session with a peer, the device waits 30 s before it syncs with that peer again (§8.3), unless
  a new local op exists.

### 10.2 Bluetooth Low Energy

#### 10.2.1 GATT service

All UUIDs are fixed. They are 128-bit values. Compare them without regard to case.

| name | UUID | properties |
|---|---|---|
| TripSplit service | `717ed7c0-0ed4-4251-a5b1-606fa1771f42` | primary service |
| `rx` | `33f56749-2c8f-4bb9-9d1b-3b27fa731a8f` | **write** (with response). Central → peripheral. |
| `tx` | `bd1e5af2-f0f9-49f7-9128-63c490a78986` | **notify**. Peripheral → central. Has a CCCD. |
| `info` | `c9698bc0-ddbd-4f16-8bd3-9c7d34135fba` | **read**. Static for the whole advertise period. |

- The advertisement holds the service UUID. It holds no other TripSplit data.
- `rx` accepts only write with response. A peripheral must not offer write without response.
- No pairing. No bonding. No encryption at the link layer. §8.1 protects the data.
- A peripheral must not notify on `tx` before the central has enabled notifications. The central must
  enable notifications before it writes to `rx`.

#### 10.2.2 The `info` characteristic

The value is canonical JSON (§2), UTF-8, at most 512 bytes. A reader may need ATT long reads.

```json
{"deviceId":"<id>","maxChunk":185,"protocolVersion":1,"roles":["central","peripheral"],"tags":["<24 hex>"]}
```

| key | value |
|---|---|
| `protocolVersion` | integer, highest version the device speaks |
| `deviceId` | the `deviceId` |
| `maxChunk` | integer 20–512: the largest chunk the device accepts on `rx` (§10.2.5) |
| `roles` | array, sorted, a subset of `"central"`, `"peripheral"`. It names the roles the device can use now. A peripheral always lists `"peripheral"`. |
| `tags` | the discovery tags, 24 lowercase hex characters each, at most 8, same order rule as §10.1.1 |

A reader ignores keys it does not know. A reader that cannot parse `info` disconnects.

#### 10.2.3 Roles and connection setup

- While the sync screen is open, a device scans for the service UUID. It also advertises and serves the
  GATT service when its hardware allows (`roles` lists `"peripheral"`). A device that cannot advertise
  is central only.
- The **central** is the initiator (§8.1). Only the central starts a session. The peripheral is the
  responder.
- Setup order for the central:
  1. Connect. Limit: 10 s.
  2. Request MTU 512 (Android). iOS negotiates alone.
  3. Discover the TripSplit service and its three characteristics.
  4. Read `info`.
  5. Enable notifications on `tx`.
  6. Decide (below). If it continues, write the handshake record (§10.2.4) to `rx`.
  Steps 2 to 5 take at most 10 s together. On a timeout the central disconnects.
- The central disconnects at step 6 when `info.tags` shares no tag with a local group, or when
  `info.protocolVersion` is lower than 1.
- **Who is central when both can be:** if `info.roles` contains `"central"` and the local `deviceId` is
  greater than `info.deviceId` (byte order), the local device is **not** the central for this pair. It
  disconnects at step 6 and expects the peer to connect to it. If no session from that peer starts in 15 s,
  the local device connects again and acts as central. In every other case it acts as central.
- A peripheral serves any number of centrals. Each connection has its own chunk counters and its own
  session.

#### 10.2.4 Messages and chunks

BLE carries a sequence of **messages** in each direction. A message is one handshake record (31 bytes) or
one frame (§8.1, with its `u32` length prefix). A message is never empty.

A **chunk** is one ATT value (a write to `rx` or a notification on `tx`):

```
byte 0       flags   bit 0 (0x01) FIRST: this chunk starts a message
                     bit 1 (0x02) LAST:  this chunk ends a message
                     bit 2 (0x04) ABORT: the sender closes the session
                     bits 3-7: must be 0
bytes 1-2    seq     u16 big-endian
bytes 3...   payload message bytes
```

- A message of `n` bytes at chunk size `c` uses `ceil(n / (c − 3))` chunks. The payload of every chunk
  except the last holds exactly `c − 3` bytes. The first chunk has FIRST. The last chunk has LAST. A
  message that fits in one chunk has both flags (`0x03`).
- `seq` is a counter for each direction and each connection. The first chunk has `seq` 0. Each chunk
  (also an ABORT chunk) adds 1, modulo 65536. The counter does not restart at a new message.
- An **ABORT** chunk has flags `0x04` and an empty payload. A side sends it to tell the peer to close. It
  matters most for a peripheral: iOS cannot drop a central. After an ABORT, the sender sends nothing more
  and ignores writes.

**Reassembly.** The receiver keeps `expectedSeq` (starts at 0) and a buffer. For each chunk it checks, in
this order:

| # | check | error code |
|---|---|---|
| 1 | length is at least 3 | `short_chunk` |
| 2 | length is at most the receive limit (`maxChunk` of the receiver) | `too_large` |
| 3 | bits 3-7 are 0, and ABORT is not set with FIRST, LAST or a payload | `bad_flags` |
| 4 | `seq == expectedSeq` | `bad_seq` |
| 5 | ABORT is not set | `abort` |
| 6 | payload is not empty | `empty_chunk` |
| 7 | FIRST is set exactly when the buffer is empty | `unexpected_first` (FIRST while a message is open) or `unexpected_continuation` (no FIRST while no message is open) |
| 8 | the buffer, with this payload, is at most 1 MiB + 4 bytes | `message_too_big` |

If a check fails, the receiver stops and closes the link. Else it adds the payload to the buffer and sets
`expectedSeq = (seq + 1) mod 65536`. If LAST is set, the buffer is a complete message: the receiver
passes it on and empties the buffer.

After reassembly, the receiver checks a frame as in §8.1 step 9. A frame message whose size is not
`4 + length` closes the session. A handshake record must be exactly 31 bytes.

Vectors: `ble_chunk.json` (chunk lists for several chunk sizes, `seq` wrap, and one case for each error
code).

#### 10.2.5 Chunk size

- `chunkSize` means the full ATT value: header and payload. It is at least **20**, at most **512**, and
  for one link it is `max(20, min(512, ATT MTU − 3))`.
- The sizes are **per direction**. The sender of a direction uses
  `min(own limit, peer limit)`:

| direction | own limit | peer limit |
|---|---|---|
| central → peripheral (`rx`) | iOS: `maximumWriteValueLength(for: .withResponse)`. Android: `negotiatedMtu − 3`. | `info.maxChunk` of the peripheral |
| peripheral → central (`tx`) | iOS: `central.maximumUpdateValueLength`. Android: `serverMtu − 3`. | `hello.maxChunk` of the central |

- The peripheral sets `info.maxChunk` to the largest chunk it accepts on `rx`. The central sets
  `hello.maxChunk` to the largest chunk it accepts on `tx`. LAN sets `hello.maxChunk` to `0`.
- Until the peripheral has read the `hello` of the central, it uses chunk size **20** on `tx`. The
  peripheral record is the only message this affects. Both sides use their `chunkSize` from then on.
- A receiver closes the link when a chunk is longer than its own limit (`too_large`).

#### 10.2.6 Flow control

- The central writes the next chunk only after the write response of the last one.
- The peripheral sends the next notification only after the platform says it can. On iOS that is when
  `updateValue` returns true, or after `peripheralManagerIsReady(toUpdateSubscribers:)`. On Android it
  is after `onNotificationSent`.
- A side sends the chunks of one message without another message in between.
- Both directions run at the same time. The sender never waits for the peer to read a message.

#### 10.2.7 Timeouts and close

| event | limit | action |
|---|---|---|
| connect | 10 s | disconnect |
| setup steps 2 to 5 | 10 s in all | disconnect |
| responder record, after the central wrote its record | 10 s | central disconnects |
| `hello` of the peer, after both records | 15 s | close |
| no chunk received while the session is open | 30 s | close |
| unfinished message (a chunk with FIRST and no LAST) | 15 s | close |
| whole session | 10 min | close |
| a pair finishes a session | wait 30 s before the next one with that peer | |

- **Close** means: the central disconnects the GATT link. A peripheral cannot always disconnect. It sends
  an ABORT chunk (best effort), sends nothing more, ignores writes, and waits for the central to
  disconnect.
- Every error in §10.2.4 and every protocol error in §10.1.4 closes the session.
- When both `done` messages are sent and received, the central disconnects (§8.3 step 6).
- A central that is not sure if the peripheral still lives (a write fails or times out) disconnects and
  starts again later. No data is lost: the next session sends what is missing.
- The sync screen keeps the screen on (`docs/PLAN.md` §9.3). iOS advertises only in the foreground.

### 10.3 Privacy

The discovery tag (§8.1) is a stable identifier of the group. Anyone on the same network, or in BLE
range, can read it in the DNS-SD TXT record (§10.1.1) and in the `info` characteristic (§10.2.2). An
observer can use it to link the devices of one group across places and time. The tag reveals nothing
about the content of the group: it does not give the key, the name or the ops. Version 1 keeps the
static tag. Rotating tags are listed in `docs/PLAN.md` §16.

### 10.4 Internet relay

The relay is a third transport for phones that are not near each other. It is a mailbox server: it
stores encrypted op batches for each group and gives them back. It never holds the group key, so it
cannot read or change the ops. It does not run the session of §8; the phones talk to it over HTTPS.
The reference relay is `protocol/tools/relay.py`. The production relay is the Cloudflare Worker in
`relay/`.

#### 10.4.1 Keys and names

All values come from `groupKey`. They are different from the discovery tag, so a person who sees the
tag on a network cannot find the mailbox.

- `mailboxId = lowercase hex(HMAC-SHA256(key = groupKey, msg = "tripsplit/v1/relay/mailbox")[0..16])`
  (32 characters).
- `relayToken = lowercase hex(HMAC-SHA256(key = groupKey, msg = "tripsplit/v1/relay/token"))`
  (64 characters).
- `batchKey = HKDF-SHA256(ikm = groupKey, salt = empty, info = "tripsplit/v1/relay/batch/" || groupId, L = 32)`.

#### 10.4.2 Batch

- The plaintext is canonical JSON `{"ops":[...]}` with 1–64 op envelopes, at most 256 KiB, in the
  order of §7 (author bytes, then seq).
- `blob = nonce (12 random bytes) || AES-256-GCM(batchKey, nonce, plaintext, aad = mailboxId as ASCII)`
  (ciphertext followed by the 16-byte tag).
- A blob that does not decrypt, or a plaintext that does not parse, is skipped. The client continues
  with the next blob.
- Each op in a decrypted batch goes through §8.3 steps 3–5, with one change: a hash error skips the
  batch, it does not stop the sync.

#### 10.4.3 HTTP API

The base URL is fixed in the app build. Each request sends `Authorization: Bearer <relayToken>`.

| Request | Body | Answer |
|---|---|---|
| `POST /v1/m/<mailboxId>/batches` | the blob, `Content-Type: application/octet-stream`, at most 320 KiB | `201 {"cursor": n}` |
| `GET /v1/m/<mailboxId>/batches?after=<cursor>&limit=<1..100>` | — | `200 {"batches": [{"cursor": n, "blob": "<base64url>"}], "more": true\|false}` |

- `cursor` is an integer that the relay gives to each stored blob. It starts at 1 and increases by 1
  in each mailbox. `after` defaults to 0 and `limit` to 50.
- The first `POST` to an unknown mailbox creates it and stores `SHA-256(relayToken)`. After that, a
  request with a different token gets `403`. A `GET` on an unknown mailbox gets `200` with no batches.
- Other errors: `400` for a bad mailbox id or a bad query, `413` for a body over the limit, `507` when
  the mailbox is full (§10.4.5), `429` for too many requests. The client tries again later on `429`,
  `5xx` and network errors, with a back-off from 5 s up to 5 min.

Details that the rules above leave open (the reference relay and `relay.json` fix them):

- The relay stores `SHA-256` of the 64 ASCII characters of `relayToken`, as lowercase hex.
- A request with no token, or a token that is not 64 lowercase hex characters, gets `403`. A `GET` on an
  unknown mailbox is the one exception: it gets `200` with no batches, with any token.
- Order of checks for `POST`: mailbox id (`400`), token (`403`), body size (`413`), empty body (`400`),
  mailbox full (`507`). The relay creates a mailbox only when it stores a blob.
- A `limit` or `after` that is not a plain non-negative integer gets `400`. `limit` is 1–100.
- `more` is true when the mailbox holds a blob after the last blob of the page.
- `Content-Length` over 320 KiB gets `413` before the relay reads the body.

#### 10.4.4 Sync with the relay

The client keeps, for each group, `relayCursor` (the highest cursor it has read, starts at 0) and
`relayVector`. `relayVector[author]` is the highest `n` such that the relay holds seq `1..n` of that
author, as far as the client knows from the batches it read or sent. It starts empty.

1. **Pull.** Read pages with `after = relayCursor` until `more` is false. For each blob, apply §10.4.2,
   add its ops to `relayVector`, and set `relayCursor` to its cursor. Store the
   cursor and the vector in the same transaction as the ops.
2. **Push.** Take every op that the device holds with `seq > relayVector[author]` (§7 order). Send them
   in batches. After each `201`, add the ops of that batch to `relayVector`.
3. Pull always runs before push. A new device then sends only what the relay does not have.

The client runs a relay sync when a group screen opens, after the user saves a change, and every
60 s while the app is in the foreground and online. The relay sync and the nearby sessions run side
by side: the op store ignores an op that it already holds (§8.3 step 4).

#### 10.4.5 Limits and retention

- A mailbox holds at most 20 000 blobs or 64 MiB. Then a `POST` gets `507`.
- The relay deletes a mailbox 400 days after its last `POST`.
- The relay may limit the request rate for each IP address and for each mailbox.

#### 10.4.6 Privacy

The relay sees the IP address of each client, the `mailboxId`, the size and the time of each request.
From this it can tell how many devices use a group and when. It cannot read the group name, the
members, the amounts or any other op content. Anyone who has the invite can derive the token and
read the mailbox; this is the same rule as for nearby sync (§10.3).

Vectors: `relay.json` (mailbox id, token, batch key, two blobs with fixed nonces, one tampered blob, and
a pull-then-push run against the reference relay).
