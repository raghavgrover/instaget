# InstaGet backend (Supabase)

One job: remember which devices already received the 10 free download credits, so clearing
app data or reinstalling can't re-grant them.

- `migrations/20261024000000_credit_grants.sql` — `credit_grants` table (RLS on, no client
  access) and the atomic `claim_initial_grant()` function.
- `functions/claim-credits/` — Edge Function the app calls on first launch. Callers are
  authenticated with **Firebase App Check** (Play Integrity), not a Supabase key.

Stored per device: `device_key` (SHA-256 of `"instaget:" + Android ID`, computed on the phone),
the granted amount, and a timestamp. Nothing else.

## One-time setup

1. **Firebase project** (also used for Analytics/Crashlytics)
   - Create a project, add the Android app `com.instaget.downloader`, download
     `google-services.json` into `app/` (gitignored).
   - Add your **app signing key SHA-256** (Play Console → Test and release → App integrity →
     App signing) under Project settings → Your apps.
   - App Check → register the app with **Play Integrity**. Link the same Google Cloud project
     in Play Console → App integrity → Play Integrity API.
   - Note the **project number** (Project settings → General).
2. **Supabase** (project `dwojyygknmodevuvqhar`) — migration and function are **already
   deployed** (2026-10-24). Only the secret is left: Dashboard → Edge Functions → Secrets →
   add `FIREBASE_PROJECT_NUMBER`. To redeploy from the CLI later:
   ```bash
   supabase link --project-ref dwojyygknmodevuvqhar
   supabase db push
   supabase secrets set FIREBASE_PROJECT_NUMBER=<firebase project number>
   supabase functions deploy claim-credits --no-verify-jwt
   ```
   Until `FIREBASE_PROJECT_NUMBER` is set the function answers 401 to everyone, and new
   installs start at 0 credits (downloads still work via the rewarded-ad gate).
3. **Debug builds**: the App Check debug provider prints a token in Logcat
   (`DebugAppCheckProvider`). Add it in Firebase console → App Check → Apps → ⋮ → Manage debug
   tokens, otherwise debug builds with `google-services.json` get 0 free credits.

## Check it works

```bash
curl -i -X POST https://dwojyygknmodevuvqhar.supabase.co/functions/v1/claim-credits -d '{}'
```
should return `401 {"error":"app_check_failed"}`. Rows appear in `credit_grants` as devices
claim; `select count(*) from credit_grants;` is the number of devices that got the free grant.
