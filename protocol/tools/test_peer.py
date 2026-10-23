#!/usr/bin/env python3
"""Two reference peers talk over a localhost TCP socket and must converge (SPEC §8, §10.1).

Run: python3 protocol/tools/test_peer.py     (exit code 0 = all pass)
"""

from __future__ import annotations

import asyncio
import random
import sys
import tempfile
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import peer  # noqa: E402
import tripsplit_ref as ref  # noqa: E402
import tripsplit_wire as wire  # noqa: E402


async def sync(a: peer.PeerState, b: peer.PeerState, wall=peer.now_ms) -> tuple[dict, dict]:
    """A dials B on a free localhost port. Returns (A's result, B's result)."""
    results: asyncio.Future = asyncio.get_running_loop().create_future()

    async def on_connect(reader, writer):
        try:
            results.set_result(await peer.Session(b, reader, writer, False, wall).run())
        except Exception as e:  # report to the test
            results.set_exception(e)

    server = await asyncio.start_server(on_connect, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    try:
        mine, theirs = await asyncio.gather(peer.connect(a, "127.0.0.1", port, lambda m: None), results,
                                            return_exceptions=True)
    finally:
        server.close()
    for r in (mine, theirs):
        if isinstance(r, BaseException):
            raise r
    return mine, theirs


def same_fold(*states: peer.PeerState) -> None:
    first = ref.canonical(states[0].fold())
    for st in states[1:]:
        assert ref.canonical(st.fold()) == first, "fold differs"
        assert st.vector() == states[0].vector(), "vector differs"
        assert set(st.ops) == set(states[0].ops), "op sets differ"


def joined(owner: peer.PeerState) -> peer.PeerState:
    return peer.join_group(wire.invite_url(owner.group_id, owner.group_key))


def test_join_from_empty():
    async def go():
        a = peer.make_group("Trip", peer.now_ms())
        peer.random_ops(a, random.Random(1), 40, peer.now_ms())
        b = joined(a)
        assert not b.ops
        ra, rb = await sync(b, a)  # the empty side dials
        assert ra["received"] == len(a.ops) and ra["sent"] == 0, ra
        assert rb["sent"] == len(a.ops) and rb["received"] == 0, rb
        same_fold(a, b)
        assert b.fold()["group"]["name"] == "Trip"
    asyncio.run(go())


def test_random_convergence():
    async def go():
        for seed in range(8):
            rng = random.Random(seed)
            a = peer.make_group("Seed %d" % seed, peer.now_ms())
            b = joined(a)
            await sync(b, a)  # B joins
            wall = peer.now_ms()
            peer.random_ops(a, rng, rng.randint(0, 60), wall)
            peer.random_ops(b, rng, rng.randint(0, 60), wall)
            await sync(a, b)
            same_fold(a, b)
            await sync(b, a)  # a second session: nothing left to send
            r1, r2 = await sync(a, b)
            assert r1["sent"] == r1["received"] == r2["sent"] == r2["received"] == 0
    asyncio.run(go())


def test_gossip_through_a_third_peer():
    async def go():
        rng = random.Random(99)
        a = peer.make_group("Gossip", peer.now_ms())
        b, c = joined(a), joined(a)
        await sync(b, a)
        peer.random_ops(a, rng, 20, peer.now_ms())
        peer.random_ops(b, rng, 20, peer.now_ms())
        await sync(a, b)
        await sync(c, b)  # C never meets A
        same_fold(a, b, c)
    asyncio.run(go())


def test_many_ops_span_frames():
    async def go():
        a = peer.make_group("Big", peer.now_ms())
        peer.random_ops(a, random.Random(5), 400, peer.now_ms())  # > 64 ops: several frames
        b = joined(a)
        await sync(b, a)
        same_fold(a, b)
        assert len(b.ops) == 401
    asyncio.run(go())


def test_wrong_key_is_refused():
    async def go():
        a = peer.make_group("Secret", peer.now_ms())
        stranger = peer.PeerState(a.group_id, bytes(32))
        try:
            await sync(stranger, a)
        except peer.SessionError:
            return
        raise AssertionError("a peer with another key must not sync")
    asyncio.run(go())


def test_save_and_load():
    a = peer.make_group("Disk", peer.now_ms())
    peer.random_ops(a, random.Random(3), 15, peer.now_ms())
    with tempfile.TemporaryDirectory() as d:
        path = str(Path(d) / "log.json")
        a.save(path)
        b = peer.PeerState.load(path)
    same_fold(a, b)
    assert (b.device_id, b.group_key, b.hlc.millis, b.hlc.counter) == (a.device_id, a.group_key, a.hlc.millis, a.hlc.counter)


def test_cli_serve_and_connect():
    """The two command-line modes in two processes, with the invite from --make-group."""
    import socket
    import subprocess

    with tempfile.TemporaryDirectory() as d:
        la, lb = str(Path(d) / "a.json"), str(Path(d) / "b.json")
        script = str(Path(__file__).resolve().parent / "peer.py")
        run = lambda *a, **kw: subprocess.Popen([sys.executable, script, *a], stdout=subprocess.PIPE,
                                                stderr=subprocess.PIPE, text=True, **kw)
        made = subprocess.run([sys.executable, script, "--make-group", "CLI", "--log", la],
                              capture_output=True, text=True, check=True)
        invite = made.stdout.strip()
        wire.parse_invite(invite)
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        server = run("serve", "--log", la, "--random-ops", "25", "--seed", "4", "--port", str(port),
                     "--no-mdns", "--timeout", "30")
        try:
            for _ in range(100):  # wait until the port accepts
                with socket.socket() as probe:
                    if probe.connect_ex(("127.0.0.1", port)) == 0:
                        break
                import time; time.sleep(0.1)
            # the probe above opened and closed one connection: the server survives a bad session
            client = run("connect", "127.0.0.1:%d" % port, "--log", lb, "--invite", invite)
            out, err = client.communicate(timeout=30)
            assert client.returncode == 0, err
            sout, serr = server.communicate(timeout=30)
            assert server.returncode == 0, serr
        finally:
            server.kill()
        a, b = peer.PeerState.load(la), peer.PeerState.load(lb)
        same_fold(a, b)
        assert len(b.ops) == 26 and "received 26" in out, out


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
