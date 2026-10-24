package com.instaget.downloader.ads

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.instaget.downloader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Asks the Supabase `claim-credits` function whether this device may receive the one-time
 * free-credit grant. The server remembers devices, so clearing app data can't re-grant credits.
 *
 * Only a salted SHA-256 of Settings.Secure.ANDROID_ID is sent. Since Android 8 that ID is scoped
 * to (app signing key, user, device): stable across clear-data and reinstall, reset by a
 * factory reset, and never comparable with other apps' IDs or the Advertising ID.
 */
internal object CreditGrantClient {

    private const val TAG = "CreditGrant"
    private const val DEVICE_KEY_SALT = "instaget:"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Needs both the Supabase URL and Firebase (for App Check). Release builds can't be made
     * without google-services.json, so this is only false in local debug builds.
     */
    fun isConfigured(context: Context): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && FirebaseApp.getApps(context).isNotEmpty()

    /** Returns the credits granted by the server (initial amount or 0), or null if the claim couldn't complete. */
    suspend fun claim(context: Context): Int? = withContext(Dispatchers.IO) {
        try {
            val deviceKey = deviceKey(context) ?: return@withContext null
            val appCheckToken = appCheckToken(context) ?: return@withContext null

            val body = JSONObject().put("deviceKey", deviceKey).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/claim-credits")
                .post(body)
                .header("X-Firebase-AppCheck", appCheckToken)
                .apply {
                    if (BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
                        header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Claim failed: HTTP ${response.code} $text")
                    return@withContext null
                }
                JSONObject(text).getInt("grant").coerceAtLeast(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Claim failed: ${e.message}")
            null
        }
    }

    @SuppressLint("HardwareIds") // Used only for fraud prevention, hashed, never for ads.
    private fun deviceKey(context: Context): String? {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((DEVICE_KEY_SALT + androidId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun appCheckToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return try {
            withTimeout(10_000) {
                FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
            }
        } catch (e: Exception) {
            Log.w(TAG, "App Check token unavailable: ${e.message}")
            null
        }
    }
}
