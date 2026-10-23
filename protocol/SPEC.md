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
   - `member.unclaim(M)` by D: if `M.claimedBy == D`, set it to null.
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

Wi-Fi LAN (DNS-SD `_tripsplit._tcp`, TCP port 47831 fallback) and BLE (GATT) carry the same byte
stream: the handshake records, then frames. The transport rules are in `docs/PLAN.md` §9.
