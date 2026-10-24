package com.instaget.downloader.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Thin wrapper over Google Analytics for Firebase.
 *
 * Privacy rule: only categorical values are ever logged — never post URLs, usernames,
 * captions, shortcodes or any other content the user downloads. Keep it that way; the
 * Play Data safety form and privacy policy are written against this.
 *
 * No-ops when Firebase isn't configured (debug builds without google-services.json).
 */
object Analytics {

    const val PLATFORM_INSTAGRAM = "instagram"
    const val PLATFORM_THREADS = "threads"

    @Volatile private var firebase: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        firebase = FirebaseAnalytics.getInstance(context.applicationContext)
    }

    fun fetchStarted(platform: String) =
        log("fetch_started", "platform" to platform)

    fun fetchSucceeded(platform: String, mediaType: String) =
        log("fetch_succeeded", "platform" to platform, "media_type" to mediaType)

    /** [errorMessage] is only bucketed into a category here; the raw text is never sent. */
    fun fetchFailed(platform: String, errorMessage: String?) =
        log("fetch_failed", "platform" to platform, "reason" to failureReason(errorMessage))

    fun downloadSucceeded(platform: String, mediaType: String, itemCount: Int) =
        log("download_succeeded", "platform" to platform, "media_type" to mediaType, "item_count" to itemCount)

    fun downloadFailed(platform: String, errorMessage: String?) =
        log("download_failed", "platform" to platform, "reason" to failureReason(errorMessage))

    fun creditsExhausted() = log("credits_exhausted")

    /** [result]: earned, skipped, failed_load, failed_show. */
    fun rewardedAdResult(result: String) = log("rewarded_ad_result", "result" to result)

    /** AdMob LoadAdError code: 0 internal, 1 invalid request, 2 network, 3 no fill. */
    fun adLoadFailed(format: String, errorCode: Int) =
        log("ad_load_failed", "format" to format, "error_code" to errorCode)

    fun paywallViewed() = log("paywall_viewed")

    fun purchaseStarted(sku: String) = log("purchase_started", "sku" to sku)

    private fun log(event: String, vararg params: Pair<String, Any>) {
        val fa = firebase ?: return
        val bundle = Bundle()
        params.forEach { (k, v) ->
            when (v) {
                is Int -> bundle.putLong(k, v.toLong())
                else -> bundle.putString(k, v.toString())
            }
        }
        fa.logEvent(event, bundle)
    }

    internal fun failureReason(message: String?): String {
        val m = message?.lowercase() ?: return "unknown"
        return when {
            "private" in m -> "private"
            "login" in m || "logged-in" in m -> "login_required"
            "expired" in m -> "expired"
            "connection" in m || "network" in m || "unable to resolve host" in m ||
                "timeout" in m || "timed out" in m -> "network"
            "invalid" in m -> "invalid_url"
            "http " in m -> "http_error"
            "parse" in m || "changed their api" in m || "not found" in m ||
                "could not fetch" in m || "no downloadable" in m -> "not_found_or_changed"
            else -> "other"
        }
    }
}
