# InstaGet backend (Supabase)

One job: remember which devices already received the 10 free download credits, so clearing
app data or reinstalling can't re-grant them.

- `migrations/20261024000000_credit_grants.sql` — `credit_grants` table (RLS on, no client
  access) and the atomic `claim_initial_grant()` function.
- `functions/claim-credits/` — Edge Function the app calls on first launch.

Stored per device: `device_key` (SHA-256 of `"instaget:" + Android ID`, computed on the phone),
the granted amount, and a timestamp. Nothing else.

The endpoint is intentionally unauthenticated (`verify_jwt = false`). The credit gate runs
on-device, so a modified APK could skip it regardless; the server only needs to stop ordinary
clear-data / reinstall resets. Firebase App Check was considered and dropped as not worth its
setup cost for this threat.

## Status

Deployed to project `dwojyygknmodevuvqhar` on 2026-10-24 (migration applied, function v2
active). Nothing else to configure. Optional secret: `INITIAL_CREDITS` (defaults to 10).

To redeploy from the CLI (logged in with the account that owns the project):
```bash
supabase link --project-ref dwojyygknmodevuvqhar
supabase db push
supabase functions deploy claim-credits --no-verify-jwt
```

## Check it works

```bash
curl -i -X POST https://dwojyygknmodevuvqhar.supabase.co/functions/v1/claim-credits -d '{}'
```
should return `400 {"error":"bad_device_key"}`. `select count(*) from credit_grants;` is the
number of devices that have received the free grant.

## Note on debug builds

Debug builds are signed with the debug key, so they get a different Android ID from the Play
build on the same phone — each debug install claims (and uses up) its own grant.
