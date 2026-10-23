// Types for the bindings and the main module, as `wrangler types` would write them.
import type { Env as WorkerEnv } from "../src/index";

declare global {
  namespace Cloudflare {
    interface GlobalProps {
      mainModule: typeof import("../src/index");
      durableNamespaces: "Mailbox";
    }
    interface Env extends WorkerEnv {}
  }
  interface Env extends WorkerEnv {}
}
