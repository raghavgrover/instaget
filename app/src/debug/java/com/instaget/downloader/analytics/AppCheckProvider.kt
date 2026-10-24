package com.instaget.downloader.analytics

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds can't pass Play Integrity (not installed from Play). The debug provider logs a
 * token on first run ("DebugAppCheckProvider: Enter this debug secret ...") — register it under
 * Firebase console → App Check → Apps → Manage debug tokens.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
