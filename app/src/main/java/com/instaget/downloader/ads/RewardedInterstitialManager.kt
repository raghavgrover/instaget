package com.instaget.downloader.ads

import com.instaget.downloader.analytics.Analytics
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Manages the rewarded interstitial shown when a free user has 0 credits.
 *
 * Credit gate rules:
 *  credits > 0 → calls [onProceed] immediately; DownloadWorker consumes 1 credit once the
 *                download actually succeeds (atomic with the file save — not tied to any
 *                UI-layer observer, which can silently miss the callback).
 *  credits = 0 → an ad is offered, but ad *infrastructure* failures never block the download:
 *    • Ad ready              → shows immediately.
 *    • Ad still loading      → queues the request; shows ad automatically when load completes.
 *    • Ad failed to load     → not the user's fault — [onProceed] fires anyway, no bonus credits.
 *    • Ad failed to show     → not the user's fault — [onProceed] fires anyway, no bonus credits.
 *    • Ad shown but skipped  → user's own choice to bail early — download withheld, same as before.
 *    • Reward earned         → +2 credits, [onProceed] fires.
 *
 * Premium users bypass everything.
 *
 * Singleton (application-scoped): a single cached/loading ad instance survives fragment
 * recreation (tab switches), instead of every Home/Threads fragment starting a fresh load.
 */
class RewardedInterstitialManager private constructor(private val context: Context) {

    private var rewardedAd: RewardedInterstitialAd? = null
    private var loadedAtMs = 0L
    private var isLoading = false
    private var retryAttempt = 0

    // Queued gate request — fired automatically when the pending load completes
    private var pendingActivity: WeakReference<Activity>? = null
    private var pendingOnProceed: (() -> Unit)? = null
    private var pendingOnCreditsUpdated: ((Int) -> Unit)? = null
    private var hasPendingGate = false

    private val retryHandler = Handler(Looper.getMainLooper())
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "RewardedIntAd"
        // Background retries after a failed load are capped: endless retries every 60s (as
        // before) burn requests, lower the unit's match rate and can get the device throttled
        // ("Too many recently failed requests"), making the load fail exactly when a user
        // needs it. After the cap, the next gateDownload() starts a fresh load.
        private val RETRY_DELAYS_MS = longArrayOf(15_000, 60_000, 180_000)
        // Loaded full-screen ads expire after 1 hour (AdMob); refresh slightly before that.
        private const val AD_MAX_AGE_MS = 55 * 60 * 1000L

        @Volatile
        private var INSTANCE: RewardedInterstitialManager? = null

        fun getInstance(context: Context): RewardedInterstitialManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RewardedInterstitialManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /** Pre-loads the next ad. Queued gate requests are fired automatically on load. */
    fun preload() {
        if (rewardedAd != null && isExpired()) {
            Log.d(TAG, "Cached ad expired — reloading")
            rewardedAd = null
        }
        if (isLoading || rewardedAd != null) return
        isLoading = true
        RewardedInterstitialAd.load(
            context,
            AdConfig.REWARDED_INTERSTITIAL,
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    rewardedAd = ad
                    loadedAtMs = System.currentTimeMillis()
                    isLoading = false
                    retryAttempt = 0
                    Log.d(TAG, "Rewarded interstitial loaded")

                    // If gateDownload() was called while we were loading, fire it now
                    firePendingGate()
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    // code 3 = NO_FILL (no ad available / serving limited), 2 = NETWORK_ERROR,
                    // 0 = INTERNAL_ERROR, 1 = INVALID_REQUEST (bad unit ID / app ID mismatch).
                    Log.w(TAG, "Failed to load: code=${err.code} ${err.message}")
                    Analytics.adLoadFailed("rewarded_interstitial", err.code)

                    // Ad infra failing is not the user's fault — let a queued download proceed anyway.
                    if (hasPendingGate) {
                        Analytics.rewardedAdResult("failed_load")
                        val proceed = pendingOnProceed
                        hasPendingGate = false
                        pendingActivity = null
                        pendingOnProceed = null
                        pendingOnCreditsUpdated = null
                        showToast("Continuing without ad — enjoy your download!")
                        proceed?.invoke()
                    }

                    scheduleRetry()
                }
            }
        )
    }

    private fun scheduleRetry() {
        if (retryAttempt >= RETRY_DELAYS_MS.size) {
            Log.d(TAG, "Retry cap reached — next load starts on the next gate request")
            return
        }
        val delay = RETRY_DELAYS_MS[retryAttempt]
        retryAttempt++
        retryHandler.removeCallbacksAndMessages(null)
        retryHandler.postDelayed({ preload() }, delay)
    }

    private fun isExpired() = System.currentTimeMillis() - loadedAtMs > AD_MAX_AGE_MS

    /**
     * Call BEFORE starting the download.
     *
     * - credits > 0   → [onProceed] fires immediately.
     * - credits = 0   → shows (or queues) the rewarded ad. [onProceed] fires ONLY on full watch.
     */
    fun gateDownload(
        activity: Activity,
        isPremium: Boolean,
        onCreditsUpdated: (Int) -> Unit,
        isUiAlive: () -> Boolean = { true },
        onProceed: () -> Unit
    ) {
        if (isPremium) { onProceed(); return }

        if (!CreditsManager.isInitialized(context)) {
            // Free grant not settled yet (fresh install still claiming, or the server was
            // unreachable earlier): try once more before deciding, then re-enter the gate.
            val actRef = WeakReference(activity)
            mainScope.launch {
                CreditsManager.ensureInitialized(context)
                // The claim can take seconds; the screen may be gone by now (tab switch).
                val act = actRef.get()
                if (act == null || act.isFinishing || act.isDestroyed || !isUiAlive()) return@launch
                onCreditsUpdated(CreditsManager.getCredits(context))
                gateCredits(act, onCreditsUpdated, onProceed)
            }
            return
        }
        gateCredits(activity, onCreditsUpdated, onProceed)
    }

    private fun gateCredits(
        activity: Activity,
        onCreditsUpdated: (Int) -> Unit,
        onProceed: () -> Unit
    ) {
        val credits = CreditsManager.getCredits(context)
        if (credits > 0) {
            onProceed()
            return
        }

        // 0 credits — must earn via ad
        Analytics.creditsExhausted()
        if (rewardedAd != null && isExpired()) rewardedAd = null
        val ad = rewardedAd
        if (ad != null) {
            // Ad is ready — show it right now
            showAd(WeakReference(activity), onCreditsUpdated, onProceed)
        } else if (isLoading) {
            // Ad is still loading (e.g. onViewCreated called preload() just a moment ago).
            // Queue the request; firePendingGate() will show it the instant load completes.
            Log.d(TAG, "Ad loading — queuing gate request")
            showToast("Loading ad…")
            hasPendingGate = true
            pendingActivity = WeakReference(activity)
            pendingOnProceed = onProceed
            pendingOnCreditsUpdated = onCreditsUpdated
        } else {
            // Not loading at all — start a fresh load and queue
            Log.d(TAG, "No ad and not loading — starting fresh preload and queuing gate")
            // User-initiated: cancel any pending backoff and allow a fresh round of retries.
            retryHandler.removeCallbacksAndMessages(null)
            retryAttempt = 0
            showToast("Loading ad…")
            hasPendingGate = true
            pendingActivity = WeakReference(activity)
            pendingOnProceed = onProceed
            pendingOnCreditsUpdated = onCreditsUpdated
            preload()
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun firePendingGate() {
        if (!hasPendingGate) return
        val ad = rewardedAd ?: return  // shouldn't happen but guard anyway
        val actRef = pendingActivity ?: return
        val proceed = pendingOnProceed ?: return
        val creditsUpdated = pendingOnCreditsUpdated ?: {}

        hasPendingGate = false
        pendingActivity = null
        pendingOnProceed = null
        pendingOnCreditsUpdated = null

        val act = actRef.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            Log.d(TAG, "Pending gate: activity gone — not showing ad")
            return
        }
        showAd(WeakReference(act), creditsUpdated, proceed)
    }

    private fun showAd(
        actRef: WeakReference<Activity>,
        onCreditsUpdated: (Int) -> Unit,
        onProceed: () -> Unit
    ) {
        val ad = rewardedAd ?: run {
            showToast("Continuing without ad — enjoy your download!")
            onProceed()
            return
        }

        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                preload()
                Analytics.rewardedAdResult(if (rewardEarned) "earned" else "skipped")
                if (rewardEarned) {
                    onProceed()
                } else {
                    showToast("Almost there! Finish the ad and get 2 free downloads. ⚡")
                }
            }
            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                Log.w(TAG, "Ad failed to show: ${err.message}")
                Analytics.rewardedAdResult("failed_show")
                rewardedAd = null
                preload()
                showToast("Continuing without ad — enjoy your download!")
                onProceed()
            }
        }

        val act = actRef.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            rewardedAd = null
            return
        }

        ad.show(act) { _ ->
            rewardEarned = true
            val newBalance = CreditsManager.addRewardCredits(context)
            Log.d(TAG, "Reward earned — balance before download: $newBalance")
            onCreditsUpdated(newBalance)
        }
        rewardedAd = null
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}
