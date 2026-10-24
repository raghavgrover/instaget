# InstaGet v1.7 (versionCode 8) — Release Plan

> **Status (2026-10-24): code implemented.** Backend chosen: **Supabase Edge Function**
> (`supabase/`) authenticated with **Firebase App Check** tokens. Firebase BoM pinned to 34.19.0
> (35.x needs Kotlin 2.4 metadata; upgrading Kotlin was out of scope). UMP kept on 3.x (3.2.0).
> Remaining manual steps: see `supabase/README.md` and section 5.

Goal: add Google analytics, close the "clear data resets credits" exploit, and fix only the
tech debt that is a real correctness, revenue or policy risk — without touching scraping logic.

Ground rule for every change below: `InstagramScraper.kt` and `ThreadsScraper.kt` keep their
logic byte-for-byte. The only allowed edits there are visibility changes (`private` → `internal`)
so parsers can be unit-tested, and analytics calls at the call sites (ViewModels), not inside.

---

## 0. Data safety — read this first

**The current "we do not collect any data" declaration is already inaccurate, before any new work.**
The app ships the Google Mobile Ads SDK (AdMob), which per Google's own disclosure guide
automatically *collects and shares*: IP address (approximate location), user product interactions
(app launches, taps, ad views), diagnostics, and device identifiers (Advertising ID, App Set ID).
Your own privacy policy already says AdMob collects the advertising ID, device info and IP — so the
Play Data safety form contradicts both the SDK and the policy. Fix this with this release regardless
of what else ships.

### What the Data safety form should say after v1.7

| Data type (Play category) | Source | Collected | Shared | Purposes |
|---|---|---|---|---|
| Location → Approximate location | AdMob (IP), Firebase Analytics (masked IP) | Yes | Yes (AdMob) | Advertising, Analytics, Fraud prevention |
| App activity → App interactions | AdMob, Firebase Analytics | Yes | Yes (AdMob) | Advertising, Analytics |
| App info & performance → Crash logs | Crashlytics (if adopted) | Yes | No | Analytics / App functionality |
| App info & performance → Diagnostics | AdMob, Crashlytics | Yes | Yes (AdMob) | Advertising, Analytics, Fraud prevention |
| Device or other IDs | AdMob (Ad ID), Firebase (app-instance / installation ID), credit check (hashed device key) | Yes | Yes (AdMob) | Advertising, Analytics, Fraud prevention & security |
| Financial info → Purchase history | Firebase Analytics auto-logs in-app purchase events (product, price) | Yes | No | Analytics |

Other answers: data encrypted in transit — **Yes** (all three SDKs use TLS). Account creation —
**No**, so no account-deletion URL is required. Data collection is required (not optional) for the
SDK-level items. Firebase acting as your service provider counts as *collected, not shared*; AdMob
sending data to Google for ad serving counts as *shared*.

Also required:
- **Advertising ID declaration** (Play Console → App content): "Yes, uses Advertising ID" for
  advertising/analytics. `play-services-ads` merges the `AD_ID` permission automatically.
- **Privacy policy update** (saanidhyasoftware.lovable.app/instaget/privacy): it currently says
  "We use no other third-party SDKs" and promises in-app notice before adding one. Add Firebase
  Analytics/Crashlytics, the fraud-prevention device check, and fix the contradictory "we may
  collect data necessary to process your payment" line.
- **In-app notice** (your policy promises it): bump a `TERMS_VERSION` constant so
  `WelcomeActivity` re-shows once to existing users with an "Updated privacy policy" line.

---

## 1. Fix: clearing app data resets download credits (P0)

### Why it happens
Credits live only in `SharedPreferences` (`credits_prefs`). Settings → Storage → **Clear storage**
wipes all app-private data, `CreditsManager.getCredits()` sees `credits_initialized == false`, and
re-grants 10 credits. ("Clear cache" alone does *not* reset them.) Android deliberately gives apps
no local storage that survives Clear storage, so **any real fix needs a small server-side record.**
Rejected local-only ideas: marker files in shared storage / MediaStore (user-deletable, and
MediaStore drops the app's ownership on data clear), Auto Backup (only restores on reinstall, not
on clear data).

### Recommended design: one-time "initial grant" claim, server-side
Smallest possible server state — one boolean per device — enough to kill the exploit:

1. On-device key: `SHA-256(Settings.Secure.ANDROID_ID + APP_SALT)`. Since Android 8, ANDROID_ID is
   scoped to (signing key, user, device): it survives clear data and reinstall, resets on factory
   reset. minSdk is 28, so this always holds.
2. Endpoint `claimInitialGrant(deviceKey)` → `{ grant: 10 }` the first time, `{ grant: 0 }` after.
   Idempotent; stores `{ deviceKey, claimedAt }` only. Protected by **Firebase App Check (Play
   Integrity provider)** so only genuine copies of your app can call it.
3. `CreditsManager` (renamed `CreditsRepository`) changes:
   - Uninitialized state no longer auto-grants. It calls `claimInitialGrant` and initializes to the
     returned value.
   - Server unreachable during that first claim → initialize to **0 for now** and retry the claim on
     the next app start (downloads need network anyway; the existing fail-open ad gate still lets
     them download). Never grant 10 offline.
   - Existing users upgrading (already initialized) are untouched; on first launch of v1.7 they
     silently register their device key so a later clear data won't re-grant.
4. Rewarded-ad credits stay local. A user who clears data just loses them — they can never
   *gain* credits by resetting, which is the actual exploit.

Backend (implemented): **Supabase Edge Function `claim-credits` + `credit_grants` table**. The
function verifies the Firebase App Check JWT against Google's public JWKS, then calls the atomic
`claim_initial_grant()` SQL function.

Later upgrade (optional): **Play Integrity "device recall"** (beta, needs Google's approval form)
stores up to 3 bits per device on Google's servers, survives even factory reset, and means you hold
no device key at all. Same endpoint shape — swap the storage when approved.

Data safety impact: adds *Device or other IDs — collected, not shared — Fraud prevention, security*
(already declared because of AdMob, just add the purpose).

---

## 2. Analytics: Firebase (Google Analytics 4) + Crashlytics (P0)

- Add `com.google.gms.google-services` plugin, Firebase BoM, `firebase-analytics`,
  `firebase-crashlytics` (+ Crashlytics Gradle plugin for R8 mapping upload). You create the
  Firebase project and drop `google-services.json` in `app/` (gitignored).
- Set `google_analytics_adid_collection_enabled=false` in the manifest — Analytics doesn't need the
  Ad ID (AdMob still uses it).
- Wire UMP consent → `Firebase.analytics.setConsent(...)` (Consent Mode v2) for EEA/UK users.
- **Never log URLs, usernames, captions or shortcodes.** Only categorical events:

| Event | Params |
|---|---|
| `fetch_started` | `platform` (ig/threads), `source` (paste/share) |
| `fetch_failed` | `platform`, `reason` (private/not_found/network/parse) |
| `download_succeeded` | `platform`, `media_type`, `item_count` |
| `download_failed` | `platform`, `reason` |
| `credits_exhausted` | — |
| `rewarded_ad_result` | `result` (earned/skipped/failed_load/failed_show) |
| `paywall_viewed` / `purchase_started` | `sku` |

  `fetch_failed` by `reason` is the early-warning signal for when Instagram/Threads change their
  pages and scraping breaks.
- Crashlytics: no custom keys containing user content.

---

## 3. Tech debt — must fix in this release

| # | Fix | Why it's necessary | Risk to scraping |
|---|---|---|---|
| 3.1 | **Billing: acknowledge purchases found by `queryActivePurchases()`**, check the acknowledge `BillingResult`, ignore `PENDING` purchases for entitlement | Today only the live `PurchasesUpdatedListener` acknowledges. If the app is killed before it fires, the purchase is never acknowledged and **Google auto-refunds it after 3 days** while the app keeps showing Premium | None |
| 3.2 | Billing: remove manual reconnect in `onBillingServiceDisconnected` and the ad-hoc `ensureConnected()` (double `resume` crash risk); rely on `enableAutoServiceReconnection()`; replace `CoroutineScope(Dispatchers.IO)` with one app-scoped `SupervisorJob` scope | Possible `IllegalStateException: Already resumed`; leaked scopes | None |
| 3.3 | Billing Library 8.3.0 → **9.1.0** | Google-recommended; no removed APIs we use | None |
| 3.4 | Google Mobile Ads 23.3.0 → **25.x** (and latest UMP) | 23.x is **deprecated since Feb 2026, sunsets Jun 30 2027** (ads may stop filling) | None |
| 3.5 | Room: remove `fallbackToDestructiveMigration()`, set `exportSchema = true` + schema dir | Any future schema slip silently **wipes the user's library** | None |
| 3.6 | Delete dead `DownloadRepository` + `InstagramApiService` + `OEmbedResponse`; drop Retrofit/Gson/logging-interceptor deps and their keep rules if nothing else uses them | Dead code (would crash on API 28 if ever wired up); smaller APK | None — scrapers use OkHttp + `org.json` only (verify with grep before deleting) |
| 3.7 | Scraper **safety-net tests**: make parse helpers `internal`, add JVM unit tests with saved HTML/JSON fixtures for IG post, reel, carousel, story; Threads image, video, carousel, text | The only way to prove "scraping unchanged" after the dependency bumps and R8 | Visibility only |
| 3.8 | Move `releases/*.aab` out of git, add to `.gitignore` | 17 MB in history; AABs embed the full R8 mapping | None |
| 3.9 | Remove `requestLegacyExternalStorage` (no-op at targetSdk 36) | Lint noise; keep `WRITE_EXTERNAL_STORAGE maxSdk=28` (needed on API 28) | None |

## 4. Deliberately deferred (not necessary for this release)

Hilt/DI, moving gating logic out of Fragments (beyond the new `CreditsRepository`), KSP migration,
version-catalog cleanup, narrowing broad R8 keep rules (risky for scraping, no user benefit),
GMA Next-Gen SDK migration, server-side purchase verification, AdMob server-side reward
verification, renaming leftover "FetchIn" identifiers (DB name/channel ID — renaming would orphan
existing data).

---

## 5. Order of work and release checklist

1. 3.7 scraper tests first (baseline green on current code).
2. 3.1–3.5, 3.6, 3.8, 3.9 — run tests after each.
3. Firebase project + Analytics/Crashlytics (section 2).
4. Backend endpoint + App Check + `CreditsRepository` (section 1).
5. `TERMS_VERSION` bump for the in-app notice; versionCode 8 / versionName 1.7.
6. Release build (R8 on); manual test on an **API 28** device and an API 34+ device:
   IG post/reel/carousel/story, Threads image/video/carousel/text, share-sheet entry, credits
   (fresh install, clear storage → expect 0, reinstall → expect 0, upgrade from 1.6 → balance kept),
   rewarded ad, subscribe/restore with a license tester.
7. Play Console: update privacy policy page → Data safety → Advertising ID declaration → upload.
   Also replace/pause the Closed testing vc4 release (Billing 6.2.0 warning).
