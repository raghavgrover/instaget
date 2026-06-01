package com.instaget.downloader.ads

import android.content.Context

/**
 * Manages download credits for free users.
 * - Free users start with 10 credits (one-time initialisation).
 * - Each successful download consumes 1 credit (if available).
 * - At 0 credits downloads still proceed, but a rewarded interstitial is shown after.
 * - Watching the full rewarded ad awards +2 credits.
 * - Premium subscribers bypass this system entirely.
 * - Credits persist forever in SharedPreferences.
 */
object CreditsManager {

    private const val PREFS = "credits_prefs"
    private const val KEY_CREDITS = "download_credits"
    private const val KEY_INITIALIZED = "credits_initialized"
    const val INITIAL_CREDITS = 10
    const val REWARD_CREDITS = 2

    fun getCredits(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            prefs.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putInt(KEY_CREDITS, INITIAL_CREDITS)
                .apply()
            return INITIAL_CREDITS
        }
        return prefs.getInt(KEY_CREDITS, 0)
    }

    /** Consumes 1 credit if available. Returns the new balance. */
    fun consumeCredit(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getCredits(context)
        val next = (current - 1).coerceAtLeast(0)
        prefs.edit().putInt(KEY_CREDITS, next).apply()
        return next
    }

    /** Adds REWARD_CREDITS after a completed rewarded ad. Returns the new balance. */
    fun addRewardCredits(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getCredits(context)
        val next = current + REWARD_CREDITS
        prefs.edit().putInt(KEY_CREDITS, next).apply()
        return next
    }

    fun hasCredits(context: Context) = getCredits(context) > 0

    /** Returns "∞" for premium users, otherwise the numeric credit balance. */
    fun displayString(context: Context, isPremium: Boolean) =
        if (isPremium) "∞" else getCredits(context).toString()
}
