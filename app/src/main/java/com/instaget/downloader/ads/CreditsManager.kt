package com.instaget.downloader.ads

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages download credits for free users.
 * - Free users get 10 credits once per *device* (not per install): the grant is claimed from the
 *   server via [CreditGrantClient], so clearing app data or reinstalling can't re-grant it.
 * - Each successful download consumes 1 credit (if available).
 * - At 0 credits downloads still proceed, but a rewarded interstitial is shown after.
 * - Watching the full rewarded ad awards +2 credits.
 * - Premium subscribers bypass this system entirely.
 * - Balance persists in SharedPreferences (lost on clear data — users can only lose credits,
 *   never gain them, by resetting).
 */
object CreditsManager {

    private const val PREFS = "credits_prefs"
    private const val KEY_CREDITS = "download_credits"
    private const val KEY_INITIALIZED = "credits_initialized"
    /** True once the server knows this device has had its free grant (or decided it). */
    private const val KEY_DEVICE_REGISTERED = "device_registered"
    const val INITIAL_CREDITS = 10
    const val REWARD_CREDITS = 2

    private val initMutex = Mutex()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** False until [ensureInitialized] has settled the free grant for this install. */
    fun isInitialized(context: Context) = prefs(context).getBoolean(KEY_INITIALIZED, false)

    /**
     * Settles the free grant with the server. Safe to call repeatedly; does network work only
     * until it has succeeded once.
     *  - New install / cleared data: balance += server grant (10 the first time this device
     *    asks, 0 afterwards). If the server can't be reached the install stays uninitialized
     *    with 0 credits and retries next time — the free grant is never handed out offline.
     *  - Existing users upgrading: keep their balance; the device is just registered so a later
     *    clear-data doesn't re-grant.
     */
    suspend fun ensureInitialized(context: Context) = initMutex.withLock {
        val prefs = prefs(context)
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if (initialized && prefs.getBoolean(KEY_DEVICE_REGISTERED, false)) return@withLock

        if (!CreditGrantClient.isConfigured(context)) {
            // Local debug builds without google-services.json (release builds can't be made
            // without it): fall back to the old per-install grant.
            if (!initialized) {
                prefs.edit().putBoolean(KEY_INITIALIZED, true).putInt(KEY_CREDITS, INITIAL_CREDITS).apply()
            }
            return@withLock
        }

        val grant = CreditGrantClient.claim(context) ?: return@withLock
        if (initialized) {
            prefs.edit().putBoolean(KEY_DEVICE_REGISTERED, true).apply()
        } else {
            // Additive: keeps any reward credits earned while the claim was pending.
            prefs.edit()
                .putInt(KEY_CREDITS, prefs.getInt(KEY_CREDITS, 0) + grant)
                .putBoolean(KEY_INITIALIZED, true)
                .putBoolean(KEY_DEVICE_REGISTERED, true)
                .apply()
        }
    }

    fun getCredits(context: Context): Int = prefs(context).getInt(KEY_CREDITS, 0)

    /** Consumes 1 credit if available. Returns the new balance. */
    fun consumeCredit(context: Context): Int {
        val next = (getCredits(context) - 1).coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_CREDITS, next).apply()
        return next
    }

    /** Adds REWARD_CREDITS after a completed rewarded ad. Returns the new balance. */
    fun addRewardCredits(context: Context): Int {
        val next = getCredits(context) + REWARD_CREDITS
        prefs(context).edit().putInt(KEY_CREDITS, next).apply()
        return next
    }

    fun hasCredits(context: Context) = getCredits(context) > 0

    /** Returns "∞" for premium users, otherwise the numeric credit balance. */
    fun displayString(context: Context, isPremium: Boolean) =
        if (isPremium) "∞" else getCredits(context).toString()
}
