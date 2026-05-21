package com.instaget.downloader

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
import com.instaget.downloader.data.InstagramScraper
import com.instaget.downloader.data.MediaInfo
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : AppCompatActivity() {

    private val scraper = InstagramScraper()
    private val urlRegex = Regex("""https?://(?:www\.)?instagram\.com/\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = sharedText?.let { urlRegex.find(it)?.value }

        if (url == null) {
            Toast.makeText(this, "No Instagram link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "FetchIn: Fetching media…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { scraper.fetchMediaInfo(url) }

            result.onSuccess { info ->
                val dao = AppDatabase.getInstance(applicationContext).mediaDao()
                val shortcode = info.shortcode
                if (shortcode.isNotBlank()) {
                    val existing = withContext(Dispatchers.IO) { dao.getByShortcode(shortcode) }
                    if (existing != null) {
                        Toast.makeText(this@ShareReceiverActivity, "Already downloaded", Toast.LENGTH_SHORT).show()
                        finish()
                        return@onSuccess
                    }
                }
                enqueueDownload(url, info)
                Toast.makeText(this@ShareReceiverActivity, "FetchIn: Download started", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@ShareReceiverActivity, "FetchIn: ${e.message}", Toast.LENGTH_LONG).show()
            }

            finish()
        }
    }

    private fun enqueueDownload(originalUrl: String, info: MediaInfo) {
        val timestamp = System.currentTimeMillis()
        val workManager = WorkManager.getInstance(applicationContext)

        val items: List<Triple<String, String, String>> = when {
            info.mediaType == "carousel" && info.carouselItems.isNotEmpty() -> {
                info.carouselItems.mapIndexedNotNull { i, item ->
                    val url = item.videoUrl ?: item.imageUrl ?: return@mapIndexedNotNull null
                    val ext = if (item.mediaType == "video") "mp4" else "jpg"
                    Triple(url, "InstaGet_${timestamp}_${i + 1}.$ext", if (item.mediaType == "video") "VIDEO" else "IMAGE")
                }
            }
            info.videoUrl != null -> listOf(Triple(info.videoUrl, "InstaGet_${timestamp}.mp4", "VIDEO"))
            info.imageUrl != null -> listOf(Triple(info.imageUrl, "InstaGet_${timestamp}.jpg", "IMAGE"))
            else -> emptyList()
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "FetchIn: No downloadable URL found", Toast.LENGTH_SHORT).show()
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        items.forEach { (mediaUrl, filename, mediaType) ->
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_MEDIA_URL to mediaUrl,
                        DownloadWorker.KEY_FILENAME to filename,
                        DownloadWorker.KEY_SHORTCODE to info.shortcode,
                        DownloadWorker.KEY_ORIGINAL_URL to originalUrl,
                        DownloadWorker.KEY_MEDIA_TYPE to mediaType,
                        DownloadWorker.KEY_THUMBNAIL_URL to (info.thumbnailUrl ?: ""),
                        DownloadWorker.KEY_USERNAME to info.username,
                        DownloadWorker.KEY_CAPTION to info.caption,
                        DownloadWorker.KEY_NOTIFY_ON_COMPLETE to true
                    )
                )
                .build()
            workManager.enqueue(request)
        }
    }
}
