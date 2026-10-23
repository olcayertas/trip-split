import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

const wrangler = { configPath: "./wrangler.jsonc" };

export default defineConfig({
  test: {
    projects: [
      {
        // Default limits: the vectors of protocol/vectors/relay.json run against the Worker as deployed.
        plugins: [cloudflareTest({ wrangler })],
        test: { name: "vectors", include: ["test/vectors.test.ts"] },
      },
      {
        // Tiny limits so the tests reach 507, 429 and the retention rule fast.
        plugins: [
          cloudflareTest({
            wrangler,
            miniflare: { bindings: { MAX_BLOBS: "3", MAX_BYTES: "1000", RATE_LIMIT: "20", RETENTION_SECONDS: "3600" } },
          }),
        ],
        test: { name: "limits", include: ["test/limits.test.ts"] },
      },
    ],
  },
});
