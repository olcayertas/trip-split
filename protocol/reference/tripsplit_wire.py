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
