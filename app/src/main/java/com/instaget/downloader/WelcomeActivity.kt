package com.instaget.downloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.instaget.downloader.databinding.ActivityWelcomeBinding

/**
 * Shown on the first launch after install, and again once whenever [TERMS_VERSION] is bumped
 * (the privacy policy promises in-app notice before new SDKs are added). The user must accept
 * by tapping "Continue"; the accepted version is persisted in SharedPreferences.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "welcome_prefs"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"   // v1: boolean, pre-1.7
        private const val KEY_TERMS_VERSION = "terms_version"

        /** Bump when the terms/privacy policy change materially. 2 = Firebase + device check (v1.7). */
        private const val TERMS_VERSION = 2

        private fun acceptedVersion(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_TERMS_VERSION, if (prefs.getBoolean(KEY_TERMS_ACCEPTED, false)) 1 else 0)
        }

        /** True if the user has accepted the current terms on this install. */
        fun isTermsAccepted(context: Context): Boolean = acceptedVersion(context) >= TERMS_VERSION

        private fun markAccepted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TERMS_ACCEPTED, true).putInt(KEY_TERMS_VERSION, TERMS_VERSION).apply()
        }
    }

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Light purple status bar with white icons (same as MainActivity).
        // The root has padding only at the top (status bar). The bottom navigation
        // bar inset is applied to the Continue button container so it lifts above
        // the phone's nav bar while keeping the area white (not purple).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarTop)
            insets
        }
        val basePaddingBottom = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, insets ->
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = basePaddingBottom + navBarBottom)
            insets
        }
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        // Existing users who accepted older terms see what changed.
        if (acceptedVersion(this) > 0) binding.tvPolicyUpdate.visibility = View.VISIBLE

        binding.tvTerms.setOnClickListener {
            startActivity(
                Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_URL, getString(R.string.url_terms_of_use))
                    putExtra(WebViewActivity.EXTRA_TITLE, "Terms of Use")
                }
            )
        }

        binding.tvPrivacy.setOnClickListener {
            startActivity(
                Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_URL, getString(R.string.url_privacy_policy))
                    putExtra(WebViewActivity.EXTRA_TITLE, "Privacy Policy")
                }
            )
        }

        binding.btnContinue.setOnClickListener {
            markAccepted(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /** Disable back button — user must explicitly accept the terms. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // No-op
    }
}
