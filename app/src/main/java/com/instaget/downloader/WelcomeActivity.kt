package com.instaget.downloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.instaget.downloader.databinding.ActivityWelcomeBinding

/**
 * Shown once on the very first launch after install. The user must accept the
 * terms of use by tapping "Continue". A flag is persisted in SharedPreferences
 * so this screen is never shown again on the same install.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "welcome_prefs"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"

        /** True if the user has already accepted the terms on this install. */
        fun isTermsAccepted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TERMS_ACCEPTED, false)

        private fun markAccepted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TERMS_ACCEPTED, true).apply()
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
