#!/usr/bin/env python3
"""Tests for the reference relay and relay client (SPEC §10.4). Exit code 0 = all pass.

Run: python3 protocol/tools/test_relay.py
"""

from __future__ import annotations

import http.client
import json
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import relay  # noqa: E402
import tripsplit_ref as ref  # noqa: E402
import tripsplit_wire as wire  # noqa: E402

V = json.loads((ref.VECTORS / "relay.json").read_text(encoding="utf-8"))
KEY = bytes.fromhex(V["groupKeyHex"])
GROUP = V["group"]
MAILBOX, TOKEN = V["mailboxId"], V["token"]
PATH = "/v1/m/%s/batches" % MAILBOX
AUTH = {"good": "Bearer " + TOKEN, "other": "Bearer " + "f" * 64, "none": None}


def nonce(k: int) -> bytes:
    return bytes(8) + k.to_bytes(4, "big")


def core_transport(core, token=TOKEN):
    return lambda method, path, body: core.handle(method, path, "Bearer " + token, body)


def open_ops(blob):
    return wire.open_batch(wire.relay_batch_key(KEY, GROUP), MAILBOX, blob)


def test_derived_values():
    assert wire.relay_mailbox_id(KEY) == MAILBOX and len(MAILBOX) == 32
    assert wire.relay_token(KEY) == TOKEN and len(TOKEN) == 64
    assert wire.relay_batch_key(KEY, GROUP).hex() == V["batchKeyHex"]
    assert wire.relay_token_hash(TOKEN) == V["tokenHashHex"]
    assert MAILBOX != wire.discovery_tag(KEY).hex()  # the mailbox is not the discovery tag


def test_blobs_open_and_negative_controls():
    bk = wire.relay_batch_key(KEY, GROUP)
    for b in V["blobs"]:
        blob = bytes.fromhex(b["blobHex"])
        assert blob[:12].hex() == b["nonceHex"]
        ops = wire.open_batch(bk, MAILBOX, blob)
        assert ops is not None and ref.canonical({"ops": ops}) == b["plaintext"]
        # negative controls: the same blob must fail with a wrong AAD, a wrong key and a flipped bit
        assert wire.open_batch(bk, V["wrongAadMailboxId"], blob) is None, "a wrong AAD must fail"
        assert wire.open_batch(wire.relay_batch_key(KEY, V["ops"][0]["author"]), MAILBOX, blob) is None
        flipped = bytearray(blob)
        flipped[-1] ^= 1
        assert wire.open_batch(bk, MAILBOX, bytes(flipped)) is None
        assert wire.open_batch(bk, MAILBOX, blob[:20]) is None  # too short
    assert wire.open_batch(bk, MAILBOX, bytes.fromhex(V["tamperedBlobHex"])) is None
    assert wire.open_batch(bk, MAILBOX, bytes.fromhex(V["wrongAadBlobHex"])) is None
    assert wire.open_batch(bk, V["wrongAadMailboxId"], bytes.fromhex(V["wrongAadBlobHex"])) is not None


def replay_scenario(make_transport):
    sc = V["scenario"]
    ops = {"%s:%d" % (o["author"], o["seq"]): o for o in V["ops"]}

    def parse(r):
        author, seq = r.rsplit(":", 1)
        return author, int(seq)

    held = {n: {parse(r): ops[r] for r in refs} for n, refs in sc["initialHeld"].items()}
    posts = [0]
    seen: list = []

    def nxt():
        posts[0] += 1
        return nonce(posts[0])

    clients = {}
    transport = make_transport(seen)
    for step in sc["steps"]:
        name = step["device"]
        if name not in clients:
            clients[name] = wire.RelayClient(GROUP, KEY, held[name], transport, nxt, step["pageLimit"])
        c = clients[name]
        seen.clear()
        assert len(c.ops) == step["heldBefore"]
        assert c.sync() == (step["stored"], step["sent"]), name
        assert (c.cursor, c.vector, len(c.ops)) == (step["relayCursor"], step["relayVector"], step["heldAfter"])
        assert seen == step["requests"], "requests differ for device " + name
    folds = {ref.canonical(ref.fold(GROUP, list(c.ops.values()))) for c in clients.values()}
    assert len(folds) == 1
    assert sorted("%s:%d" % k for k in clients["A"].ops) == sc["endOpRefs"]
    assert ref.fold(GROUP, list(clients["A"].ops.values()))["balances"] == sc["endFoldBalances"]


def record(seen, method, path, body, status, payload):
    entry = {"method": method, "path": path, "status": status}
    if method == "POST":
        entry["bodyHex"] = body.hex()
    entry["response"] = payload
    seen.append(entry)


def test_scenario_in_process():
    core = relay.RelayCore()

    def make(seen):
        def t(method, path, body):
            status, payload = core.handle(method, path, AUTH["good"], body)
            record(seen, method, path, body, status, payload)
            return status, payload
        return t

    replay_scenario(make)
    assert len(core.boxes[MAILBOX].blobs) == V["scenario"]["relayBlobCount"]


def test_scenario_over_http():
    server = relay.serve(relay.RelayCore())
    conn = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=10)

    def make(seen):
        def t(method, path, body):
            conn.request(method, path, body=body, headers={"Authorization": AUTH["good"],
                                                           "Content-Type": "application/octet-stream"})
            r = conn.getresponse()
            payload = json.loads(r.read())
            record(seen, method, path, body, r.status, payload)
            return r.status, payload
        return t

    try:
        replay_scenario(make)
    finally:
        server.shutdown()


def body_of(step):
    return b"\x01" * step["bodyBytes"] if "bodyBytes" in step else None


def test_http_cases_in_process():
    for case in V["http"]:
        core = relay.RelayCore()
        for st in case["steps"]:
            got = core.handle(st["method"], st["path"], AUTH[st["auth"]], body_of(st))
            assert got == (st["status"], st["response"]), (case["name"], st)


def test_http_cases_over_a_socket():
    for case in V["http"]:
        server = relay.serve(relay.RelayCore())
        port = server.server_address[1]
        try:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            for st in case["steps"]:
                headers = {"Authorization": AUTH[st["auth"]]} if AUTH[st["auth"]] else {}
                conn.request(st["method"], st["path"], body=body_of(st), headers=headers)
                r = conn.getresponse()
                assert (r.status, json.loads(r.read())) == (st["status"], st["response"]), (case["name"], st)
                if r.status == 413:  # the server closes the connection after a 413
                    conn.close()
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        finally:
            server.shutdown()


def test_full_mailbox_gets_507():
    t = core_transport(relay.RelayCore(max_blobs=2))
    assert [t("POST", PATH, b"x" * 30)[0] for _ in range(3)] == [201, 201, 507]
    t = core_transport(relay.RelayCore(max_bytes=100))
    assert [t("POST", PATH, b"x" * 60)[0] for _ in range(2)] == [201, 507]
    assert t("GET", PATH, None)[0] == 200  # reading still works


def test_token_hash_is_stored_not_the_token():
    core = relay.RelayCore()
    core_transport(core)("POST", PATH, b"x" * 30)
    box = core.boxes[MAILBOX]
    assert box.token_hash == V["tokenHashHex"] and TOKEN not in vars(box).values()


def test_retention_deletes_after_400_days():
    now = [1000.0]
    core = relay.RelayCore(clock=lambda: now[0])
    t = core_transport(core)
    t("POST", PATH, b"x" * 30)
    now[0] += 399 * 86400
    assert len(t("GET", PATH, None)[1]["batches"]) == 1
    now[0] += 2 * 86400  # 401 days after the last POST
    assert t("GET", PATH, None) == (200, {"batches": [], "more": False})
    assert core_transport(core, "e" * 64)("POST", PATH, b"x" * 30) == (201, {"cursor": 1})  # new owner, cursor 1


def test_rate_limit_gets_429():
    t = core_transport(relay.RelayCore(rate_limit=(3, 60), clock=lambda: 5.0))
    assert [t("GET", PATH, None)[0] for _ in range(5)] == [200, 200, 200, 429, 429]


def test_client_skips_bad_blobs_and_continues():
    t = core_transport(relay.RelayCore())
    bk = wire.relay_batch_key(KEY, GROUP)
    ops = V["ops"]
    t("POST", PATH, bytes.fromhex(V["tamperedBlobHex"]))  # does not decrypt
    t("POST", PATH, b"\x00" * 60)  # garbage
    bad = dict(ops[0])
    bad["hash"] = "0" * 64  # decrypts, but the hash is wrong: the whole batch is skipped
    t("POST", PATH, wire.seal_batch(bk, MAILBOX, [bad, ops[1]]))
    foreign = dict(ops[1])
    foreign["group"] = ops[0]["author"]
    foreign["hash"] = ref.op_hash(foreign)  # a foreign-group op is dropped alone
    t("POST", PATH, wire.seal_batch(bk, MAILBOX, [foreign, ops[0]]))
    store: dict = {}
    c = wire.RelayClient(GROUP, KEY, store, t)
    assert c.pull() == 1 and c.cursor == 4
    assert list(store) == [(ops[0]["author"], ops[0]["seq"])]
    assert c.vector == {ops[0]["author"]: 1}


def test_push_splits_into_batches_of_64():
    author = "aaaaaaaa-0000-4000-8000-000000000001"
    ops = {}
    for seq in range(1, 131):
        op = {"v": 1, "group": GROUP, "author": author, "seq": seq,
              "hlc": ref.hlc_format(1761210000000 + seq, 0, author), "type": "member.rename",
              "body": {"memberId": author, "name": "n%d" % seq}}
        op["hash"] = ref.op_hash(op)
        ops[(author, seq)] = op
    core = relay.RelayCore()
    c = wire.RelayClient(GROUP, KEY, ops, core_transport(core))
    assert c.push() == 130
    assert [len(open_ops(b)) for b in core.boxes[MAILBOX].blobs] == [64, 64, 2]
    assert c.vector == {author: 130} and c.push() == 0


def test_newcomer_pulls_first_and_pushes_nothing():
    ops = {(o["author"], o["seq"]): o for o in V["ops"]}
    core = relay.RelayCore()
    wire.RelayClient(GROUP, KEY, dict(ops), core_transport(core)).sync()
    before = len(core.boxes[MAILBOX].blobs)
    assert wire.RelayClient(GROUP, KEY, {}, core_transport(core)).sync() == (len(ops), 0)
    assert len(core.boxes[MAILBOX].blobs) == before


def main() -> int:
    failed = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("ok    ", name)
            except BaseException:
                failed += 1
                print("FAILED", name)
                traceback.print_exc()
    print("%d failed" % failed if failed else "all passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
