package com.instaget.downloader

import android.app.Application
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.instaget.downloader.ads.CreditsManager
import com.instaget.downloader.ads.RewardedInterstitialManager
import com.instaget.downloader.analytics.Analytics

class InstaGetApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        // Settle the one-time free-credit grant with the server early; the download gate
        // retries if this hasn't finished or failed.
        appScope.launch { CreditsManager.ensureInitialized(this@InstaGetApplication) }
        MobileAds.initialize(this) { status ->
            Log.d("AdMob", "Initialized: ${status.adapterStatusMap}")
            RewardedInterstitialManager.getInstance(this).preload()
        }
    }

    /** Skipped entirely when the build has no google-services.json (local debug builds). */
    private fun initFirebase() {
        if (FirebaseApp.getApps(this).isEmpty()) return
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
        Analytics.init(this)
    }
}
