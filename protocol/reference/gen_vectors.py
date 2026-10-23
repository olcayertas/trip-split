"""Write protocol/vectors/*.json from the reference model.

Needs: pip install -r protocol/reference/requirements.txt (AES-GCM for crypto.json).

Run:  python3 protocol/reference/gen_vectors.py          (writes the files)
      python3 protocol/reference/gen_vectors.py --check  (fails if a file would change; used by CI)
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

import tripsplit_ref as ref
import tripsplit_wire as wire

# Fixed ids keep the vectors readable. All ids are lowercase UUIDs, as the spec requires.
GROUP = "00000000-0000-4000-8000-000000000001"
DEV_A = "aaaaaaaa-0000-4000-8000-000000000001"
DEV_B = "bbbbbbbb-0000-4000-8000-000000000002"
DEV_C = "cccccccc-0000-4000-8000-000000000003"
M_ALI = "10000000-0000-4000-8000-000000000001"
M_AYSE = "20000000-0000-4000-8000-000000000002"
M_CAN = "30000000-0000-4000-8000-000000000003"
M_DENIZ = "40000000-0000-4000-8000-000000000004"
M_GHOST = "90000000-0000-4000-8000-000000000009"
E1 = "e0000000-0000-4000-8000-000000000001"
E2 = "e0000000-0000-4000-8000-000000000002"
E3 = "e0000000-0000-4000-8000-000000000003"
S1 = "50000000-0000-4000-8000-000000000001"
T0 = 1761210000000  # 2025-10-23T09:00:00Z, a fixed base for HLC millis


class Log:
    """Builds ops with correct per-author seq and hash. `at` is millis after T0."""

    def __init__(self):
        self.ops = []
        self.seq = {}

    def op(self, author, at, op_type, body, *, group=GROUP, v=1, counter=0, seq=None):
        n = self.seq.get(author, 0) + 1 if seq is None else seq
        self.seq[author] = max(self.seq.get(author, 0), n)
        op = {"v": v, "group": group, "author": author, "seq": n,
              "hlc": ref.hlc_format(T0 + at, counter, author), "type": op_type, "body": body}
        op["hash"] = ref.op_hash(op)
        self.ops.append(op)
        return op


def money(amount, currency, rate, split):
    return {"amountMinor": amount, "currency": currency, "rate": rate, "split": split}


def equal(*members):
    return {"mode": "equal", "members": list(members)}


def create(log, at=0, name="Kapadokya 2026", base="EUR"):
    return log.op(DEV_A, at, "group.create", {"name": name, "baseCurrency": base,
                                              "creatorMemberId": M_ALI, "creatorName": "Ali"})


def add_member(log, author, at, member, name):
    return log.op(author, at, "member.add", {"memberId": member, "name": name})


def expense(log, author, at, eid, title, paid_by, m, *, date="2026-10-20", category="food"):
    return log.op(author, at, "expense.add", {"expenseId": eid, "title": title, "date": date,
                                               "category": category, "paidBy": paid_by, "money": m})


# --------------------------------------------------------------------------------------------


def canonical_cases():
    values = [
        {"b": 1, "a": [True, False, None], "c": {"z": "", "y": -42}},
        {"name": "Ayşe Çağlar", "city": "İstanbul ğüşıöç"},
        "quote\" backslash\\ newline\n tab\t ctrl\u0001 del\u007f slash/",
        {"": 1, "\U0001F600": 2, "a": 3},
        [0, 9007199254740991, -9007199254740991],
        {},
        [],
    ]
    log = Log()
    op = create(log)
    cases = [{"input": v, "canonical": ref.canonical(v), "utf8Hex": ref.canonical_bytes(v).hex()} for v in values]
    return {
        "description": "Canonical JSON (SPEC §2) and op hash (SPEC §4.2). Parse `input`, encode it, compare bytes.",
        "cases": cases,
        "opHash": [{"op": {k: v for k, v in op.items() if k != "hash"}, "hash": op["hash"]}],
        "rejected": [
            {"note": "float", "json": "{\"a\":1.5}"},
            {"note": "integer above 2^53-1", "json": "[9007199254740992]"},
        ],
    }


def currency_table():
    return {"description": "ISO 4217 code -> minor-unit exponent. Both apps embed exactly this table.",
            "currencies": ref.CURRENCIES}


def money_cases():
    convert = [
        (1000, "EUR", "34.75", "TRY"),
        (1500, "JPY", "0.0061", "EUR"),
        (1234, "KWD", "3.05", "EUR"),
        (1999, "EUR", "0.33", "KWD"),
        (5, "USD", "0.5", "EUR"),
        (15, "USD", "0.1", "EUR"),
        (25, "USD", "0.1", "EUR"),
        (35, "USD", "0.1", "EUR"),
        (12345, "EUR", "1", "EUR"),
        (100, "EUR", "150.5", "JPY"),
        (1, "JPY", "0.00000001", "EUR"),
        (999999999999, "EUR", "999999999.99999999", "KWD"),
        (1000000, "EUR", "1000", "JPY"),
    ]
    out = []
    for amount, cur, rate, base in convert:
        try:
            result = {"baseMinor": ref.convert(amount, cur, rate, base)}
        except ValueError:
            result = {"error": "outOfRange"}
        out.append({"amountMinor": amount, "currency": cur, "rate": rate, "base": base, **result})
    rates = ["1", "34.75", "0.0061", "01.5", "999999999.99999999", "0", "0.0", "1.", ".5", "-1",
             "1e3", "1.123456789", "1234567890", " 1", "1,5", ""]
    rate_cases = []
    for r in rates:
        try:
            mantissa, scale = ref.parse_rate(r)
            rate_cases.append({"rate": r, "valid": True, "mantissa": str(mantissa), "scale": scale})
        except ValueError:
            rate_cases.append({"rate": r, "valid": False})
    return {"description": "Rate parsing and conversion to base minor units, round half even (SPEC §5). "
                           "`mantissa` is a decimal string because it can exceed 2^53.",
            "convert": out, "rates": rate_cases}


def split_cases():
    specs = [
        (1000, 1000, equal(M_CAN, M_ALI, M_AYSE)),
        (2, 2, equal(M_CAN, M_ALI, M_AYSE)),
        (0, 1, equal(M_ALI, M_AYSE)),
        (1001, 1001, {"mode": "weights", "weights": {M_ALI: 2, M_AYSE: 1, M_CAN: 1}}),
        (100, 100, {"mode": "weights", "weights": {M_CAN: 1, M_AYSE: 1, M_ALI: 1}}),
        (10, 10, {"mode": "weights", "weights": {M_ALI: 3, M_AYSE: 3, M_CAN: 1}}),
        (34750, 1000, {"mode": "exact", "shares": {M_ALI: 333, M_AYSE: 333, M_CAN: 334}}),
        (915, 1500, {"mode": "exact", "shares": {M_ALI: 1500, M_AYSE: 0}}),
        (7, 3, {"mode": "exact", "shares": {M_ALI: 1, M_AYSE: 1, M_CAN: 1}}),
    ]
    cases = [{"baseTotal": t, "amountMinor": a, "split": s, "shares": ref.split_shares(t, a, s)} for t, a, s in specs]
    return {"description": "Split of a base-currency total (SPEC §5.3). Shares always sum to baseTotal.",
            "cases": cases}


def settlement_cases():
    balance_sets = [
        {M_ALI: 3000, M_AYSE: -1000, M_CAN: -2000},
        {M_ALI: 100, M_AYSE: 100, M_CAN: -100, M_DENIZ: -100},
        {M_ALI: 0, M_AYSE: 0},
        {M_ALI: 500, M_AYSE: -200, M_CAN: -200, M_DENIZ: -100},
        {M_DENIZ: 700, M_CAN: -300, M_AYSE: -300, M_ALI: -100},
    ]
    return {"description": "Greedy settlement (SPEC §6).",
            "cases": [{"balances": b, "payments": ref.settle(b)} for b in balance_sets]}


def hlc_cases():
    seqs = []

    def run(name, start, events):
        clock = ref.Hlc(DEV_A, *start)
        steps = []
        for ev in events:
            if ev[0] == "send":
                steps.append({"event": "send", "wall": ev[1], "stamp": clock.send(ev[1])})
            else:
                refused = clock.receive(ev[1], ev[2])
                steps.append({"event": "receive", "remote": ev[1], "wall": ev[2], "refused": refused,
                              "state": ref.hlc_format(clock.millis, clock.counter, DEV_A)})
        seqs.append({"name": name, "device": DEV_A, "startMillis": start[0], "startCounter": start[1],
                     "steps": steps})

    b = lambda ms, c=0: ref.hlc_format(ms, c, DEV_B)
    run("monotonic sends", (0, 0), [("send", T0), ("send", T0), ("send", T0 + 5), ("send", T0 + 5)])
    run("clock steps back", (T0 + 1000, 3), [("send", T0), ("send", T0 + 999), ("send", T0 + 2000)])
    run("receive ahead", (T0, 0), [("receive", b(T0 + 500, 7), T0 + 10), ("send", T0 + 20)])
    run("receive equal millis", (T0, 4), [("receive", b(T0, 9), T0), ("send", T0)])
    run("receive behind", (T0 + 100, 2), [("receive", b(T0, 50), T0 + 50), ("send", T0 + 60)])
    run("wall is newest", (T0, 2), [("receive", b(T0 + 10, 1), T0 + 50), ("send", T0 + 50)])
    run("skew refused", (T0, 0), [("receive", b(T0 + 86_400_001 + 5), T0 + 5), ("send", T0 + 6)])
    run("skew at the limit", (T0, 0), [("receive", b(T0 + 86_400_000 + 5), T0 + 5), ("send", T0 + 6)])
    run("counter overflow", (T0, 0xFFFE), [("send", T0), ("send", T0), ("send", T0)])
    order = [ref.hlc_format(T0, 1, DEV_B), ref.hlc_format(T0, 1, DEV_A), ref.hlc_format(T0 + 1, 0, DEV_A),
             ref.hlc_format(T0, 0xa, DEV_A), ref.hlc_format(T0, 2, DEV_A)]
    return {"description": "Hybrid logical clock (SPEC §3). Stamp format %015d-%04x-<deviceId>; order is byte order.",
            "sequences": seqs, "sortInput": order, "sorted": sorted(order, key=str.encode)}


def fold_cases():
    cases = []

    def case(name, log, note):
        cases.append({"name": name, "note": note, "group": GROUP, "ops": log.ops,
                      "expected": ref.fold(GROUP, log.ops)})

    # 1. A normal day.
    log = Log()
    create(log)
    add_member(log, DEV_A, 10, M_AYSE, "Ayşe")
    add_member(log, DEV_A, 11, M_CAN, "Can")
    log.op(DEV_B, 20, "member.claim", {"memberId": M_AYSE})
    expense(log, DEV_A, 30, E1, "Dinner", M_ALI, money(9000, "EUR", "1", equal(M_ALI, M_AYSE, M_CAN)))
    expense(log, DEV_B, 40, E2, "Balloon tour", M_AYSE,
            money(1000000, "TRY", "0.0285", {"mode": "weights", "weights": {M_ALI: 1, M_AYSE: 2, M_CAN: 1}}),
            category="activities")
    expense(log, DEV_B, 50, E3, "Taxi", M_CAN,
            money(4500, "TRY", "0.0285", {"mode": "exact", "shares": {M_ALI: 1500, M_CAN: 3000}}),
            category="transport")
    log.op(DEV_A, 60, "settlement.add", {"settlementId": S1, "fromMember": M_CAN, "toMember": M_ALI,
                                         "amountMinor": 2000, "currency": "EUR", "rate": "1", "date": "2026-10-21"})
    case("normal day", log, "Members, a claim, three split modes, a foreign currency and a settlement.")

    # 2. Concurrent edits of different field groups both apply; of the money group, the later HLC wins.
    log = Log()
    create(log)
    add_member(log, DEV_A, 10, M_AYSE, "Ayşe")
    expense(log, DEV_A, 20, E1, "Market", M_ALI, money(3000, "EUR", "1", equal(M_ALI, M_AYSE)), category="shopping")
    log.op(DEV_A, 100, "expense.edit", {"expenseId": E1, "fields": {"title": "Market (Göreme)",
                                        "money": money(3200, "EUR", "1", equal(M_ALI, M_AYSE))}})
    log.op(DEV_B, 101, "expense.edit", {"expenseId": E1, "fields": {"money": money(
        3000, "EUR", "1", {"mode": "exact", "shares": {M_ALI: 1000, M_AYSE: 2000}})}})
    log.op(DEV_B, 90, "expense.edit", {"expenseId": E1, "fields": {"paidBy": M_AYSE, "title": "Old title"}})
    case("field groups", log, "Title from A (later), paidBy from B, money group whole from B (latest).")

    # 3. Delete wins over a later edit; an edit before the add in HLC order has no effect.
    log = Log()
    create(log)
    add_member(log, DEV_A, 10, M_AYSE, "Ayşe")
    log.op(DEV_C, 15, "expense.edit", {"expenseId": E2, "fields": {"title": "Too early"}})
    expense(log, DEV_A, 20, E1, "Museum", M_AYSE, money(2400, "EUR", "1", equal(M_ALI, M_AYSE)), category="activities")
    expense(log, DEV_A, 21, E2, "Coffee", M_ALI, money(800, "EUR", "1", equal(M_ALI, M_AYSE)))
    log.op(DEV_B, 30, "expense.delete", {"expenseId": E1})
    log.op(DEV_A, 40, "expense.edit", {"expenseId": E1, "fields": {"title": "Museum + audio guide"}})
    case("delete wins", log, "E1 stays deleted and out of balances; the early edit of E2 is ignored.")

    # 4. Member claims.
    log = Log()
    create(log)
    add_member(log, DEV_A, 1, M_AYSE, "Ayşe")
    add_member(log, DEV_A, 2, M_CAN, "Can")
    add_member(log, DEV_A, 3, M_DENIZ, "Deniz")
    log.op(DEV_C, 11, "member.claim", {"memberId": M_AYSE})
    log.op(DEV_B, 10, "member.claim", {"memberId": M_AYSE})
    log.op(DEV_B, 20, "member.claim", {"memberId": M_CAN})
    log.op(DEV_C, 30, "member.claim", {"memberId": M_AYSE})
    log.op(DEV_A, 40, "member.unclaim", {"memberId": M_CAN})
    log.op(DEV_A, 50, "member.claim", {"memberId": M_DENIZ})
    case("claims", log,
         "B wins Ayşe (lower HLC); B moves to Can, which frees Ayşe for C; A releases Can, which B held; "
         "A moves from Ali to Deniz.")

    # 4b. A restored phone (new deviceId C) takes over the member that its old deviceId A held.
    log = Log()
    create(log)
    log.op(DEV_C, 10, "member.unclaim", {"memberId": M_ALI})
    log.op(DEV_C, 11, "member.claim", {"memberId": M_ALI})
    case("takeover after restore", log,
         "A holds Ali. C releases Ali, then claims Ali. C holds Ali and A holds nothing.")

    # 5. Invalid and unknown ops.
    log = Log()
    create(log)
    log.op(DEV_B, 5, "group.create", {"name": "Second", "baseCurrency": "USD", "creatorMemberId": M_CAN,
                                      "creatorName": "Can"})
    log.op(DEV_A, 10, "member.add", {"memberId": "NOT-A-UUID", "name": "Bad"})
    log.op(DEV_A, 11, "member.add", {"memberId": M_AYSE, "name": ""})
    log.op(DEV_A, 12, "member.add", {"memberId": M_AYSE, "name": "Ayşe"}, group="99999999-0000-4000-8000-000000000009")
    expense(log, DEV_A, 13, E1, "Bad sum", M_ALI,
            money(1000, "EUR", "1", {"mode": "exact", "shares": {M_ALI: 400, M_AYSE: 500}}))
    expense(log, DEV_A, 14, E2, "Bad rate", M_ALI, money(1000, "EUR", "0", equal(M_ALI)))
    log.op(DEV_C, 15, "poll.add", {"question": "Where next?"})
    log.op(DEV_C, 16, "expense.add", {"anything": True}, v=2)
    log.op(DEV_A, 17, "expense.edit", {"expenseId": E1, "fields": {}})
    log.op(DEV_A, 18, "member.add", {"memberId": M_CAN, "name": "Can", "extra": 1})
    case("invalid and unknown", log,
         "Invalid ops stay in the log but have no effect; unknown types and versions are kept for relay.")

    # 6. Expenses that need attention are excluded from balances.
    log = Log()
    create(log)
    add_member(log, DEV_A, 1, M_AYSE, "Ayşe")
    expense(log, DEV_A, 10, E1, "Paid by a ghost", M_GHOST, money(1000, "EUR", "1", equal(M_ALI, M_AYSE)))
    expense(log, DEV_A, 11, E2, "Base with wrong rate", M_ALI, money(1000, "EUR", "2", equal(M_ALI, M_AYSE)))
    expense(log, DEV_A, 12, E3, "Fine", M_AYSE, money(1000, "EUR", "1", equal(M_ALI, M_AYSE)))
    case("needs attention", log, "Only E3 counts in balances.")

    # 7. A joining device that has not received group.create yet.
    log = Log()
    log.op(DEV_A, 1, "member.add", {"memberId": M_AYSE, "name": "Ayşe"}, seq=2)
    expense(log, DEV_A, 10, E1, "Lunch", M_ALI, money(1000, "EUR", "1", equal(M_ALI, M_AYSE)))
    case("no group yet", log, "group is null, balances are empty, the expense needs attention.")

    # 8. A removed member keeps their balance and their claim is released.
    log = Log()
    create(log)
    add_member(log, DEV_A, 1, M_AYSE, "Ayşe")
    log.op(DEV_B, 2, "member.claim", {"memberId": M_AYSE})
    expense(log, DEV_A, 10, E1, "Hotel", M_ALI, money(20000, "EUR", "1", equal(M_ALI, M_AYSE)), category="lodging")
    log.op(DEV_A, 20, "member.remove", {"memberId": M_AYSE})
    log.op(DEV_A, 30, "member.rename", {"memberId": M_AYSE, "name": "Ayşe (left)"})
    log.op(DEV_A, 40, "group.rename", {"name": "Kapadokya ✈ 2026"})
    case("member removed", log, "Ayşe still owes 100.00 EUR; her device no longer holds her.")

    return {"description": "Fold (SPEC §4.4). Fold `ops` for `group`; compare with `expected`. "
                           "Tests also fold the ops in reverse order and must get the same result.",
            "cases": cases}


def session_cases():
    log = Log()
    create(log)
    add_member(log, DEV_A, 1, M_AYSE, "Ayşe")
    expense(log, DEV_B, 2, E1, "Bread", M_AYSE, money(300, "EUR", "1", equal(M_ALI, M_AYSE)))
    expense(log, DEV_B, 3, E2, "Water", M_AYSE, money(200, "EUR", "1", equal(M_ALI, M_AYSE)))
    log.op(DEV_C, 4, "member.add", {"memberId": M_CAN, "name": "Can"})
    ops = log.ops
    a_side = [o for o in ops if not (o["author"] == DEV_B and o["seq"] == 2) and o["author"] != DEV_C]
    b_side = [o for o in ops if o["author"] != DEV_A or o["seq"] == 1]
    va, vb = ref.version_vector(a_side), ref.version_vector(b_side)
    return {"description": "Anti-entropy diff (SPEC §7). Each side sends every op with seq above the "
                           "peer's contiguous vector entry, ordered by author bytes then seq.",
            "ops": ops,
            "cases": [{"sideA": [ref.op_ref(o) for o in a_side], "sideB": [ref.op_ref(o) for o in b_side],
                       "vectorA": va, "vectorB": vb,
                       "aSends": ref.missing_for(vb, a_side), "bSends": ref.missing_for(va, b_side)}]}


def crypto_cases():
    key = bytes(range(32))
    other_key = bytes([0xEE] * 32)
    salt_i = bytes.fromhex("0102030405060708090a0b0c0d0e0f10")
    salt_r = bytes.fromhex("f0e0d0c0b0a090807060504030201000")
    tag = wire.discovery_tag(key)
    k_i, dir_i = wire.session_key(key, GROUP, salt_i, salt_r)
    k_r, dir_r = wire.session_key(key, GROUP, salt_r, salt_i)
    assert k_i == k_r and {dir_i, dir_r} == {1, 2}
    log = Log()
    create(log)
    messages = [
        (dir_i, 0, wire.hello(DEV_A, GROUP, {}, T0, 182)),
        (dir_r, 0, wire.hello(DEV_B, GROUP, {DEV_A: 1}, T0 + 3, 244)),
        (dir_i, 1, wire.ops_message(log.ops)),
        (dir_i, 2, wire.done({DEV_A: 1})),
        (dir_r, 1, wire.done({DEV_A: 1})),
    ]
    frames = []
    for direction, counter, msg in messages:
        plain = ref.canonical_bytes(msg)
        frame = wire.seal(k_i, direction, counter, plain)
        assert wire.open_frame(k_i, direction, counter, frame) == plain
        frames.append({"direction": direction, "counter": counter, "message": msg,
                       "plaintextHex": plain.hex(), "frameHex": frame.hex()})
    hkdf_rfc5869 = wire.hkdf_sha256(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"),
                                    bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)
    assert hkdf_rfc5869.hex().startswith("3cb25f25faacd57a90434f64d0362f2a")  # RFC 5869 test case 1
    tampered = bytearray(bytes.fromhex(frames[0]["frameHex"]))
    tampered[-1] ^= 1
    return {
        "description": "Session crypto (SPEC §8.1). Initiator = connecting side.",
        "hkdfRfc5869Case1": {"ikmHex": "0b" * 22, "saltHex": "000102030405060708090a0b0c",
                             "infoHex": "f0f1f2f3f4f5f6f7f8f9", "length": 42, "okmHex": hkdf_rfc5869.hex()},
        "groupKeyHex": key.hex(), "groupId": GROUP,
        "discoveryTagHex": tag.hex(), "discoveryTagBase64url": wire.b64url(tag),
        "otherGroupDiscoveryTagHex": wire.discovery_tag(other_key).hex(),
        "initiatorSaltHex": salt_i.hex(), "responderSaltHex": salt_r.hex(),
        "initiatorRecordHex": wire.handshake_record(salt_i, tag).hex(),
        "responderRecordHex": wire.handshake_record(salt_r, tag).hex(),
        "sessionKeyHex": k_i.hex(), "initiatorDirection": dir_i, "responderDirection": dir_r,
        "frames": frames,
        "tamperedFrameHex": bytes(tampered).hex(),
    }


def invite_cases():
    key = bytes(range(32))
    good = wire.invite_url(GROUP, key)
    urls = [good,
            good.replace("#k=", "&k="),
            good[:-1],
            good.replace(GROUP, "ABCDEF00-0000-4000-8000-000000000001"),
            "https://example.com/join?g=%s#k=%s" % (GROUP, wire.b64url(key)),
            good + "=",
            good.replace("tripsplit://join?g=", "tripsplit://join?x=")]
    cases = []
    for u in urls:
        try:
            g, k = wire.parse_invite(u)
            cases.append({"url": u, "valid": True, "groupId": g, "keyHex": k.hex()})
        except ValueError:
            cases.append({"url": u, "valid": False})
    return {"description": "Invite link: tripsplit://join?g=<groupId>#k=<base64url key, no padding> (SPEC §9).",
            "cases": cases}


def received_op_cases():
    log = Log()
    create(log)
    good = log.ops[0]
    variants = [("ok", good)]
    bad_hash = dict(good); bad_hash["hash"] = "0" * 64
    variants.append(("hash", bad_hash))
    wrong_group = dict(good); wrong_group["group"] = DEV_C; wrong_group["hash"] = ref.op_hash(wrong_group)
    variants.append(("group", wrong_group))
    extra = dict(good); extra["x"] = 1
    variants.append(("shape", extra))
    other_author = dict(good); other_author["author"] = DEV_B; other_author["hash"] = ref.op_hash(other_author)
    variants.append(("shape", other_author))
    future = dict(good); future["v"] = 2; future["type"] = "poll.add"; future["hash"] = ref.op_hash(future)
    variants.append(("ok", future))
    out = []
    for expected, op in variants:
        assert wire.check_received_op(op, GROUP) == expected, expected
        out.append({"op": op, "result": expected})
    return {"description": "Envelope checks on a received op (SPEC §8.3). 'hash' closes the session; "
                           "'group' and 'shape' drop only the op; unknown v/type is stored and relayed.",
            "group": GROUP, "cases": out}


def ble_chunk_cases():
    import hashlib
    key = bytes(range(32))
    record = wire.handshake_record(bytes(range(16, 32)), wire.discovery_tag(key))
    session_key, _ = wire.session_key(key, GROUP, bytes(16), bytes([1] * 16))
    frame = wire.seal(session_key, 0x01, 0, ref.canonical_bytes({"t": "done", "vector": {DEV_A: 3}}))

    def filler(n):  # fixed pseudo-random bytes
        out, i = b"", 0
        while len(out) < n:
            out += hashlib.sha256(b"ble-chunk/%d" % i).digest()
            i += 1
        return out[:n]

    messages = {"record": record, "smallFrame": frame, "bigFrame": struct.pack(">I", 1196) + filler(1196)}
    sizes = []
    for mtu in (23, 185, 517, 10):
        sizes.append({"mtu": mtu, "chunkSize": wire.chunk_size_for_mtu(mtu)})
    cases = []
    for size in (20, 23, 185, 512):
        for name, msg in messages.items():
            chunks = wire.chunk_message(msg, size)
            r = wire.Reassembler(size)
            out = [r.feed(c) for c in chunks]
            assert out[-1] == msg and all(x is None for x in out[:-1])
            cases.append({"name": name, "chunkSize": size, "message": msg.hex(), "firstSeq": 0,
                          "chunks": [c.hex() for c in chunks]})
    exact = filler(17 * 2)  # fills two chunks of 20 exactly
    one = filler(17)        # fills one chunk of 20 exactly
    for name, msg, count in (("exactlyOneChunk", one, 1), ("exactlyTwoChunks", exact, 2), ("onePlusOneByte", filler(18), 2)):
        chunks = wire.chunk_message(msg, 20)
        assert len(chunks) == count
        cases.append({"name": name, "chunkSize": 20, "message": msg.hex(), "firstSeq": 0,
                      "chunks": [c.hex() for c in chunks]})
    wrap = wire.chunk_message(filler(100), 20, first_seq=65533)
    cases.append({"name": "seqWraps", "chunkSize": 20, "message": filler(100).hex(), "firstSeq": 65533,
                  "chunks": [c.hex() for c in wrap]})

    def c(flags, seq, payload=b""):
        return (bytes([flags]) + struct.pack(">H", seq) + payload).hex()

    p = b"\x01\x02\x03"
    errors = [
        ("short_chunk", 20, [c(3, 0, p)[:4]]),
        ("too_large", 20, [c(3, 0, filler(18))]),
        ("bad_flags", 20, [c(0x0B, 0, p)]),
        ("bad_flags", 20, [c(0x04 | 0x01, 0, b"")]),
        ("bad_flags", 20, [c(0x04, 0, p)]),
        ("bad_seq", 20, [c(1, 0, p), c(2, 2, p)]),
        ("bad_seq", 20, [c(3, 1, p)]),
        ("abort", 20, [c(1, 0, p), c(4, 1)]),
        ("empty_chunk", 20, [c(3, 0, b"")]),
        ("unexpected_first", 20, [c(1, 0, p), c(1, 1, p)]),
        ("unexpected_continuation", 20, [c(2, 0, p)]),
        ("unexpected_continuation", 20, [c(3, 0, p), c(0, 1, p)]),
        ("message_too_big", 512, None),
    ]
    out = []
    for code_, limit, chunks in errors:
        if chunks is None:  # build chunks that pass the 1 MiB + 4 limit
            room = 512 - 3
            n = wire.MAX_MESSAGE // room + 1
            chunks = []
            for i in range(n):
                flags = 1 if i == 0 else 0
                chunks.append(c(flags, i, b"\x00" * room))
            chunks = chunks[:n]
        r = wire.Reassembler(limit)
        got, at = None, None
        for i, ch in enumerate(chunks):
            try:
                r.feed(bytes.fromhex(ch))
            except wire.ReassemblyError as e:
                got, at = e.code, i
                break
        assert got == code_, (code_, got)
        entry = {"error": code_, "receiveLimit": limit, "atChunk": at}
        if len(chunks) > 8:  # the long case: describe it instead of listing 2,000 chunks
            entry.update({"repeat": {"flags": 0, "payloadBytes": 509, "count": len(chunks), "firstFlags": 1}})
        else:
            entry["chunks"] = chunks
        out.append(entry)
    info = wire.info_value(DEV_A, 185, ["peripheral", "central"], [wire.discovery_tag(key)])
    return {"description": "BLE GATT values and chunking (SPEC §10.2). 'chunkSizes' maps MTU to chunk size. "
                           "'cases' split a message into chunks (header: flags u8, seq u16 big-endian). "
                           "'errors' feed chunks to a fresh receiver; it must fail at 'atChunk' with 'error'. "
                           "A 'repeat' entry stands for 'count' chunks: the first has flags 'firstFlags', "
                           "the others flags 'flags', all with 'payloadBytes' zero bytes and seq 0,1,2...",
            "uuids": {"service": wire.SERVICE_UUID, "rx": wire.RX_UUID, "tx": wire.TX_UUID, "info": wire.INFO_UUID},
            "chunkSizes": sizes, "info": {"deviceId": DEV_A, "hex": info.hex(), "text": info.decode()},
            "cases": cases, "errors": out}


def relay_cases():
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
    import relay as relay_mod

    key = bytes(range(32))
    mailbox, token = wire.relay_mailbox_id(key), wire.relay_token(key)
    batch_key = wire.relay_batch_key(key, GROUP)

    # The three devices of the scenario. A and B hold ops; C is an empty newcomer.
    log = Log()
    create(log)
    add_member(log, DEV_A, 1, M_AYSE, "Ayşe")
    expense(log, DEV_A, 2, E1, "Bread", M_AYSE, money(300, "EUR", "1", equal(M_ALI, M_AYSE)))
    log.op(DEV_B, 3, "member.add", {"memberId": M_CAN, "name": "Can"})
    expense(log, DEV_B, 4, E2, "Water", M_CAN, money(200, "EUR", "1", equal(M_ALI, M_CAN)))
    ops = log.ops
    a_ops = [o for o in ops if o["author"] == DEV_A]
    b_ops = [o for o in ops if o["author"] == DEV_B] + [o for o in a_ops if o["seq"] <= 2]

    def nonce(k):  # the k-th POST of the scenario uses this nonce (1-based)
        return bytes(8) + struct.pack(">I", k)

    blob_ops = [a_ops, [o for o in ops if o["author"] == DEV_B]]
    blobs = [{"nonceHex": nonce(i + 1).hex(), "opRefs": [wire_ref(o) for o in b],
              "plaintext": ref.canonical({"ops": b}),
              "blobHex": wire.seal_batch(batch_key, mailbox, b, nonce(i + 1)).hex()}
             for i, b in enumerate(blob_ops)]
    good = bytes.fromhex(blobs[0]["blobHex"])
    tampered = bytearray(good); tampered[20] ^= 0x01
    other_mailbox = "0" * 32
    wrong_aad = wire.seal_batch(batch_key, other_mailbox, a_ops, nonce(1))
    for blob, label in ((good, "good"), (bytes(tampered), "tampered"), (wrong_aad, "wrongAad")):
        assert (wire.open_batch(batch_key, mailbox, blob) is not None) == (label == "good"), label

    core = relay_mod.RelayCore()
    posts = [0]
    current: list = []  # the requests of the step that runs now

    def transport(method, path, body):
        status, payload = core.handle(method, path, "Bearer " + token, body)
        entry = {"method": method, "path": path, "status": status}
        if method == "POST":
            entry["bodyHex"] = body.hex()
        entry["response"] = payload
        current.append(entry)
        return status, payload

    def next_nonce():
        posts[0] += 1
        return nonce(posts[0])

    clients, steps = {}, []
    plan = [("A", a_ops, 50), ("B", b_ops, 50), ("C", [], 1), ("A", None, 50)]
    for name, held, page_limit in plan:
        if held is not None:
            store = {(o["author"], o["seq"]): o for o in held}
            clients[name] = wire.RelayClient(GROUP, key, store, transport, next_nonce, page_limit)
        client = clients[name]
        before = len(client.ops)
        current.clear()
        stored, sent = client.sync()
        steps.append({"device": name, "pageLimit": page_limit, "heldBefore": before,
                      "requests": list(current), "stored": stored, "sent": sent,
                      "relayCursor": client.cursor, "relayVector": client.vector, "heldAfter": len(client.ops)})
    final = [clients[n].ops for n in ("A", "B", "C")]
    folds = [ref.canonical(ref.fold(GROUP, list(st.values()))) for st in final]
    assert len(set(folds)) == 1 and all(len(st) == len(ops) for st in final)
    expected = ref.fold(GROUP, ops)
    return {
        "description": "Internet relay (SPEC §10.4). Values derive from groupKey. A blob is nonce(12) || "
                       "AES-256-GCM(batchKey, nonce, canonical {ops}, aad = mailboxId ASCII). tokenHashHex = "
                       "SHA-256 of the 64 ASCII characters of the token. 'scenario' runs device A, B, C, A "
                       "against an empty relay, one sync (pull, then push) each; the k-th POST of the whole "
                       "scenario uses nonce k (12 bytes: 8 zero bytes, then k as u32 big-endian). 'requests' "
                       "lists what the client must send, in order, with the relay answers. 'http' lists "
                       "single-request checks against a fresh relay.",
        "group": GROUP, "groupKeyHex": key.hex(), "mailboxId": mailbox, "token": token,
        "tokenHashHex": wire.relay_token_hash(token), "batchKeyHex": batch_key.hex(),
        "blobs": blobs,
        "tamperedBlobHex": bytes(tampered).hex(), "wrongAadBlobHex": wrong_aad.hex(), "wrongAadMailboxId": other_mailbox,
        "ops": ops,
        "scenario": {"authorization": "Bearer " + token,
                     "initialHeld": {"A": [wire_ref(o) for o in a_ops], "B": [wire_ref(o) for o in b_ops], "C": []},
                     "steps": steps,
                     "relayBlobCount": len(core.boxes[mailbox].blobs),
                     "endOpRefs": sorted(wire_ref(o) for o in ops), "endFoldBalances": expected["balances"]},
        "http": relay_http_cases(relay_mod, mailbox, token),
    }


def wire_ref(op):
    return "%s:%d" % (op["author"], op["seq"])


def relay_http_cases(relay_mod, mailbox, token):
    other = "f" * 64
    big = relay_mod.MAX_BLOB
    cases = []

    def case(name, steps):
        core = relay_mod.RelayCore()
        out = []
        for st in steps:
            auth = {"good": "Bearer " + token, "other": "Bearer " + other, "none": None}[st.get("auth", "good")]
            body = b"\x01" * st["bodyBytes"] if "bodyBytes" in st else None
            status, payload = core.handle(st["method"], st["path"], auth, body)
            entry = {k: st[k] for k in ("method", "path") if k in st}
            entry["auth"] = st.get("auth", "good")
            if "bodyBytes" in st:
                entry["bodyBytes"] = st["bodyBytes"]
            entry["status"], entry["response"] = status, payload
            out.append(entry)
        cases.append({"name": name, "steps": out})

    base = "/v1/m/%s/batches" % mailbox
    post = lambda **kw: dict(method="POST", path=base, **kw)
    get = lambda q="", **kw: dict(method="GET", path=base + q, **kw)
    case("unknownMailboxGetIsEmpty", [get(), get("?after=5&limit=10", auth="other")])
    case("firstPostCreatesMailboxAndCursorsStartAtOne", [post(bodyBytes=40), post(bodyBytes=41), get()])
    case("otherTokenGets403", [post(bodyBytes=40), post(bodyBytes=40, auth="other"), get(auth="other"),
                               post(bodyBytes=40, auth="none"), get(auth="none")])
    case("firstTokenOwnsTheMailbox", [post(bodyBytes=40, auth="other"), post(bodyBytes=40), get(auth="other")])
    case("badMailboxId", [dict(method="GET", path="/v1/m/XYZ/batches"), dict(method="POST", path="/v1/m/%s/batches" % ("A" * 32), bodyBytes=40)])
    case("badQuery", [get("?after=-1"), get("?after=x"), get("?limit=0"), get("?limit=101"), get("?limit=1.5"), get("?limit=")])
    case("paging", [post(bodyBytes=30), post(bodyBytes=31), post(bodyBytes=32), get("?limit=2"), get("?after=2&limit=2"),
                    get("?after=3"), get("?after=99")])
    case("bodyLimits", [post(bodyBytes=big), post(bodyBytes=big + 1), post(bodyBytes=0), get()])
    return cases


FILES = {
    "canonical_json.json": canonical_cases,
    "currencies.json": currency_table,
    "money.json": money_cases,
    "split.json": split_cases,
    "settlement.json": settlement_cases,
    "hlc.json": hlc_cases,
    "fold.json": fold_cases,
    "session.json": session_cases,
    "crypto.json": crypto_cases,
    "invite.json": invite_cases,
    "received_op.json": received_op_cases,
    "ble_chunk.json": ble_chunk_cases,
    "relay.json": relay_cases,
}


def main():
    check = "--check" in sys.argv
    ref.VECTORS.mkdir(exist_ok=True)
    stale = []
    for name, build in FILES.items():
        text = json.dumps(build(), ensure_ascii=False, indent=2) + "\n"
        path = ref.VECTORS / name
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(name)
        else:
            path.write_text(text, encoding="utf-8")
    if stale:
        print("stale vectors:", ", ".join(stale))
        sys.exit(1)
    print("ok" if check else "wrote %d vector files" % len(FILES))


if __name__ == "__main__":
    main()
