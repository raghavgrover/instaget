package com.instaget.downloader.ads

import com.instaget.downloader.BuildConfig

/**
 * Ad unit IDs — test IDs used in debug, real IDs in release.
 * To use real IDs: add them to local.properties (gitignored):
 *   ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX
 *   AD_BANNER_IG=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
 *   AD_BANNER_THREADS=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
 *   AD_REWARDED_INTERSTITIAL=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
 *   AD_NATIVE_THREADS=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
 */
object AdConfig {
    val BANNER_IG: String get() = BuildConfig.AD_BANNER_IG
    val BANNER_THREADS: String get() = BuildConfig.AD_BANNER_THREADS
    val REWARDED_INTERSTITIAL: String get() = BuildConfig.AD_REWARDED_INTERSTITIAL
    val NATIVE_THREADS: String get() = BuildConfig.AD_NATIVE_THREADS
}
