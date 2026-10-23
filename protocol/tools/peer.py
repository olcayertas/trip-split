#!/usr/bin/env python3
"""TripSplit reference LAN peer (SPEC §8 and §10.1) built on the reference model.

Make a group and print its invite:
    peer.py --make-group "Kapadokya" --log a.json
Wait for one peer (advertises _tripsplit._tcp):
    peer.py serve --log a.json
Dial a peer:
    peer.py connect 192.168.1.20:47831 --log b.json --invite 'tripsplit://join?g=...#k=...'

The log file is JSON: deviceId, groupId, groupKey (hex), hlc, ops. It is a test tool: the group key is
stored in clear text. Needs `zeroconf` for `serve` (pip install -r protocol/reference/requirements.txt).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import socket
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "reference"))

import tripsplit_ref as ref  # noqa: E402
import tripsplit_wire as wire  # noqa: E402

LAN_PORT = 47831
CONNECT_TIMEOUT = 5
RECORD_TIMEOUT = 5
HELLO_TIMEOUT = 10
IDLE_TIMEOUT = 30
SESSION_TIMEOUT = 600
CLOSE_WAIT = 5
MAX_OPS_PER_FRAME = 64
MAX_OPS_PLAINTEXT = 256 * 1024
APP_VERSION = "ref-peer/1"


class SessionError(Exception):
    pass


def now_ms() -> int:
    return int(time.time() * 1000)


class PeerState:
    """One device's copy of one group: the op log, the clock and the forks it has seen."""

    def __init__(self, group_id: str, group_key: bytes, device_id: str | None = None):
        self.group_id, self.group_key = group_id, group_key
        self.device_id = device_id or str(uuid.uuid4())
        self.ops: dict[tuple[str, int], dict] = {}
        self.forks: list[tuple[str, int]] = []
        self.hlc = ref.Hlc(self.device_id)
        self.tag = wire.discovery_tag(group_key)
        self.active: set[str] = set()  # remote deviceIds with a running session

    # --- persistence -----------------------------------------------------------------------

    @classmethod
    def load(cls, path: str) -> "PeerState":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        st = cls(data["groupId"], bytes.fromhex(data["groupKey"]), data["deviceId"])
        st.hlc = ref.Hlc(st.device_id, data["hlc"]["millis"], data["hlc"]["counter"])
        for op in data["ops"]:
            st.ops[(op["author"], op["seq"])] = op
        return st

    def save(self, path: str) -> None:
        ops = sorted(self.ops.values(), key=lambda o: (o["author"], o["seq"]))
        data = {"deviceId": self.device_id, "groupId": self.group_id, "groupKey": self.group_key.hex(),
                "hlc": {"millis": self.hlc.millis, "counter": self.hlc.counter}, "ops": ops}
        tmp = path + ".tmp"
        Path(tmp).write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        os.replace(tmp, path)

    # --- the log ---------------------------------------------------------------------------

    def vector(self) -> dict[str, int]:
        return ref.version_vector(list(self.ops.values()))

    def write_op(self, op_type: str, body: dict, wall: int) -> dict:
        author = self.device_id
        seq = self.vector().get(author, 0) + 1
        op = {"v": 1, "group": self.group_id, "author": author, "seq": seq,
              "hlc": self.hlc.send(wall), "type": op_type, "body": body}
        op["hash"] = ref.op_hash(op)
        self.ops[(author, seq)] = op
        return op

    def missing_for(self, peer_vector: dict[str, int]) -> list[dict]:
        out = [o for o in self.ops.values() if o["seq"] > peer_vector.get(o["author"], 0)]
        out.sort(key=lambda o: (o["author"].encode(), o["seq"]))
        return out

    def store(self, ops: list, wall: int) -> dict:
        """SPEC §8.3 steps 3-5 for the ops of one `ops` message. Raises SessionError on a bad hash."""
        stats = {"stored": 0, "skipped": 0, "dropped": 0, "forks": 0, "skew": False}
        for op in ops:
            verdict = wire.check_received_op(op, self.group_id)
            if verdict == "hash":
                raise SessionError("bad op hash")
            if verdict != "ok":
                stats["dropped"] += 1
                continue
            key = (op["author"], op["seq"])
            have = self.ops.get(key)
            if have is not None:
                if have["hash"] == op["hash"]:
                    stats["skipped"] += 1
                else:
                    stats["forks"] += 1
                    self.forks.append(key)
                continue
            self.ops[key] = op
            stats["stored"] += 1
            if self.hlc.receive(op["hlc"], wall):
                stats["skew"] = True
        return stats

    def fold(self) -> dict:
        return ref.fold(self.group_id, list(self.ops.values()))


def make_group(name: str, wall: int, base_currency: str = "EUR", creator_name: str = "Peer") -> PeerState:
    st = PeerState(str(uuid.uuid4()), os.urandom(32))
    st.write_op("group.create", {"name": name, "baseCurrency": base_currency,
                                 "creatorMemberId": str(uuid.uuid4()), "creatorName": creator_name}, wall)
    return st


def join_group(invite: str, device_id: str | None = None) -> PeerState:
    group_id, key = wire.parse_invite(invite)
    return PeerState(group_id, key, device_id)


# --- Random ops (used by --random-ops and by test_peer.py) -------------------------------------


def random_ops(st: PeerState, rng: random.Random, count: int, wall: int) -> int:
    """Append `count` valid random ops. Returns the wall time after the last one."""
    for _ in range(count):
        wall += rng.randint(1, 5000)
        members = [m["id"] for m in st.fold()["members"] if not m["removed"]]
        live = [e["id"] for e in st.fold()["expenses"] if not e["deleted"]]
        roll = rng.random()
        if len(members) < 2 or roll < 0.15:
            st.write_op("member.add", {"memberId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                                       "name": "Member %d" % rng.randint(1, 999)}, wall)
        elif roll < 0.65 or not live:
            st.write_op("expense.add", {"expenseId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                                        "title": "Expense %d" % rng.randint(1, 999), "date": "2026-10-20",
                                        "category": rng.choice(sorted(ref.CATEGORIES)),
                                        "paidBy": rng.choice(members), "money": _money(rng, members)}, wall)
        elif roll < 0.8:
            st.write_op("expense.edit", {"expenseId": rng.choice(live),
                                         "fields": {"title": "Edited %d" % rng.randint(1, 999)}}, wall)
        elif roll < 0.88:
            st.write_op("expense.delete", {"expenseId": rng.choice(live)}, wall)
        elif roll < 0.95:
            st.write_op("member.rename", {"memberId": rng.choice(members), "name": "Renamed %d" % rng.randint(1, 99)}, wall)
        else:
            a, b = rng.sample(members, 2)
            st.write_op("settlement.add", {"settlementId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                                           "fromMember": a, "toMember": b, "amountMinor": rng.randint(1, 5000),
                                           "currency": "EUR", "rate": "1", "date": "2026-10-21"}, wall)
    return wall


def _money(rng: random.Random, members: list[str]) -> dict:
    amount = rng.randint(1, 200000)
    currency, rate = rng.choice([("EUR", "1"), ("USD", "0.92"), ("TRY", "0.0235")])
    chosen = rng.sample(members, rng.randint(1, len(members)))
    mode = rng.choice(["equal", "weights", "exact"])
    if mode == "equal":
        split = {"mode": "equal", "members": chosen}
    elif mode == "weights":
        split = {"mode": "weights", "weights": {m: rng.randint(1, 10) for m in chosen}}
    else:
        cuts = sorted(rng.randint(0, amount) for _ in range(len(chosen) - 1))
        parts = [b - a for a, b in zip([0] + cuts, cuts + [amount])]
        split = {"mode": "exact", "shares": dict(zip(chosen, parts))}
    return {"amountMinor": amount, "currency": currency, "rate": rate, "split": split}


# --- Session (SPEC §8.1, §8.2, §8.3 over the LAN byte stream of §10.1) ---------------------------


class Session:
    def __init__(self, st: PeerState, reader, writer, initiator: bool, wall=now_ms, log=lambda m: None):
        self.st, self.reader, self.writer, self.initiator = st, reader, writer, initiator
        self.wall, self.log = wall, log
        self.sent_ops = self.received_ops = 0
        self.stats = {"stored": 0, "skipped": 0, "dropped": 0, "forks": 0, "skew": False}
        self.remote_id: str | None = None
        self.key = self.my_dir = self.peer_dir = None
        self.send_counter = self.recv_counter = 0

    async def _read(self, n: int, timeout: float) -> bytes:
        try:
            return await asyncio.wait_for(self.reader.readexactly(n), timeout)
        except asyncio.IncompleteReadError:
            raise SessionError("peer closed the connection")
        except asyncio.TimeoutError:
            raise SessionError("timeout")

    async def _handshake(self) -> None:
        salt = os.urandom(wire.SALT_LEN)
        mine = wire.handshake_record(salt, self.st.tag)
        if self.initiator:
            self.writer.write(mine)
            await self.writer.drain()
            theirs = await self._read(wire.RECORD_LEN, RECORD_TIMEOUT)
        else:
            theirs = await self._read(wire.RECORD_LEN, RECORD_TIMEOUT)
        if theirs[:3] != wire.MAGIC or theirs[19:] != self.st.tag:
            raise SessionError("bad magic or unknown group tag")
        if not self.initiator:
            self.writer.write(mine)
            await self.writer.drain()
        peer_salt = theirs[3:19]
        try:
            self.key, self.my_dir = wire.session_key(self.st.group_key, self.st.group_id, salt, peer_salt)
        except ValueError:
            raise SessionError("equal salts")
        self.peer_dir = 0x02 if self.my_dir == 0x01 else 0x01

    async def _send(self, message: dict) -> None:
        frame = wire.seal(self.key, self.my_dir, self.send_counter, ref.canonical_bytes(message))
        self.send_counter += 1
        self.writer.write(frame)
        await self.writer.drain()

    async def _recv(self, timeout: float = IDLE_TIMEOUT) -> dict:
        head = await self._read(4, timeout)
        length = int.from_bytes(head, "big")
        if length > wire.MAX_FRAME:
            raise SessionError("frame too large")
        body = await self._read(length, timeout)
        try:
            plain = wire.open_frame(self.key, self.peer_dir, self.recv_counter, head + body)
        except Exception:
            raise SessionError("frame does not decrypt")
        self.recv_counter += 1
        try:
            message = json.loads(plain)
        except ValueError:
            raise SessionError("frame is not JSON")
        if not isinstance(message, dict):
            raise SessionError("message is not an object")
        return message

    async def _send_ops(self, peer_vector: dict) -> None:
        batch: list = []
        size = 0

        async def flush():
            nonlocal batch, size
            if batch:
                await self._send(wire.ops_message(batch))
                self.sent_ops += len(batch)
                batch, size = [], 0

        for op in self.st.missing_for(peer_vector):
            n = len(ref.canonical_bytes(op))
            if batch and (len(batch) >= MAX_OPS_PER_FRAME or size + n > MAX_OPS_PLAINTEXT - 1024):
                await flush()
            batch.append(op)
            size += n
        await flush()
        await self._send(wire.done(self.st.vector()))

    async def _receive_loop(self) -> None:
        sender = None
        got_hello = got_done = False
        while not got_done:
            message = await self._recv(IDLE_TIMEOUT if got_hello else HELLO_TIMEOUT)
            kind = message.get("t")
            if not got_hello:
                if kind != "hello":
                    raise SessionError("first frame is not hello")
                self._check_hello(message)
                got_hello = True
                sender = asyncio.ensure_future(self._send_ops(message["vector"]))
            elif kind == "ops":
                ops = message.get("ops")
                if not isinstance(ops, list) or not 1 <= len(ops) <= MAX_OPS_PER_FRAME:
                    raise SessionError("bad ops size")
                got = self.st.store(ops, self.wall())
                self.received_ops += got["stored"]
                for k in ("stored", "skipped", "dropped", "forks"):
                    self.stats[k] += got[k]
                self.stats["skew"] = self.stats["skew"] or got["skew"]
            elif kind == "done":
                got_done = True
            else:
                raise SessionError("unknown message %r" % (kind,))
            if sender.done():
                sender.result()  # raises the error of the sending task
        await sender

    def _check_hello(self, m: dict) -> None:
        for key in ("protocolVersion", "deviceId", "groupId", "vector", "wallMillis"):
            if key not in m:
                raise SessionError("hello lacks %s" % key)
        if m["groupId"] != self.st.group_id:
            raise SessionError("hello group differs from the tag group")
        if not isinstance(m["deviceId"], str) or not ref.UUID_RE.match(m["deviceId"]):
            raise SessionError("bad deviceId")
        if not isinstance(m["vector"], dict):
            raise SessionError("bad vector")
        if ref.is_int(m["wallMillis"]) and m["wallMillis"] > self.wall() + ref.MAX_FORWARD_SKEW_MS:
            self.stats["skew"] = True
        self.remote_id = m["deviceId"]
        if self.remote_id in self.st.active and self.st.device_id > self.remote_id:
            raise SessionError("duplicate session (the higher deviceId closes the newer one)")
        self.st.active.add(self.remote_id)

    async def run(self) -> dict:
        try:
            await asyncio.wait_for(self._run(), SESSION_TIMEOUT)
        finally:
            if self.remote_id:
                self.st.active.discard(self.remote_id)
            self.writer.close()
        return {"sent": self.sent_ops, "received": self.received_ops, **self.stats, "remote": self.remote_id}

    async def _run(self) -> None:
        await self._handshake()
        await self._send(wire.hello(self.st.device_id, self.st.group_id, self.st.vector(), self.wall(), 0, APP_VERSION))
        await self._receive_loop()
        if not self.initiator:
            try:  # the initiator closes (SPEC §8.3 step 6)
                await asyncio.wait_for(self.reader.read(1), CLOSE_WAIT)
            except asyncio.TimeoutError:
                pass


# --- Command line ----------------------------------------------------------------------------


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def summary(st: PeerState, result: dict | None) -> str:
    folded = st.fold()
    names = {m["id"]: m["name"] for m in folded["members"]}
    lines = ["device  %s" % st.device_id, "ops     %d held" % len(st.ops)]
    if result:
        lines.append("session sent %d, received %d (skipped %d, dropped %d, forks %d)%s" % (
            result["sent"], result["received"], result["skipped"], result["dropped"], result["forks"],
            ", CLOCK SKEW WARNING" if result["skew"] else ""))
    lines.append("group   %s" % (folded["group"],))
    for member, balance in sorted(folded["balances"].items()):
        lines.append("balance %-14s %+d" % (names.get(member, member), balance))
    for p in folded["suggestedPayments"]:
        lines.append("pay     %s -> %s %d" % (names.get(p["from"]), names.get(p["to"]), p["amountMinor"]))
    return "\n".join(lines)


async def serve(st: PeerState, port: int, mdns: bool, timeout: float, log) -> dict:
    done: asyncio.Future = asyncio.get_running_loop().create_future()

    async def on_connect(reader, writer):
        try:
            result = await Session(st, reader, writer, False, log=log).run()
            if not done.done():
                done.set_result(result)
        except (SessionError, OSError) as e:
            log("session failed: %s" % e)

    try:
        server = await asyncio.start_server(on_connect, "0.0.0.0", port or LAN_PORT)
    except OSError:
        server = await asyncio.start_server(on_connect, "0.0.0.0", 0)
    bound = server.sockets[0].getsockname()[1]
    az = info = None
    if mdns:
        from zeroconf import ServiceInfo
        from zeroconf.asyncio import AsyncZeroconf
        ip = local_ip()
        props = {"v": "1", "d": st.device_id, "t": st.tag.hex()}
        info = ServiceInfo("_tripsplit._tcp.local.", "TripSplit-%s._tripsplit._tcp.local." % st.device_id[:8],
                           addresses=[socket.inet_aton(ip)], port=bound, properties=props)
        az = AsyncZeroconf()
        await az.async_register_service(info)
    log("listening on port %d%s" % (bound, " and advertising _tripsplit._tcp" if mdns else ""))
    try:
        return await asyncio.wait_for(done, timeout)
    finally:
        server.close()
        if az:
            await az.async_unregister_service(info)
            await az.async_close()


async def connect(st: PeerState, host: str, port: int, log) -> dict:
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), CONNECT_TIMEOUT)
    except asyncio.TimeoutError:
        raise SessionError("connect timeout")
    sock = writer.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return await Session(st, reader, writer, True, log=log).run()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", choices=["serve", "connect"])
    ap.add_argument("target", nargs="?", help="HOST:PORT (connect mode)")
    ap.add_argument("--log", required=True, help="op log JSON file (read if it exists, always written at the end)")
    ap.add_argument("--invite", help="tripsplit://join?g=...#k=... (needed when the log file does not exist)")
    ap.add_argument("--make-group", metavar="NAME", help="create a group, print its invite URL and exit")
    ap.add_argument("--random-ops", type=int, default=0, metavar="N", help="append N random valid ops first")
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--port", type=int, default=0, help="serve: TCP port (default 47831, else any free port)")
    ap.add_argument("--no-mdns", action="store_true", help="serve: do not advertise over DNS-SD")
    ap.add_argument("--timeout", type=float, default=120, help="serve: seconds to wait for a peer")
    args = ap.parse_args(argv)
    log = lambda m: print(m, file=sys.stderr)

    if args.make_group:
        st = make_group(args.make_group, now_ms())
        st.save(args.log)
        print(wire.invite_url(st.group_id, st.group_key))
        return 0
    if os.path.exists(args.log):
        st = PeerState.load(args.log)
        if args.invite and wire.parse_invite(args.invite) != (st.group_id, st.group_key):
            ap.error("--invite does not match the group in the log file")
    elif args.invite:
        st = join_group(args.invite)
    else:
        ap.error("the log file does not exist: pass --invite or use --make-group")
    if args.random_ops:
        random_ops(st, random.Random(args.seed), args.random_ops, now_ms())
    result = None
    code = 0
    try:
        if args.mode == "serve":
            result = asyncio.run(serve(st, args.port, not args.no_mdns, args.timeout, log))
        elif args.mode == "connect":
            if not args.target or ":" not in args.target:
                ap.error("connect needs HOST:PORT")
            host, _, port = args.target.rpartition(":")
            result = asyncio.run(connect(st, host, int(port), log))
    except asyncio.TimeoutError:
        log("session failed: no peer within %g s" % args.timeout)
        code = 1
    except (SessionError, OSError) as e:
        log("session failed: %s" % e)
        code = 1
    st.save(args.log)
    print(summary(st, result))
    return code


if __name__ == "__main__":
    sys.exit(main())
