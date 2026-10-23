#!/usr/bin/env python3
"""TripSplit reference relay: an in-memory mailbox server (SPEC §10.4).

    python3 protocol/tools/relay.py [--port 8787]

`RelayCore.handle` is the whole API as a pure function of the request, so tests need no socket.
`serve` wraps it in a stdlib HTTP server. State lives in memory and is lost when the process ends.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "reference"))

import tripsplit_wire as wire  # noqa: E402

MAX_BLOB = wire.RELAY_MAX_BLOB          # 320 KiB
MAX_BLOBS = 20_000
MAX_BYTES = 64 * 1024 * 1024
RETENTION_SECONDS = 400 * 24 * 3600
DEFAULT_LIMIT, MAX_LIMIT = 50, 100

PATH_RE = re.compile(r"^/v1/m/([^/]+)/batches$")
MAILBOX_RE = re.compile(r"^[0-9a-f]{32}$")
TOKEN_RE = re.compile(r"^[0-9a-f]{64}$")


class Mailbox:
    def __init__(self, token_hash: str, now: float, epoch: str):
        self.token_hash = token_hash
        self.epoch = epoch  # SPEC §10.4.3: new for each life of the mailbox
        self.blobs: list[bytes] = []  # cursor n is blobs[n - 1]
        self.bytes = 0
        self.last_post = now


class RelayCore:
    def __init__(self, max_blobs=MAX_BLOBS, max_bytes=MAX_BYTES, retention=RETENTION_SECONDS,
                 rate_limit: tuple[int, float] | None = None, clock=time.time, epoch_fn=None):
        self.max_blobs, self.max_bytes, self.retention = max_blobs, max_bytes, retention
        self.rate_limit, self.clock = rate_limit, clock
        self.epoch_fn = epoch_fn or (lambda: os.urandom(16).hex())
        self.boxes: dict[str, Mailbox] = {}
        self.hits: dict[str, list[float]] = {}
        self.lock = threading.Lock()

    def _box(self, mailbox_id: str, now: float):
        box = self.boxes.get(mailbox_id)
        if box and now - box.last_post > self.retention:
            del self.boxes[mailbox_id]  # retention: SPEC §10.4.5
            return None
        return box

    def handle(self, method: str, target: str, authorization: str | None, body: bytes | None,
               declared_length: int | None = None) -> tuple[int, dict]:
        """Returns (status, JSON body). `declared_length` is Content-Length when the body is not read yet."""
        with self.lock:
            return self._handle(method, target, authorization, body, declared_length)

    def _handle(self, method, target, authorization, body, declared_length):
        now = self.clock()
        parts = urlsplit(target)
        m = PATH_RE.match(parts.path)
        if not m or method not in ("GET", "POST"):
            return (404 if not m else 405), {"error": "not found" if not m else "method not allowed"}
        mailbox_id = m.group(1)
        if not MAILBOX_RE.match(mailbox_id):
            return 400, {"error": "bad mailbox id"}
        if self.rate_limit:
            count, window = self.rate_limit
            recent = [t for t in self.hits.get(mailbox_id, []) if now - t < window]
            if len(recent) >= count:
                self.hits[mailbox_id] = recent
                return 429, {"error": "too many requests"}
            recent.append(now)
            self.hits[mailbox_id] = recent
        token = None
        if authorization and authorization.startswith("Bearer "):
            token = authorization[7:]
        box = self._box(mailbox_id, now)
        if method == "GET":
            return self._get(box, token, parts.query)
        return self._post(mailbox_id, box, token, body, declared_length, now)

    def _authorised(self, box: Mailbox | None, token: str | None) -> bool:
        if token is None or not TOKEN_RE.match(token):
            return False
        return box is None or hmac.compare_digest(box.token_hash, wire.relay_token_hash(token))

    def _get(self, box, token, query):
        q = parse_qs(query, keep_blank_values=True)
        try:
            after = int(q["after"][0]) if "after" in q else 0
            limit = int(q["limit"][0]) if "limit" in q else DEFAULT_LIMIT
        except ValueError:
            return 400, {"error": "bad query"}
        for key in ("after", "limit"):
            if key in q and not re.fullmatch(r"[0-9]+", q[key][0]):
                return 400, {"error": "bad query"}
        if after < 0 or not 1 <= limit <= MAX_LIMIT:
            return 400, {"error": "bad query"}
        if box is None:
            return 200, {"epoch": None, "batches": [], "more": False}
        if not self._authorised(box, token):
            return 403, {"error": "forbidden"}
        page = box.blobs[after:after + limit]
        return 200, {"epoch": box.epoch, "batches": [{"cursor": after + i + 1, "blob": wire.b64url(b)} for i, b in enumerate(page)],
                     "more": after + len(page) < len(box.blobs)}

    def _post(self, mailbox_id, box, token, body, declared_length, now):
        if not self._authorised(box, token):
            return 403, {"error": "forbidden"}
        size = declared_length if declared_length is not None else len(body or b"")
        if size > MAX_BLOB:
            return 413, {"error": "body too large"}
        if size == 0:
            return 400, {"error": "empty body"}
        if box is not None and (len(box.blobs) >= self.max_blobs or box.bytes + size > self.max_bytes):
            return 507, {"error": "mailbox full"}
        if box is None:
            if size > self.max_bytes:
                return 507, {"error": "mailbox full"}
            box = self.boxes[mailbox_id] = Mailbox(wire.relay_token_hash(token), now, self.epoch_fn())
        box.blobs.append(bytes(body))
        box.bytes += size
        box.last_post = now
        return 201, {"cursor": len(box.blobs), "epoch": box.epoch}


def make_handler(core: RelayCore):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _reply(self, status: int, payload: dict, close: bool = False):
            data = json.dumps(payload, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            if close:
                self.send_header("Connection", "close")
                self.close_connection = True
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            status, payload = core.handle("GET", self.path, self.headers.get("Authorization"), None)
            self._reply(status, payload)

        def do_POST(self):
            length = self.headers.get("Content-Length")
            if length is None or not length.isdigit():
                return self._reply(400, {"error": "Content-Length needed"}, close=True)
            length = int(length)
            auth = self.headers.get("Authorization")
            if length > MAX_BLOB:  # answer without reading the body
                status, payload = core.handle("POST", self.path, auth, None, declared_length=length)
                return self._reply(status, payload, close=True)
            status, payload = core.handle("POST", self.path, auth, self.rfile.read(length))
            self._reply(status, payload)

        def log_message(self, *args):
            pass

    return Handler


def serve(core: RelayCore, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    """Starts the server in a thread and returns it (server.server_address has the port)."""
    server = ThreadingHTTPServer((host, port), make_handler(core))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(RelayCore()))
    print("relay on http://%s:%d" % server.server_address, file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
