// Replays protocol/vectors/relay.json against the Worker. The vectors come from the Python reference
// relay, so a pass here means the Worker answers exactly like the reference.

import { env, exports } from "cloudflare:workers";
import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import vectors from "../../protocol/vectors/relay.json";

type Auth = "good" | "other" | "none";
const OTHER = "f".repeat(64);
const bearer = (auth: Auth) =>
  auth === "good" ? `Bearer ${vectors.token}` : auth === "other" ? `Bearer ${OTHER}` : undefined;

function fromHex(h: string): Uint8Array {
  return Uint8Array.from(h.match(/../g) ?? [], (b) => parseInt(b, 16));
}

// Each vector run uses its own mailbox, so a run never sees the data of another run.
// The path in the vectors holds the mailbox id of the vectors. This helper swaps it.
function request(method: string, path: string, auth: Auth, body?: Uint8Array, mailbox?: string) {
  const p = mailbox ? path.replace(vectors.mailboxId, mailbox) : path;
  const headers: Record<string, string> = {};
  const a = bearer(auth);
  if (a) headers.Authorization = a;
  return exports.default.fetch(new Request(`https://relay.test${p}`, { method, headers, body }));
}

// The vectors use epoch k for the k-th mailbox that a relay makes. The Worker makes random epochs. Map each
// Worker epoch to the vector epoch it first stands for, and check that the mapping stays one to one.
function epochMatcher() {
  const map = new Map<string, string>();
  const used = new Set<string>();
  return (got: any, want: any) => {
    if (want && typeof want === "object" && "epoch" in want && got && typeof got === "object") {
      if (want.epoch === null) {
        expect(got.epoch).toBeNull();
      } else {
        expect(got.epoch).toMatch(/^[0-9a-f]{32}$/);
        if (!map.has(got.epoch)) {
          expect(used.has(want.epoch), "two Worker epochs for one vector epoch").toBe(false);
          map.set(got.epoch, want.epoch);
          used.add(want.epoch);
        }
        expect(map.get(got.epoch)).toBe(want.epoch);
      }
      got = { ...got, epoch: want.epoch };
    }
    expect(got).toEqual(want);
  };
}

async function expire(mailbox: string) {
  const stub = env.MAILBOX.get(env.MAILBOX.idFromName(mailbox));
  await runInDurableObject(stub, async (_obj, state) => {
    await state.storage.deleteAll(); // what the retention alarm does after 400 days (SPEC 10.4.5)
  });
}

function freshMailbox(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

describe("scenario", () => {
  it("answers every request of the scenario like the reference relay", async () => {
    const mailbox = freshMailbox();
    const match = epochMatcher();
    for (const step of vectors.scenario.steps) {
      for (const r of step.requests as any[]) {
        const res = await request(r.method, r.path, "good", r.bodyHex ? fromHex(r.bodyHex) : undefined, mailbox);
        expect(res.status, `${step.device} ${r.method} ${r.path}`).toBe(r.status);
        match(await res.json(), r.response);
      }
    }
    const all = await request("GET", `/v1/m/${mailbox}/batches`, "good");
    expect(((await all.json()) as any).batches).toHaveLength(vectors.scenario.relayBlobCount);
  });
});

describe("expiry scenario", () => {
  it("gives a new epoch after the mailbox is deleted, and answers the stale client like the reference", async () => {
    const mailbox = freshMailbox();
    const match = epochMatcher();
    const sc = vectors.expiryScenario as any;
    for (const [i, step] of (sc.steps as any[]).entries()) {
      if (i === sc.expireBeforeStep) await expire(mailbox);
      for (const r of step.requests as any[]) {
        const res = await request(r.method, r.path, "good", r.bodyHex ? fromHex(r.bodyHex) : undefined, mailbox);
        expect(res.status, `${step.device} ${r.method} ${r.path}`).toBe(r.status);
        match(await res.json(), r.response);
      }
    }
    const all = await request("GET", `/v1/m/${mailbox}/batches?limit=100`, "good");
    expect(((await all.json()) as any).batches).toHaveLength(sc.relayBlobCount);
  });
});

describe("http cases", () => {
  for (const c of vectors.http as any[]) {
    it(c.name, async () => {
      const mailbox = freshMailbox();
      const match = epochMatcher();
      for (const st of c.steps) {
        const body = st.bodyBytes === undefined ? undefined : new Uint8Array(st.bodyBytes).fill(1);
        const res = await request(st.method, st.path, st.auth, body, st.path.includes(vectors.mailboxId) ? mailbox : undefined);
        expect(res.status, JSON.stringify(st)).toBe(st.status);
        match(await res.json(), st.response);
      }
    });
  }
});

describe("routing", () => {
  it("answers 404 for an unknown path and 405 for another method", async () => {
    const m = freshMailbox();
    expect((await exports.default.fetch(new Request("https://relay.test/other"))).status).toBe(404);
    const res = await exports.default.fetch(new Request(`https://relay.test/v1/m/${m}/batches`, { method: "DELETE" }));
    expect(res.status).toBe(405);
  });
});
