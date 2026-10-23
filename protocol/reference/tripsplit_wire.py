"""TripSplit wire reference: invite links, discovery tag, session key, frames (SPEC §8, §9).

Needs the `cryptography` package for AES-GCM (pip install -r protocol/reference/requirements.txt).
HKDF and HMAC use the standard library, so the derivation steps are easy to read.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import tripsplit_ref as ref

MAGIC = b"TS1"
SALT_LEN = 16
TAG_LEN = 12
RECORD_LEN = len(MAGIC) + SALT_LEN + TAG_LEN  # 31
MAX_FRAME = 1024 * 1024
PROTOCOL_VERSION = 1


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64url_decode(text: str) -> bytes:
    if not re.fullmatch(r"[A-Za-z0-9_-]*", text) or len(text) % 4 == 1:
        raise ValueError("bad base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


# --- Invite (SPEC §9) -------------------------------------------------------------------------


def invite_url(group_id: str, key: bytes) -> str:
    return "tripsplit://join?g=%s#k=%s" % (group_id, b64url(key))


def parse_invite(url: str) -> tuple[str, bytes]:
    m = re.fullmatch(r"tripsplit://join\?g=([0-9a-f-]{36})#k=([A-Za-z0-9_-]{43})", url)
    if not m or not ref.UUID_RE.match(m.group(1)):
        raise ValueError("bad invite")
    key = b64url_decode(m.group(2))
    if len(key) != 32:
        raise ValueError("bad key")
    return m.group(1), key


# --- Key derivation (SPEC §8.1) ---------------------------------------------------------------


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def discovery_tag(group_key: bytes) -> bytes:
    return hmac.new(group_key, b"tripsplit/v1/discover", hashlib.sha256).digest()[:TAG_LEN]


def handshake_record(salt: bytes, tag: bytes) -> bytes:
    assert len(salt) == SALT_LEN and len(tag) == TAG_LEN
    return MAGIC + salt + tag


def session_key(group_key: bytes, group_id: str, my_salt: bytes, peer_salt: bytes) -> tuple[bytes, int]:
    """Returns (key, my direction byte). Equal salts are an error: both sides close."""
    if my_salt == peer_salt:
        raise ValueError("equal salts")
    low, high = sorted([my_salt, peer_salt])
    key = hkdf_sha256(group_key, low + high, b"tripsplit/v1/frame/" + group_id.encode())
    return key, 0x01 if my_salt == low else 0x02


def nonce(direction: int, counter: int) -> bytes:
    return bytes([direction, 0, 0, 0]) + struct.pack(">Q", counter)


def seal(key: bytes, direction: int, counter: int, plaintext: bytes) -> bytes:
    sealed = AESGCM(key).encrypt(nonce(direction, counter), plaintext, None)
    return struct.pack(">I", len(sealed)) + sealed


def open_frame(key: bytes, direction: int, counter: int, frame: bytes) -> bytes:
    (length,) = struct.unpack(">I", frame[:4])
    if length > MAX_FRAME or length != len(frame) - 4:
        raise ValueError("bad length")
    return AESGCM(key).decrypt(nonce(direction, counter), frame[4:], None)


# --- Messages (SPEC §8.2) ---------------------------------------------------------------------


def hello(device_id, group_id, vector, wall_millis, max_chunk, app_version="1.0.0"):
    return {"t": "hello", "protocolVersion": PROTOCOL_VERSION, "deviceId": device_id, "appVersion": app_version,
            "groupId": group_id, "vector": vector, "wallMillis": wall_millis, "maxChunk": max_chunk}


def ops_message(ops):
    return {"t": "ops", "ops": ops}


def done(vector):
    return {"t": "done", "vector": vector}


HEX64 = re.compile(r"^[0-9a-f]{64}$")


def check_received_op(op, group_id: str) -> str:
    """Envelope checks a receiver makes before it stores an op (SPEC §8.3).

    Returns "ok", or the reason to drop the op. A bad hash closes the session (the peer is broken);
    the other reasons drop only the op.
    """
    if not isinstance(op, dict):
        return "shape"
    if set(op.keys()) != ref.ENVELOPE_KEYS:
        return "shape"
    if not ref.is_int(op["v"]) or op["v"] < 1:
        return "shape"
    if op["group"] != group_id:
        return "group"
    if not isinstance(op["author"], str) or not ref.UUID_RE.match(op["author"]):
        return "shape"
    if not ref.is_int(op["seq"]) or not 1 <= op["seq"] <= ref.MAX_SAFE_INT:
        return "shape"
    try:
        _, _, device = ref.hlc_parse(op["hlc"]) if isinstance(op["hlc"], str) else (0, 0, None)
    except ValueError:
        return "shape"
    if device != op["author"]:
        return "shape"
    if not isinstance(op["type"], str) or not isinstance(op["body"], dict):
        return "shape"
    if not isinstance(op["hash"], str) or not HEX64.match(op["hash"]):
        return "shape"
    if ref.op_hash(op) != op["hash"]:
        return "hash"
    return "ok"


# --- BLE chunks (SPEC §10.2.4, §10.2.5) -------------------------------------------------------

SERVICE_UUID = "717ed7c0-0ed4-4251-a5b1-606fa1771f42"
RX_UUID = "33f56749-2c8f-4bb9-9d1b-3b27fa731a8f"
TX_UUID = "bd1e5af2-f0f9-49f7-9128-63c490a78986"
INFO_UUID = "c9698bc0-ddbd-4f16-8bd3-9c7d34135fba"

FLAG_FIRST, FLAG_LAST, FLAG_ABORT = 0x01, 0x02, 0x04
CHUNK_HEADER = 3
MIN_CHUNK, MAX_CHUNK = 20, 512
MAX_MESSAGE = MAX_FRAME + 4


def chunk_size_for_mtu(mtu: int) -> int:
    return max(MIN_CHUNK, min(MAX_CHUNK, mtu - 3))


def chunk_message(message: bytes, chunk_size: int, first_seq: int = 0) -> list[bytes]:
    """Split one message into chunks. `seq` runs from first_seq and wraps at 65536."""
    if not message:
        raise ValueError("empty message")
    if not MIN_CHUNK <= chunk_size <= MAX_CHUNK:
        raise ValueError("bad chunk size")
    room = chunk_size - CHUNK_HEADER
    chunks = []
    for start in range(0, len(message), room):
        flags = (FLAG_FIRST if start == 0 else 0) | (FLAG_LAST if start + room >= len(message) else 0)
        seq = (first_seq + len(chunks)) & 0xFFFF
        chunks.append(bytes([flags]) + struct.pack(">H", seq) + message[start:start + room])
    return chunks


def abort_chunk(seq: int) -> bytes:
    return bytes([FLAG_ABORT]) + struct.pack(">H", seq & 0xFFFF)


class ReassemblyError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class Reassembler:
    """Receiver side of SPEC §10.2.4. One instance per direction and connection."""

    def __init__(self, limit: int):
        self.limit = limit
        self.expected = 0
        self.buffer = bytearray()

    def feed(self, chunk: bytes):
        """Returns a complete message, or None. Raises ReassemblyError (the caller closes the link)."""
        if len(chunk) < CHUNK_HEADER:
            raise ReassemblyError("short_chunk")
        if len(chunk) > self.limit:
            raise ReassemblyError("too_large")
        flags, seq, payload = chunk[0], struct.unpack(">H", chunk[1:3])[0], chunk[3:]
        if flags & 0xF8 or (flags & FLAG_ABORT and (flags != FLAG_ABORT or payload)):
            raise ReassemblyError("bad_flags")
        if seq != self.expected:
            raise ReassemblyError("bad_seq")
        if flags & FLAG_ABORT:
            raise ReassemblyError("abort")
        if not payload:
            raise ReassemblyError("empty_chunk")
        first = bool(flags & FLAG_FIRST)
        if first and self.buffer:
            raise ReassemblyError("unexpected_first")
        if not first and not self.buffer:
            raise ReassemblyError("unexpected_continuation")
        if len(self.buffer) + len(payload) > MAX_MESSAGE:
            raise ReassemblyError("message_too_big")
        self.buffer += payload
        self.expected = (seq + 1) & 0xFFFF
        if flags & FLAG_LAST:
            message, self.buffer = bytes(self.buffer), bytearray()
            return message
        return None


def info_value(device_id: str, max_chunk: int, roles: list[str], tags: list[bytes]) -> bytes:
    """The `info` characteristic value (SPEC §10.2.2)."""
    return ref.canonical_bytes({"deviceId": device_id, "maxChunk": max_chunk, "protocolVersion": PROTOCOL_VERSION,
                                "roles": sorted(roles), "tags": [t.hex() for t in tags[:8]]})


# --- Internet relay (SPEC §10.4) ---------------------------------------------------------------

RELAY_MAX_BLOB = 320 * 1024
RELAY_MAX_BATCH_OPS = 64
RELAY_MAX_BATCH_PLAINTEXT = 256 * 1024
RELAY_DEFAULT_LIMIT = 50


def relay_mailbox_id(group_key: bytes) -> str:
    return hmac.new(group_key, b"tripsplit/v1/relay/mailbox", hashlib.sha256).digest()[:16].hex()


def relay_token(group_key: bytes) -> str:
    return hmac.new(group_key, b"tripsplit/v1/relay/token", hashlib.sha256).hexdigest()


def relay_token_hash(token: str) -> str:
    """What the relay stores: SHA-256 of the 64 ASCII characters of the token, in hex."""
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def relay_batch_key(group_key: bytes, group_id: str) -> bytes:
    return hkdf_sha256(group_key, b"", b"tripsplit/v1/relay/batch/" + group_id.encode())


def seal_batch(batch_key: bytes, mailbox_id: str, ops: list, nonce: bytes | None = None) -> bytes:
    """blob = nonce(12) || AES-256-GCM(batchKey, nonce, canonical {"ops":[...]}, aad = mailboxId)."""
    import os
    nonce = os.urandom(12) if nonce is None else nonce
    assert len(nonce) == 12 and 1 <= len(ops) <= RELAY_MAX_BATCH_OPS
    plain = ref.canonical_bytes({"ops": ops})
    assert len(plain) <= RELAY_MAX_BATCH_PLAINTEXT
    return nonce + AESGCM(batch_key).encrypt(nonce, plain, mailbox_id.encode("ascii"))


def open_batch(batch_key: bytes, mailbox_id: str, blob: bytes):
    """Returns the list of ops, or None when the blob must be skipped (SPEC §10.4.2)."""
    if len(blob) < 12 + 16:
        return None
    try:
        plain = AESGCM(batch_key).decrypt(blob[:12], blob[12:], mailbox_id.encode("ascii"))
        data = __import__("json").loads(plain)
    except Exception:
        return None
    if not isinstance(data, dict) or not isinstance(data.get("ops"), list):
        return None
    return data["ops"]


class RelayClient:
    """Pull-then-push relay sync of one group (SPEC §10.4.4).

    `ops` is the device's store: a dict {(author, seq): op}. `transport(method, path, body)` returns
    (status, parsed JSON or None); `path` includes the query. `nonce_fn()` gives 12 bytes per push.
    """

    def __init__(self, group_id, group_key, ops, transport, nonce_fn=None, page_limit=RELAY_DEFAULT_LIMIT):
        self.group_id, self.ops, self.transport = group_id, ops, transport
        self.mailbox = relay_mailbox_id(group_key)
        self.token = relay_token(group_key)
        self.batch_key = relay_batch_key(group_key, group_id)
        self.nonce_fn = nonce_fn
        self.page_limit = page_limit
        self.cursor = 0
        self.on_relay: set[tuple[str, int]] = set()  # (author, seq) the relay is known to hold

    @property
    def vector(self) -> dict[str, int]:
        seqs: dict[str, set[int]] = {}
        for author, seq in self.on_relay:
            seqs.setdefault(author, set()).add(seq)
        out = {}
        for author, s in sorted(seqs.items()):
            n = 0
            while n + 1 in s:
                n += 1
            if n:
                out[author] = n
        return out

    def pull(self) -> int:
        """Reads every page after the cursor. Returns the number of new ops stored."""
        stored = 0
        while True:
            status, body = self.transport(
                "GET", "/v1/m/%s/batches?after=%d&limit=%d" % (self.mailbox, self.cursor, self.page_limit), None)
            if status != 200:
                raise RelayError(status)
            for item in body["batches"]:
                stored += self._apply(b64url_decode(item["blob"]))
                self.cursor = item["cursor"]
            if not body["more"]:
                return stored

    def _apply(self, blob: bytes) -> int:
        ops = open_batch(self.batch_key, self.mailbox, blob)
        if ops is None:
            return 0
        verdicts = [check_received_op(op, self.group_id) for op in ops]
        if "hash" in verdicts:  # a hash error skips the whole batch
            return 0
        n = 0
        for op, verdict in zip(ops, verdicts):
            if verdict != "ok":
                continue
            key = (op["author"], op["seq"])
            self.on_relay.add(key)
            if key not in self.ops:
                self.ops[key] = op
                n += 1
        return n

    def push(self) -> int:
        """Sends every op the relay lacks, in batches. Returns the number of ops sent."""
        vec = self.vector
        todo = [o for o in self.ops.values() if o["seq"] > vec.get(o["author"], 0)]
        todo.sort(key=lambda o: (o["author"].encode(), o["seq"]))
        sent, batch, size = 0, [], 0

        def flush():
            nonlocal sent, batch, size
            if not batch:
                return
            blob = seal_batch(self.batch_key, self.mailbox, batch, self.nonce_fn() if self.nonce_fn else None)
            status, _ = self.transport("POST", "/v1/m/%s/batches" % self.mailbox, blob)
            if status != 201:
                raise RelayError(status)
            self.on_relay.update((o["author"], o["seq"]) for o in batch)
            sent += len(batch)
            batch, size = [], 0

        for op in todo:
            n = len(ref.canonical_bytes(op)) + 1
            if batch and (len(batch) >= RELAY_MAX_BATCH_OPS or size + n > RELAY_MAX_BATCH_PLAINTEXT - 16):
                flush()
            batch.append(op)
            size += n
        flush()
        return sent

    def sync(self) -> tuple[int, int]:
        """Pull, then push. Returns (ops stored, ops sent)."""
        return self.pull(), self.push()


class RelayError(Exception):
    def __init__(self, status: int):
        super().__init__("relay answered %d" % status)
        self.status = status
