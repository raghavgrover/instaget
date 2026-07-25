package com.instaget.downloader.ads

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
    private var isLoading = false
    private var retryAttempt = 0

    // Queued gate request — fired automatically when the pending load completes
    private var pendingActivity: WeakReference<Activity>? = null
    private var pendingOnProceed: (() -> Unit)? = null
    private var pendingOnCreditsUpdated: ((Int) -> Unit)? = null
    private var hasPendingGate = false

    private val retryHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "RewardedIntAd"
        private val RETRY_DELAYS_MS = longArrayOf(15_000, 30_000, 60_000)

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
        if (isLoading || rewardedAd != null) return
        isLoading = true
        RewardedInterstitialAd.load(
            context,
            AdConfig.REWARDED_INTERSTITIAL,
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    rewardedAd = ad
                    isLoading = false
                    retryAttempt = 0
                    Log.d(TAG, "Rewarded interstitial loaded")

                    // If gateDownload() was called while we were loading, fire it now
                    firePendingGate()
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.w(TAG, "Failed to load: ${err.message}")

                    // Ad infra failing is not the user's fault — let a queued download proceed anyway.
                    if (hasPendingGate) {
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
        val delay = RETRY_DELAYS_MS[retryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        retryAttempt++
        retryHandler.postDelayed({ preload() }, delay)
    }

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
        onProceed: () -> Unit
    ) {
        if (isPremium) { onProceed(); return }

        val credits = CreditsManager.getCredits(context)
        if (credits > 0) {
            onProceed()
            return
        }

        // 0 credits — must earn via ad
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
                if (rewardEarned) {
                    onProceed()
                } else {
                    showToast("Almost there! Finish the ad and get 2 free downloads. ⚡")
                }
            }
            override fun onAdFailedToShowFullScreenContent(err: AdError) {
                Log.w(TAG, "Ad failed to show: ${err.message}")
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
