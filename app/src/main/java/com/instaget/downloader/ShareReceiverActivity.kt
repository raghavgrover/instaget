package com.instaget.downloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.instaget.downloader.billing.BillingManager
import com.instaget.downloader.data.ThreadsPostInfo
import com.instaget.downloader.data.ThreadsScraper
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : AppCompatActivity() {

    companion object {
        private const val FREE_DOWNLOAD_LIMIT = 10
        private const val PREFS_SHARE = "share_prefs"
        private const val KEY_PENDING_IG_URL = "pending_ig_url"
    }

    private val igUrlRegex = Regex("""https?://(?:www\.)?instagram\.com/\S+""")
    private val threadsUrlRegex = Regex("""https?://(?:www\.)?threads\.(?:com|net)/\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val igUrl     = sharedText?.let { igUrlRegex.find(it)?.value }
        val threadsUrl = sharedText?.let { threadsUrlRegex.find(it)?.value }

        when {
            igUrl != null     -> handleInstagramShare(igUrl)
            threadsUrl != null -> handleThreadsShare(threadsUrl)
            else -> {
                Toast.makeText(this, "No Instagram or Threads link found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ── Instagram ──────────────────────────────────────────────────────────────

    /**
     * For Instagram URLs: save the URL to SharedPreferences and open the app's
     * Home tab. HomeFragment picks up the pending URL on resume and auto-triggers
     * the download using the same reliable scraper path as manual copy-paste.
     */
    private fun handleInstagramShare(url: String) {
        // Check free download limit before doing anything
        lifecycleScope.launch {
            if (!checkDownloadLimit()) return@launch

            // Save URL for HomeFragment to pick up
            getSharedPreferences(PREFS_SHARE, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING_IG_URL, url).apply()

            // Bring the app to the foreground at the Home (IG) tab
            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("navigate_to", "home")
                }
            )
            Toast.makeText(this@ShareReceiverActivity, "InstaGet: Opening download…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ── Threads ────────────────────────────────────────────────────────────────

    private fun handleThreadsShare(url: String) {
        lifecycleScope.launch {
            if (!checkDownloadLimit()) return@launch

            Toast.makeText(this@ShareReceiverActivity, "InstaGet: Fetching Threads post…", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.IO) { ThreadsScraper().fetchPostInfo(url) }

            result.onSuccess { info ->
                enqueueThreadsDownload(url, info)
            }.onFailure { e ->
                Toast.makeText(this@ShareReceiverActivity, "InstaGet: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun enqueueThreadsDownload(originalUrl: String, info: ThreadsPostInfo) {
        val timestamp = System.currentTimeMillis()
        val shortcode = "threads_${info.postId}"

        when {
            info.mediaType == "text" -> {
                Toast.makeText(this, "Text post — open InstaGet to view", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            info.mediaType == "carousel" && info.imageUrls.size > 1 -> {
                val workManager = WorkManager.getInstance(applicationContext)
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()

                info.imageUrls.forEachIndexed { i, imageUrl ->
                    workManager.enqueue(
                        buildWorkRequest(
                            mediaUrl  = imageUrl,
                            filename  = "Threads_${timestamp}_${i + 1}.jpg",
                            shortcode = shortcode,
                            origUrl   = originalUrl,
                            mediaType = "IMAGE",
                            thumb     = "",
                            username  = info.username,
                            caption   = info.text,
                            referer   = "https://www.threads.net/",
                            cookie    = info.sessionCookies,
                            constraints = constraints
                        )
                    )
                }
                Toast.makeText(this, "InstaGet: Download started", Toast.LENGTH_SHORT).show()
                finish()
            }
            info.mediaType == "video" && info.videoUrl != null -> {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()
                WorkManager.getInstance(applicationContext).enqueue(
                    buildWorkRequest(
                        mediaUrl  = info.videoUrl,
                        filename  = "Threads_${timestamp}.mp4",
                        shortcode = shortcode,
                        origUrl   = originalUrl,
                        mediaType = "VIDEO",
                        thumb     = info.videoThumbnailUrl ?: "",
                        username  = info.username,
                        caption   = info.text,
                        referer   = "https://www.threads.net/",
                        cookie    = info.sessionCookies,
                        constraints = constraints
                    )
                )
                Toast.makeText(this, "InstaGet: Download started", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                val imageUrl = info.imageUrls.firstOrNull()
                if (imageUrl == null) {
                    Toast.makeText(this, "InstaGet: No downloadable URL found", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()
                WorkManager.getInstance(applicationContext).enqueue(
                    buildWorkRequest(
                        mediaUrl  = imageUrl,
                        filename  = "Threads_${timestamp}.jpg",
                        shortcode = shortcode,
                        origUrl   = originalUrl,
                        mediaType = "IMAGE",
                        thumb     = "",
                        username  = info.username,
                        caption   = info.text,
                        referer   = "https://www.threads.net/",
                        cookie    = info.sessionCookies,
                        constraints = constraints
                    )
                )
                Toast.makeText(this, "InstaGet: Download started", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns true if the user can still download; shows upsell Toast + finish() if not. */
    private suspend fun checkDownloadLimit(): Boolean {
        val billing = BillingManager.getInstance(applicationContext)
        if (billing.isUserSubscribed()) return true
        val count = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(applicationContext).mediaDao().getCount()
        }
        return if (count >= FREE_DOWNLOAD_LIMIT) {
            Toast.makeText(
                this,
                "Free limit reached (${FREE_DOWNLOAD_LIMIT}) — open InstaGet to subscribe",
                Toast.LENGTH_LONG
            ).show()
            finish()
            false
        } else {
            true
        }
    }

    private fun buildWorkRequest(
        mediaUrl: String, filename: String, shortcode: String, origUrl: String,
        mediaType: String, thumb: String, username: String, caption: String,
        referer: String = "", cookie: String = "",
        constraints: Constraints
    ) = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .setInputData(
            workDataOf(
                DownloadWorker.KEY_MEDIA_URL       to mediaUrl,
                DownloadWorker.KEY_FILENAME        to filename,
                DownloadWorker.KEY_SHORTCODE       to shortcode,
                DownloadWorker.KEY_ORIGINAL_URL    to origUrl,
                DownloadWorker.KEY_MEDIA_TYPE      to mediaType,
                DownloadWorker.KEY_THUMBNAIL_URL   to thumb,
                DownloadWorker.KEY_USERNAME        to username,
                DownloadWorker.KEY_CAPTION         to caption,
                DownloadWorker.KEY_REFERER         to referer,
                DownloadWorker.KEY_COOKIE          to cookie,
                DownloadWorker.KEY_NOTIFY_ON_COMPLETE to true
            )
        )
        .build()
}
