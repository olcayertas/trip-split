"""TripSplit reference model.

This module is the executable form of protocol/SPEC.md. It generates the golden vectors in
protocol/vectors/. The iOS and Android core modules must give the same results. When this file and
SPEC.md disagree, that is a bug in one of them: fix it before you change a vector.

Standard library only.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

VECTORS = Path(__file__).resolve().parent.parent / "vectors"

# --------------------------------------------------------------------------------------------
# Canonical JSON (SPEC §2)
# --------------------------------------------------------------------------------------------

MAX_SAFE_INT = 2**53 - 1


def canonical(value) -> str:
    """RFC 8785 subset: integers only, keys sorted by UTF-16 code units, raw UTF-8."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        if not -MAX_SAFE_INT <= value <= MAX_SAFE_INT:
            raise ValueError("integer out of range")
        return str(value)
    if isinstance(value, float):
        raise ValueError("floats are not allowed")
    if isinstance(value, str):
        return _string(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical(v) for v in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=lambda k: k.encode("utf-16-be"))
        return "{" + ",".join(_string(k) + ":" + canonical(value[k]) for k in keys) + "}"
    raise TypeError(type(value))


_SHORT_ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def _string(s: str) -> str:
    out = ['"']
    for ch in s:
        if ch in _SHORT_ESCAPES:
            out.append(_SHORT_ESCAPES[ch])
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def canonical_bytes(value) -> bytes:
    return canonical(value).encode("utf-8")


def op_hash(op: dict) -> str:
    """SHA-256 hex of the canonical op without its own `hash` field (SPEC §4.2)."""
    body = {k: v for k, v in op.items() if k != "hash"}
    return hashlib.sha256(canonical_bytes(body)).hexdigest()


# --------------------------------------------------------------------------------------------
# Currencies and money (SPEC §5)
# --------------------------------------------------------------------------------------------

CURRENCIES: dict[str, int] = {
    "AED": 2, "ARS": 2, "AUD": 2, "BGN": 2, "BHD": 3, "BRL": 2, "CAD": 2, "CHF": 2, "CLP": 0,
    "CNY": 2, "COP": 2, "CZK": 2, "DKK": 2, "EGP": 2, "EUR": 2, "GBP": 2, "GEL": 2, "HKD": 2,
    "HUF": 2, "IDR": 2, "ILS": 2, "INR": 2, "ISK": 0, "JOD": 3, "JPY": 0, "KRW": 0, "KWD": 3,
    "MAD": 2, "MXN": 2, "MYR": 2, "NOK": 2, "NZD": 2, "OMR": 3, "PHP": 2, "PLN": 2, "QAR": 2,
    "RON": 2, "RSD": 2, "RUB": 2, "SAR": 2, "SEK": 2, "SGD": 2, "THB": 2, "TND": 3, "TRY": 2,
    "TWD": 2, "UAH": 2, "USD": 2, "VND": 0, "ZAR": 2,
}

MAX_AMOUNT_MINOR = 10**12
MAX_BASE_MINOR = 10**15
RATE_RE = re.compile(r"^[0-9]{1,9}(\.[0-9]{1,8})?$")


def parse_rate(rate: str) -> tuple[int, int]:
    """Return (mantissa, scale) so that rate == mantissa / 10**scale. Raises on bad syntax or zero."""
    if not isinstance(rate, str) or not RATE_RE.match(rate):
        raise ValueError("bad rate")
    whole, _, frac = rate.partition(".")
    mantissa = int(whole + frac)
    if mantissa == 0:
        raise ValueError("rate must be greater than zero")
    return mantissa, len(frac)


def div_round_half_even(num: int, den: int) -> int:
    """num >= 0, den > 0."""
    q, r = divmod(num, den)
    twice = 2 * r
    if twice > den or (twice == den and q % 2 == 1):
        q += 1
    return q


def convert(amount_minor: int, currency: str, rate: str, base: str) -> int:
    """Expense minor units -> base minor units. Raises ValueError when the result is out of range."""
    mantissa, scale = parse_rate(rate)
    shift = CURRENCIES[base] - CURRENCIES[currency] - scale
    num = amount_minor * mantissa
    result = num * 10**shift if shift >= 0 else div_round_half_even(num, 10 ** (-shift))
    if result > MAX_BASE_MINOR:
        raise ValueError("converted amount out of range")
    return result


def allocate(total: int, weights: list[tuple[str, int]]) -> dict[str, int]:
    """Largest-remainder allocation (SPEC §5.3). Ties: larger fraction first, then id bytes."""
    w_sum = sum(w for _, w in weights)
    base = {}
    rems = []
    for member, w in weights:
        q, r = divmod(total * w, w_sum)
        base[member] = q
        rems.append((-r, member.encode("utf-8"), member))
    leftover = total - sum(base.values())
    for _, _, member in sorted(rems)[:leftover]:
        base[member] += 1
    return base


def split_shares(base_total: int, amount_minor: int, split: dict) -> dict[str, int]:
    mode = split["mode"]
    if mode == "equal":
        return allocate(base_total, [(m, 1) for m in split["members"]])
    if mode == "weights":
        return allocate(base_total, list(split["weights"].items()))
    if mode == "exact":
        # Shares are in the expense currency; they act as weights over the base total.
        return allocate(base_total, list(split["shares"].items()))
    raise ValueError(mode)


def settle(balances: dict[str, int]) -> list[dict]:
    """Greedy settlement (SPEC §6). Largest debtor pays largest creditor; ties by id bytes."""
    bal = dict(balances)
    out = []
    while True:
        debtors = [(b, m.encode()) for m, b in bal.items() if b < 0]
        creditors = [(-b, m.encode()) for m, b in bal.items() if b > 0]
        if not debtors or not creditors:
            break
        d = min(debtors)[1].decode()
        c = min(creditors)[1].decode()
        amount = min(-bal[d], bal[c])
        out.append({"from": d, "to": c, "amountMinor": amount})
        bal[d] += amount
        bal[c] -= amount
    return out


# --------------------------------------------------------------------------------------------
# Hybrid logical clock (SPEC §3)
# --------------------------------------------------------------------------------------------

MAX_FORWARD_SKEW_MS = 24 * 3600 * 1000
MAX_COUNTER = 0xFFFF


def hlc_format(millis: int, counter: int, device: str) -> str:
    return "%015d-%04x-%s" % (millis, counter, device)


def hlc_parse(s: str) -> tuple[int, int, str]:
    m = re.fullmatch(r"([0-9]{15})-([0-9a-f]{4})-(.+)", s)
    if not m:
        raise ValueError("bad hlc")
    return int(m.group(1)), int(m.group(2), 16), m.group(3)


class Hlc:
    def __init__(self, device: str, millis: int = 0, counter: int = 0):
        self.device, self.millis, self.counter = device, millis, counter

    def _norm(self):
        if self.counter > MAX_COUNTER:
            self.millis += 1
            self.counter = 0

    def send(self, wall: int) -> str:
        if wall > self.millis:
            self.millis, self.counter = wall, 0
        else:
            self.counter += 1
        self._norm()
        return hlc_format(self.millis, self.counter, self.device)

    def receive(self, remote: str, wall: int) -> bool:
        """Returns True when the remote stamp was refused for clock skew."""
        r_millis, r_counter, _ = hlc_parse(remote)
        if r_millis > wall + MAX_FORWARD_SKEW_MS:
            return True
        m = max(self.millis, r_millis, wall)
        if m == self.millis and m == r_millis:
            c = max(self.counter, r_counter) + 1
        elif m == self.millis:
            c = self.counter + 1
        elif m == r_millis:
            c = r_counter + 1
        else:
            c = 0
        self.millis, self.counter = m, c
        self._norm()
        return False


# --------------------------------------------------------------------------------------------
# Validation helpers (SPEC §4.3) — pure functions of the op alone
# --------------------------------------------------------------------------------------------

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
DATE_RE = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
CATEGORIES = {"food", "transport", "lodging", "activities", "shopping", "other"}
MAX_SPLIT_MEMBERS = 100
MAX_WEIGHT = 1000


class Invalid(Exception):
    pass


def need(cond: bool):
    if not cond:
        raise Invalid()


def is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def check_uuid(v):
    need(isinstance(v, str) and UUID_RE.match(v) is not None)


def check_text(v, max_len: int):
    need(isinstance(v, str) and 1 <= len(v) <= max_len)


def check_keys(obj, required: set[str], optional: set[str] = frozenset()):
    need(isinstance(obj, dict))
    keys = set(obj.keys())
    need(required <= keys and keys <= required | optional)


def check_money(m: dict):
    """The money field group: amountMinor, currency, rate, split (SPEC §4.3)."""
    check_keys(m, {"amountMinor", "currency", "rate", "split"})
    amount = m["amountMinor"]
    need(is_int(amount) and 1 <= amount <= MAX_AMOUNT_MINOR)
    need(m["currency"] in CURRENCIES)
    try:
        parse_rate(m["rate"])
    except ValueError:
        raise Invalid()
    split = m["split"]
    need(isinstance(split, dict) and split.get("mode") in {"equal", "exact", "weights"})
    mode = split["mode"]
    if mode == "equal":
        check_keys(split, {"mode", "members"})
        members = split["members"]
        need(isinstance(members, list) and 1 <= len(members) <= MAX_SPLIT_MEMBERS)
        for mem in members:
            check_uuid(mem)
        need(len(set(members)) == len(members))
    elif mode == "weights":
        check_keys(split, {"mode", "weights"})
        weights = split["weights"]
        need(isinstance(weights, dict) and 1 <= len(weights) <= MAX_SPLIT_MEMBERS)
        for mem, w in weights.items():
            check_uuid(mem)
            need(is_int(w) and 1 <= w <= MAX_WEIGHT)
    else:
        check_keys(split, {"mode", "shares"})
        shares = split["shares"]
        need(isinstance(shares, dict) and 1 <= len(shares) <= MAX_SPLIT_MEMBERS)
        for mem, s in shares.items():
            check_uuid(mem)
            need(is_int(s) and 0 <= s <= MAX_AMOUNT_MINOR)
        need(sum(shares.values()) == amount)


def split_members(split: dict) -> list[str]:
    if split["mode"] == "equal":
        return list(split["members"])
    return list(split["weights" if split["mode"] == "weights" else "shares"].keys())


EXPENSE_SCALARS = {
    "title": lambda v: check_text(v, 100),
    "date": lambda v: need(isinstance(v, str) and DATE_RE.match(v) is not None),
    "category": lambda v: need(v in CATEGORIES),
    "paidBy": check_uuid,
}


def check_body(op_type: str, body) -> None:
    need(isinstance(body, dict))
    if op_type == "group.create":
        check_keys(body, {"name", "baseCurrency", "creatorMemberId", "creatorName"})
        check_text(body["name"], 60)
        need(body["baseCurrency"] in CURRENCIES)
        check_uuid(body["creatorMemberId"])
        check_text(body["creatorName"], 60)
    elif op_type == "group.rename":
        check_keys(body, {"name"})
        check_text(body["name"], 60)
    elif op_type in ("member.add", "member.rename"):
        check_keys(body, {"memberId", "name"})
        check_uuid(body["memberId"])
        check_text(body["name"], 60)
    elif op_type in ("member.claim", "member.unclaim", "member.remove"):
        check_keys(body, {"memberId"})
        check_uuid(body["memberId"])
    elif op_type == "expense.add":
        check_keys(body, {"expenseId", "title", "date", "category", "paidBy", "money"})
        check_uuid(body["expenseId"])
        for key, check in EXPENSE_SCALARS.items():
            check(body[key])
        check_money(body["money"])
    elif op_type == "expense.edit":
        check_keys(body, {"expenseId", "fields"})
        check_uuid(body["expenseId"])
        fields = body["fields"]
        need(isinstance(fields, dict) and len(fields) >= 1)
        need(set(fields.keys()) <= set(EXPENSE_SCALARS) | {"money"})
        for key, value in fields.items():
            if key == "money":
                check_money(value)
            else:
                EXPENSE_SCALARS[key](value)
    elif op_type in ("expense.delete",):
        check_keys(body, {"expenseId"})
        check_uuid(body["expenseId"])
    elif op_type == "settlement.add":
        check_keys(body, {"settlementId", "fromMember", "toMember", "amountMinor", "currency", "rate", "date"})
        check_uuid(body["settlementId"])
        check_uuid(body["fromMember"])
        check_uuid(body["toMember"])
        need(body["fromMember"] != body["toMember"])
        need(is_int(body["amountMinor"]) and 1 <= body["amountMinor"] <= MAX_AMOUNT_MINOR)
        need(body["currency"] in CURRENCIES)
        try:
            parse_rate(body["rate"])
        except ValueError:
            raise Invalid()
        EXPENSE_SCALARS["date"](body["date"])
    elif op_type == "settlement.delete":
        check_keys(body, {"settlementId"})
        check_uuid(body["settlementId"])
    else:
        raise AssertionError(op_type)


KNOWN_TYPES = {
    "group.create", "group.rename", "member.add", "member.rename", "member.claim", "member.unclaim",
    "member.remove", "expense.add", "expense.edit", "expense.delete", "settlement.add", "settlement.delete",
}

ENVELOPE_KEYS = {"v", "group", "author", "seq", "hlc", "type", "body", "hash"}


def op_ref(op: dict) -> str:
    return "%s:%d" % (op.get("author"), op.get("seq"))


# --------------------------------------------------------------------------------------------
# Fold (SPEC §4.4)
# --------------------------------------------------------------------------------------------


def fold(group_id: str, ops: list[dict]) -> dict:
    invalid, unknown = [], []
    candidates = []
    for op in ops:
        # Envelope shape. The sync layer only stores ops whose envelope parses, so here the
        # envelope is assumed well formed; only the version, group and type are checked.
        if op.get("v") != 1 or op.get("type") not in KNOWN_TYPES:
            unknown.append(op_ref(op))
            continue
        if op.get("group") != group_id:
            invalid.append(op_ref(op))
            continue
        try:
            check_body(op["type"], op["body"])
        except Invalid:
            invalid.append(op_ref(op))
            continue
        candidates.append(op)

    candidates.sort(key=lambda o: (o["hlc"].encode(), o["author"].encode(), o["seq"]))

    group = None
    members: dict[str, dict] = {}
    claim_of_device: dict[str, str] = {}
    expenses: dict[str, dict] = {}
    settlements: dict[str, dict] = {}

    def claim(member_id: str, device: str) -> bool:
        member = members.get(member_id)
        if member is None or member["removed"] or member["claimedBy"] is not None:
            return False
        previous = claim_of_device.get(device)
        if previous is not None:
            members[previous]["claimedBy"] = None
        member["claimedBy"] = device
        claim_of_device[device] = member_id
        return True

    for op in candidates:
        t, b, author = op["type"], op["body"], op["author"]
        if t == "group.create":
            if group is not None or op["seq"] != 1:
                invalid.append(op_ref(op))
                continue
            group = {"name": b["name"], "baseCurrency": b["baseCurrency"]}
            if b["creatorMemberId"] not in members:
                members[b["creatorMemberId"]] = {"name": b["creatorName"], "removed": False, "claimedBy": None}
            claim(b["creatorMemberId"], author)
        elif t == "group.rename":
            if group is not None:
                group["name"] = b["name"]
        elif t == "member.add":
            if b["memberId"] not in members:
                members[b["memberId"]] = {"name": b["name"], "removed": False, "claimedBy": None}
        elif t == "member.rename":
            if b["memberId"] in members:
                members[b["memberId"]]["name"] = b["name"]
        elif t == "member.claim":
            claim(b["memberId"], author)
        elif t == "member.unclaim":
            member = members.get(b["memberId"])
            if member is not None and member["claimedBy"] == author:
                member["claimedBy"] = None
                del claim_of_device[author]
        elif t == "member.remove":
            member = members.get(b["memberId"])
            if member is not None and not member["removed"]:
                member["removed"] = True
                if member["claimedBy"] is not None:
                    del claim_of_device[member["claimedBy"]]
                    member["claimedBy"] = None
        elif t == "expense.add":
            if b["expenseId"] not in expenses:
                expenses[b["expenseId"]] = {
                    "title": b["title"], "date": b["date"], "category": b["category"],
                    "paidBy": b["paidBy"], "money": b["money"], "deleted": False,
                    "createdBy": author,
                }
        elif t == "expense.edit":
            expense = expenses.get(b["expenseId"])
            if expense is not None and not expense["deleted"]:
                expense.update(b["fields"])
        elif t == "expense.delete":
            if b["expenseId"] in expenses:
                expenses[b["expenseId"]]["deleted"] = True
        elif t == "settlement.add":
            if b["settlementId"] not in settlements:
                s = {k: b[k] for k in ("fromMember", "toMember", "amountMinor", "currency", "rate", "date")}
                s["deleted"] = False
                settlements[b["settlementId"]] = s
        elif t == "settlement.delete":
            if b["settlementId"] in settlements:
                settlements[b["settlementId"]]["deleted"] = True

    # End-of-fold validity and balances (SPEC §4.4, §6).
    balances = {m: 0 for m in members} if group is not None else {}
    base = group["baseCurrency"] if group is not None else None

    def money_base(currency: str, rate: str, amount: int):
        if base is None:
            return None
        if currency == base and rate != "1":
            return None
        try:
            return convert(amount, currency, rate, base)
        except ValueError:
            return None

    expense_out = []
    for eid in sorted(expenses, key=str.encode):
        e = expenses[eid]
        money = e["money"]
        base_amount = money_base(money["currency"], money["rate"], money["amountMinor"])
        involved = [e["paidBy"]] + split_members(money["split"])
        ok = base_amount is not None and all(m in members for m in involved)
        shares = None
        if ok:
            shares = split_shares(base_amount, money["amountMinor"], money["split"])
            if not e["deleted"]:
                balances[e["paidBy"]] += base_amount
                for m, s in shares.items():
                    balances[m] -= s
        expense_out.append({
            "id": eid, "title": e["title"], "date": e["date"], "category": e["category"],
            "paidBy": e["paidBy"], "money": money, "deleted": e["deleted"], "createdBy": e["createdBy"],
            "needsAttention": not ok, "baseAmountMinor": base_amount if ok else None,
            "baseShares": shares,
        })

    settlement_out = []
    for sid in sorted(settlements, key=str.encode):
        s = settlements[sid]
        base_amount = money_base(s["currency"], s["rate"], s["amountMinor"])
        ok = base_amount is not None and s["fromMember"] in members and s["toMember"] in members
        if ok and not s["deleted"]:
            balances[s["fromMember"]] += base_amount
            balances[s["toMember"]] -= base_amount
        settlement_out.append({"id": sid, **s, "needsAttention": not ok,
                               "baseAmountMinor": base_amount if ok else None})

    assert sum(balances.values()) == 0
    return {
        "group": group,
        "members": [{"id": m, **members[m]} for m in sorted(members, key=str.encode)],
        "expenses": expense_out,
        "settlements": settlement_out,
        "balances": balances,
        "suggestedPayments": settle(balances),
        "invalidOps": sorted(invalid),
        "unknownOps": sorted(unknown),
    }


# --------------------------------------------------------------------------------------------
# Version vectors and the anti-entropy diff (SPEC §7)
# --------------------------------------------------------------------------------------------


def version_vector(ops: list[dict]) -> dict[str, int]:
    seqs: dict[str, set[int]] = {}
    for op in ops:
        seqs.setdefault(op["author"], set()).add(op["seq"])
    vector = {}
    for author, s in seqs.items():
        n = 0
        while n + 1 in s:
            n += 1
        if n:
            vector[author] = n
    return vector


def missing_for(peer_vector: dict[str, int], ops: list[dict]) -> list[str]:
    out = [op for op in ops if op["seq"] > peer_vector.get(op["author"], 0)]
    out.sort(key=lambda o: (o["author"].encode(), o["seq"]))
    return [op_ref(o) for o in out]
