package com.instaget.downloader

import android.app.Application
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.instaget.downloader.ads.RewardedInterstitialManager

class InstaGetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this) { status ->
            Log.d("AdMob", "Initialized: ${status.adapterStatusMap}")
            RewardedInterstitialManager.getInstance(this).preload()
        }
    }
}
