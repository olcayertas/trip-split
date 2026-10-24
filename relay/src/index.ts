// TripSplit relay (SPEC section 10.4): a mailbox server for encrypted op batches.
// The Worker checks the mailbox id and routes the request. One SQLite-backed Durable Object for each
// mailbox does everything else. It never sees the group key. It cannot read a blob.
// The reference for the API is protocol/tools/relay.py. The tests replay protocol/vectors/relay.json.

import { DurableObject } from "cloudflare:workers";

export interface Env {
  MAILBOX: DurableObjectNamespace<Mailbox>;
  // Optional overrides (strings). The tests set them. A deployed Worker uses the defaults.
  MAX_BLOBS?: string;
  MAX_BYTES?: string;
  RETENTION_SECONDS?: string;
  RATE_LIMIT?: string;
}

const MAX_BLOB = 320 * 1024;
const MAX_BLOBS = 20_000;
const MAX_BYTES = 64 * 1024 * 1024;
const RETENTION_SECONDS = 400 * 24 * 3600;
const RATE_LIMIT = 600; // requests for each mailbox in 60 s
const RATE_WINDOW_MS = 60_000;
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;

const PATH_RE = /^\/v1\/m\/([^/]+)\/batches$/;
const MAILBOX_RE = /^[0-9a-f]{32}$/;
const TOKEN_RE = /^[0-9a-f]{64}$/;
const DIGITS_RE = /^[0-9]+$/;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function num(value: string | undefined, fallback: number): number {
  const n = value === undefined ? NaN : Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

function base64url(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const m = PATH_RE.exec(url.pathname);
    if (!m) return json(404, { error: "not found" });
    if (request.method !== "GET" && request.method !== "POST") return json(405, { error: "method not allowed" });
    if (!MAILBOX_RE.test(m[1])) return json(400, { error: "bad mailbox id" });
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(m[1]));
    return stub.fetch(request);
  },
} satisfies ExportedHandler<Env>;

export class Mailbox extends DurableObject<Env> {
  private hits: number[] = [];
  private tail: Promise<unknown> = Promise.resolve();

  private sql() {
    const sql = this.ctx.storage.sql;
    sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    sql.exec("CREATE TABLE IF NOT EXISTS batches (cursor INTEGER PRIMARY KEY, blob BLOB NOT NULL)");
    return sql;
  }

  private getMeta(key: string): string | null {
    const rows = [...this.sql().exec("SELECT value FROM meta WHERE key = ?", key)];
    return rows.length ? (rows[0].value as string) : null;
  }

  private setMeta(key: string, value: string | number) {
    this.sql().exec("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, String(value));
  }

  // Retention (SPEC 10.4.5): the mailbox is gone 400 days after the last POST.
  private async expireIfOld(): Promise<void> {
    const last = this.getMeta("lastPost");
    if (last !== null && Date.now() - Number(last) > num(this.env.RETENTION_SECONDS, RETENTION_SECONDS) * 1000) {
      await this.ctx.storage.deleteAll();
    }
  }

  async alarm(): Promise<void> {
    await this.expireIfOld();
  }

  async fetch(request: Request): Promise<Response> {
    const now = Date.now();
    const limit = num(this.env.RATE_LIMIT, RATE_LIMIT);
    this.hits = this.hits.filter((t) => now - t < RATE_WINDOW_MS);
    if (this.hits.length >= limit) return json(429, { error: "too many requests" });
    this.hits.push(now);

    // One request at a time for each mailbox. The hash of the token is async, so without this two first
    // POSTs with different tokens could both pass the owner check.
    const run = this.tail.then(() => this.handle(request, now));
    this.tail = run.catch(() => undefined);
    return run;
  }

  private async handle(request: Request, now: number): Promise<Response> {
    await this.expireIfOld();
    const auth = request.headers.get("Authorization");
    const token = auth !== null && auth.startsWith("Bearer ") ? auth.slice(7) : null;
    return request.method === "GET" ? this.get(new URL(request.url), token) : this.post(request, token, now);
  }

  // The stored value is SHA-256 of the 64 ASCII characters of the token, as lowercase hex.
  private async authorised(token: string | null): Promise<boolean> {
    if (token === null || !TOKEN_RE.test(token)) return false;
    const stored = this.getMeta("tokenHash");
    if (stored === null) return true; // a new mailbox: the first token becomes the owner
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
    const a = new TextEncoder().encode(hex(digest));
    const b = new TextEncoder().encode(stored);
    return a.length === b.length && crypto.subtle.timingSafeEqual(a, b);
  }

  private async get(url: URL, token: string | null): Promise<Response> {
    const q = url.searchParams;
    const afterText = q.get("after");
    const limitText = q.get("limit");
    if ((afterText !== null && !DIGITS_RE.test(afterText)) || (limitText !== null && !DIGITS_RE.test(limitText))) {
      return json(400, { error: "bad query" });
    }
    const after = afterText === null ? 0 : Number(afterText);
    const limit = limitText === null ? DEFAULT_LIMIT : Number(limitText);
    if (limit < 1 || limit > MAX_LIMIT) return json(400, { error: "bad query" });
    const epoch = this.getMeta("epoch");
    if (epoch === null) return json(200, { epoch: null, batches: [], more: false });
    if (!(await this.authorised(token))) return json(403, { error: "forbidden" });
    const sql = this.sql();
    const rows = [...sql.exec("SELECT cursor, blob FROM batches WHERE cursor > ? ORDER BY cursor LIMIT ?", after, limit + 1)];
    const page = rows.slice(0, limit);
    return json(200, {
      epoch,
      batches: page.map((r) => ({ cursor: r.cursor as number, blob: base64url(new Uint8Array(r.blob as ArrayBuffer)) })),
      more: rows.length > limit,
    });
  }

  private async post(request: Request, token: string | null, now: number): Promise<Response> {
    if (!(await this.authorised(token))) return json(403, { error: "forbidden" });
    const declared = request.headers.get("Content-Length");
    if (declared !== null && DIGITS_RE.test(declared) && Number(declared) > MAX_BLOB) {
      return json(413, { error: "body too large" }); // answered before the body is read
    }
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > MAX_BLOB) return json(413, { error: "body too large" });
    if (body.length === 0) return json(400, { error: "empty body" });
    const maxBlobs = num(this.env.MAX_BLOBS, MAX_BLOBS);
    const maxBytes = num(this.env.MAX_BYTES, MAX_BYTES);
    const count = Number(this.getMeta("count") ?? 0);
    const bytes = Number(this.getMeta("bytes") ?? 0);
    const exists = this.getMeta("tokenHash") !== null;
    if ((exists && (count >= maxBlobs || bytes + body.length > maxBytes)) || (!exists && body.length > maxBytes)) {
      return json(507, { error: "mailbox full" });
    }
    // Durable Object code runs one request at a time here, and these writes are synchronous.
    // So the cursor needs no lock. The writes happen in one implicit transaction.
    if (!exists) {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token as string));
      this.setMeta("tokenHash", hex(digest));
      // SPEC 10.4.3: a new epoch for each life of the mailbox, so clients can see a reset.
      this.setMeta("epoch", hex(crypto.getRandomValues(new Uint8Array(16)).buffer));
    }
    const cursor = count + 1;
    this.sql().exec("INSERT INTO batches (cursor, blob) VALUES (?, ?)", cursor, body);
    this.setMeta("count", cursor);
    this.setMeta("bytes", bytes + body.length);
    this.setMeta("lastPost", now);
    await this.ctx.storage.setAlarm(now + num(this.env.RETENTION_SECONDS, RETENTION_SECONDS) * 1000 + 1000);
    return json(201, { cursor, epoch: this.getMeta("epoch") });
  }
}
