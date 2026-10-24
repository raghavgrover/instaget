// claim-credits: one-time free download credits per device.
//
// POST { "deviceKey": "<64 hex chars>" }
// Header X-Firebase-AppCheck: <App Check token>   (proves the call comes from the genuine app)
// → 200 { "grant": 10 } the first time a device claims, { "grant": 0 } afterwards.
//
// Env (set with `supabase secrets set`):
//   FIREBASE_PROJECT_NUMBER  numeric Firebase project number (Project settings → General)
//   INITIAL_CREDITS          optional, defaults to 10
// SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are injected by Supabase automatically.

import { createClient } from "npm:@supabase/supabase-js@2.49.4";
import { createRemoteJWKSet, jwtVerify } from "npm:jose@5.9.6";

const FIREBASE_PROJECT_NUMBER = Deno.env.get("FIREBASE_PROJECT_NUMBER") ?? "";
const INITIAL_CREDITS = Number(Deno.env.get("INITIAL_CREDITS") ?? "10");
const DEVICE_KEY_RE = /^[0-9a-f]{64}$/;

const appCheckJwks = createRemoteJWKSet(
  new URL("https://firebaseappcheck.googleapis.com/v1/jwks"),
);

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

/** Verifies a Firebase App Check token per https://firebase.google.com/docs/app-check/custom-resource-backend */
async function verifyAppCheck(token: string | null): Promise<boolean> {
  if (!token || !FIREBASE_PROJECT_NUMBER) return false;
  try {
    const { payload, protectedHeader } = await jwtVerify(token, appCheckJwks, {
      issuer: `https://firebaseappcheck.googleapis.com/${FIREBASE_PROJECT_NUMBER}`,
      audience: `projects/${FIREBASE_PROJECT_NUMBER}`,
      algorithms: ["RS256"],
    });
    return protectedHeader.typ === "JWT" && typeof payload.sub === "string";
  } catch (_) {
    return false;
  }
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  if (!(await verifyAppCheck(req.headers.get("X-Firebase-AppCheck")))) {
    return json(401, { error: "app_check_failed" });
  }

  let deviceKey: unknown;
  try {
    ({ deviceKey } = await req.json());
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
