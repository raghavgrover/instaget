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
 *  credits > 0 → calls [onProceed] immediately; [onDownloadSucceeded] consumes 1 credit after success.
 *  credits = 0 → ad MUST play first:
 *    • Ad ready         → shows immediately.
 *    • Ad still loading → queues the request; shows ad automatically when load completes.
 *    • Ad failed load   → toast "Ad unavailable". NO download.
 *    • Ad skipped       → toast "Watch the full ad to earn credits". NO download.
 *    • Reward earned    → +2 credits, [onProceed] fires.
 *
 * Premium users bypass everything.
 */
class RewardedInterstitialManager(private val context: Context) {

    private var rewardedAd: RewardedInterstitialAd? = null
    private var isLoading = false

    // Queued gate request — fired automatically when the pending load completes
    private var pendingActivity: WeakReference<Activity>? = null
    private var pendingOnProceed: (() -> Unit)? = null
    private var pendingOnCreditsUpdated: ((Int) -> Unit)? = null
    private var hasPendingGate = false

    companion object {
        private const val TAG = "RewardedIntAd"
    }

    /** Pre-loads the next ad. Queued gate requests are fired automatically on load. */
    fun preload() {
        if (isLoading || rewardedAd != null) return
        isLoading = true
        RewardedInterstitialAd.load(
            context.applicationContext,
            AdConfig.REWARDED_INTERSTITIAL,
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.d(TAG, "Rewarded interstitial loaded")

                    // If gateDownload() was called while we were loading, fire it now
                    firePendingGate()
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.w(TAG, "Failed to load: ${err.message}")

                    // Clear any queued gate — can't show an ad that didn't load
                    if (hasPendingGate) {
                        hasPendingGate = false
                        pendingActivity = null
                        pendingOnProceed = null
                        pendingOnCreditsUpdated = null
                        showToast("Ad unavailable right now. Try again shortly.")
                    }
                }
            }
        )
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

    /**
     * Call inside WorkInfo.State.SUCCEEDED to consume 1 credit.
     * Returns the new balance (floor 0).
     */
    fun onDownloadSucceeded(isPremium: Boolean): Int {
        if (isPremium) return Int.MAX_VALUE
        return CreditsManager.consumeCredit(context)
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
            showToast("Ad unavailable. Try again shortly.")
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
                showToast("Ad unavailable. Try again shortly.")
                // NO download at 0 credits
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
