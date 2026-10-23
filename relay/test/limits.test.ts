// Limits and retention, with the tiny limits of vitest.config.ts:
// MAX_BLOBS 3, MAX_BYTES 1000, RATE_LIMIT 20 for each 60 s, RETENTION_SECONDS 3600.

import { env, exports } from "cloudflare:workers";
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

const TOKEN = "a".repeat(64);
const OTHER = "b".repeat(64);

function mailboxId(): string {
  return [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function call(m: string, method: string, token: string | null, body?: Uint8Array) {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  return exports.default.fetch(new Request(`https://relay.test/v1/m/${m}/batches`, { method, headers, body }));
}

const blob = (n: number) => new Uint8Array(n).fill(7);

describe("limits", () => {
  it("answers 507 when the mailbox holds MAX_BLOBS blobs, and still serves reads", async () => {
    const m = mailboxId();
    const codes = [];
    for (let i = 0; i < 4; i++) codes.push((await call(m, "POST", TOKEN, blob(10))).status);
    expect(codes).toEqual([201, 201, 201, 507]);
    const res = await call(m, "GET", TOKEN);
    expect(((await res.json()) as any).batches).toHaveLength(3);
  });

  it("answers 507 when the bytes would pass MAX_BYTES", async () => {
    const m = mailboxId();
    expect((await call(m, "POST", TOKEN, blob(600))).status).toBe(201);
    expect((await call(m, "POST", TOKEN, blob(401))).status).toBe(507);
    expect((await call(m, "POST", TOKEN, blob(400))).status).toBe(201);
  });

  it("answers 507 for a first blob that is larger than MAX_BYTES and does not create the mailbox", async () => {
    const m = mailboxId();
    expect((await call(m, "POST", TOKEN, blob(1001))).status).toBe(507);
    expect((await call(m, "POST", OTHER, blob(10))).status).toBe(201); // still unowned, so OTHER can own it
  });

  it("answers 429 after RATE_LIMIT requests in one window", async () => {
    const m = mailboxId();
    const codes = [];
    for (let i = 0; i < 22; i++) codes.push((await call(m, "GET", TOKEN)).status);
    expect(codes.slice(0, 20).every((c) => c === 200)).toBe(true);
    expect(codes.slice(20)).toEqual([429, 429]);
  });

  it("lets only one of two concurrent first POSTs with different tokens own the mailbox", async () => {
    const m = mailboxId();
    const [a, b] = await Promise.all([call(m, "POST", TOKEN, blob(10)), call(m, "POST", OTHER, blob(10))]);
    expect([a.status, b.status].sort()).toEqual([201, 403]);
  });
});

describe("storage", () => {
  it("stores the token hash and not the token", async () => {
    const m = mailboxId();
    await call(m, "POST", TOKEN, blob(10));
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(m));
    await runInDurableObject(stub, async (_i, state) => {
      const rows = [...state.storage.sql.exec("SELECT value FROM meta WHERE key = 'tokenHash'")];
      const expected = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(TOKEN)))]
        .map((b) => b.toString(16).padStart(2, "0")).join("");
      expect(rows[0].value).toBe(expected);
      const all = JSON.stringify([...state.storage.sql.exec("SELECT value FROM meta")]);
      expect(all).not.toContain(TOKEN);
    });
  });

  it("keeps the data after the Durable Object is evicted from memory", async () => {
    const m = mailboxId();
    await call(m, "POST", TOKEN, blob(10));
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(m));
    await runInDurableObject(stub, async () => {});
    const res = await call(m, "GET", TOKEN);
    expect(((await res.json()) as any).batches).toEqual([{ cursor: 1, blob: "BwcHBwcHBwcHBw" }]);
  });
});

describe("retention", () => {
  it("deletes the mailbox when the alarm runs after the retention time", async () => {
    const m = mailboxId();
    await call(m, "POST", TOKEN, blob(10));
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(m));
    await runInDurableObject(stub, async (_i, state) => {
      // Move the last POST time back: now it is older than RETENTION_SECONDS (3600).
      state.storage.sql.exec("UPDATE meta SET value = ? WHERE key = 'lastPost'", String(Date.now() - 7200_000));
    });
    expect(await runDurableObjectAlarm(stub)).toBe(true);
    const res = await call(m, "GET", OTHER);
    expect(await res.json()).toEqual({ batches: [], more: false });
    expect((await call(m, "POST", OTHER, blob(10))).status).toBe(201); // a new owner, a new cursor 1
    expect(((await (await call(m, "GET", OTHER)).json()) as any).batches[0].cursor).toBe(1);
  });

  it("keeps the mailbox when the alarm runs before the retention time", async () => {
    const m = mailboxId();
    await call(m, "POST", TOKEN, blob(10));
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(m));
    await runDurableObjectAlarm(stub);
    expect(((await (await call(m, "GET", TOKEN)).json()) as any).batches).toHaveLength(1);
  });
});
