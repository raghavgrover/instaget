// claim-credits: one-time free download credits per device.
//
// POST { "deviceKey": "<64 hex chars>" }
// → 200 { "grant": 10 } the first time a device claims, { "grant": 0 } afterwards.
//
// Deliberately unauthenticated (deployed with verify_jwt = false): the credit gate runs on the
// device, so this endpoint only needs to stop ordinary clear-data / reinstall resets.
//
// Env (optional, set with `supabase secrets set`):
//   INITIAL_CREDITS  defaults to 10
// SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are injected by Supabase automatically.

import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const INITIAL_CREDITS = Number(Deno.env.get("INITIAL_CREDITS") ?? "10");
const DEVICE_KEY_RE = /^[0-9a-f]{64}$/;
const MAX_BODY_BYTES = 1024;

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const raw = await req.text();
  if (raw.length > MAX_BODY_BYTES) return json(413, { error: "too_large" });

  let deviceKey: unknown;
  try {
    ({ deviceKey } = JSON.parse(raw));
  } catch (_) {
    return json(400, { error: "bad_json" });
  }
  if (typeof deviceKey !== "string" || !DEVICE_KEY_RE.test(deviceKey)) {
    return json(400, { error: "bad_device_key" });
  }

  const { data, error } = await supabase.rpc("claim_initial_grant", {
    p_device_key: deviceKey,
    p_grant: INITIAL_CREDITS,
  });
  if (error) {
    console.error("claim_initial_grant failed", error.message);
    return json(500, { error: "db_error" });
  }
  return json(200, { grant: data as number });
});
