package com.instaget.downloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Transparent activity that receives ACTION_SEND text intents from other apps.
 *
 * Both Instagram and Threads shares are routed into the app's own UI so that
 * the credit/rewarded-ad gate in each fragment is properly enforced:
 *
 *  • Instagram URL → saves to pending_ig_url, navigates to Home tab.
 *    HomeFragment.onResume() picks it up, pre-fills the URL, and calls
 *    btnDownload.performClick() which runs through gateDownload().
 *
 *  • Threads URL  → saves to pending_threads_url, navigates to Threads tab.
 *    ThreadsFragment.onResume() picks it up, pre-fills the URL, and calls
 *    btnFetch.performClick() which runs through gateDownload().
 */
class ShareReceiverActivity : AppCompatActivity() {

    companion object {
        const val PREFS_SHARE = "share_prefs"
        const val KEY_PENDING_IG_URL      = "pending_ig_url"
        const val KEY_PENDING_THREADS_URL = "pending_threads_url"
    }

    private val igUrlRegex      = Regex("""https?://(?:www\.)?instagram\.com/\S+""")
    private val threadsUrlRegex = Regex("""https?://(?:www\.)?threads\.(?:com|net)/\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText  = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val igUrl       = sharedText?.let { igUrlRegex.find(it)?.value }
        val threadsUrl  = sharedText?.let { threadsUrlRegex.find(it)?.value }

        when {
            igUrl != null      -> handleShare(igUrl,      KEY_PENDING_IG_URL,      "home")
            threadsUrl != null -> handleShare(threadsUrl, KEY_PENDING_THREADS_URL, "threads")
            else -> {
                Toast.makeText(this, "No Instagram or Threads link found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Saves [url] under [prefsKey] so the target fragment can pick it up in onResume,
     * then brings the app to the foreground on the correct [navigateTo] tab.
     * Credit gating is handled entirely by the fragment's gateDownload() call.
     */
    private fun handleShare(url: String, prefsKey: String, navigateTo: String) {
        lifecycleScope.launch {
            getSharedPreferences(PREFS_SHARE, Context.MODE_PRIVATE)
                .edit().putString(prefsKey, url).apply()

            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("navigate_to", navigateTo)
                }
            )
            Toast.makeText(this@ShareReceiverActivity, "InstaGet: Opening download…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
