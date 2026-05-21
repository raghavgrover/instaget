package com.instaget.downloader.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.instaget.downloader.data.db.AppDatabase
import com.instaget.downloader.data.db.MediaItem
import com.instaget.downloader.network.InstagramApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MediaInfo(
    val mediaUrls: List<String>,
    val mediaTypes: List<String>,
    val thumbnailUrl: String?
)

sealed class DownloadResult {
    data class Success(val savedItems: List<MediaItem>) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

class DownloadRepository(private val context: Context) {

    companion object {
        private const val TAG = "DownloadRepo"
        private const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }

    private val apiService = InstagramApiService.create()
    private val dao = AppDatabase.getInstance(context).mediaDao()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Referer", "https://www.instagram.com/")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            chain.proceed(req)
        }
        .build()

    fun isValidInstagramUrl(url: String): Boolean {
        if (!url.contains("instagram.com")) return false
        // Accept both full URLs and short share links (no path check needed for reel short links)
        return url.contains("/p/") || url.contains("/reel/") ||
                url.contains("/tv/") || url.contains("/stories/") ||
                url.contains("/reels/") || url.matches(Regex(".*instagram\\.com/[^/]+/?.*"))
    }

    suspend fun fetchMediaInfo(postUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Try oEmbed — non-fatal, Instagram often rejects unauthenticated calls
            var thumbnailUrl: String? = null
            try {
                val oEmbedResponse = apiService.getOEmbed(postUrl)
                if (oEmbedResponse.isSuccessful) {
                    thumbnailUrl = oEmbedResponse.body()?.thumbnailUrl
                    Log.d(TAG, "oEmbed thumbnail: $thumbnailUrl")
                }
            } catch (e: Exception) {
                Log.w(TAG, "oEmbed failed (non-fatal): ${e.message}")
            }

            // Step 2: Fetch the post HTML and extract CDN media URLs
            val mediaUrls = mutableListOf<String>()
            val mediaTypes = mutableListOf<String>()

            val html = fetchHtml(postUrl)
            Log.d(TAG, "HTML length: ${html.length}")

            if (html.isNotEmpty()) {
                extractMedia(html, mediaUrls, mediaTypes)

                // Also grab og:image as thumbnail if we don't have one yet
                if (thumbnailUrl == null) {
                    thumbnailUrl = extractOgImage(html)
                }
            }

            // Step 3: Fallback — use thumbnail if nothing found from HTML
            if (mediaUrls.isEmpty() && thumbnailUrl != null) {
                mediaUrls.add(thumbnailUrl)
                mediaTypes.add("IMAGE")
            }

            Log.d(TAG, "Found ${mediaUrls.size} media URLs, types: $mediaTypes")

            if (mediaUrls.isEmpty()) {
                val reason = when {
                    html.contains("login") || html.contains("Log in") ->
                        "Private account — cannot download"
                    html.isEmpty() -> "No internet connection"
                    else -> "Media not found — post may be private or unavailable"
                }
                return@withContext Result.failure(IOException(reason))
            }

            Result.success(MediaInfo(mediaUrls, mediaTypes, thumbnailUrl))
        } catch (e: Exception) {
            Log.e(TAG, "fetchMediaInfo error", e)
            Result.failure(e)
        }
    }

    private fun fetchHtml(url: String): String {
        return try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "HTML fetch HTTP ${response.code} for $url")
                if (response.isSuccessful) response.body?.string() ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML fetch failed: ${e.message}")
            ""
        }
    }

    private fun extractMedia(
        html: String,
        mediaUrls: MutableList<String>,
        mediaTypes: MutableList<String>
    ) {
        fun String.unescape() = replace("\\u0026", "&").replace("\\/", "/")

        // --- VIDEO patterns (highest priority) ---
        val videoPatterns = listOf(
            Regex(""""video_url"\s*:\s*"([^"]+)""""),
            Regex(""""playback_url"\s*:\s*"([^"]+)""""),
            Regex(""""__typename"\s*:\s*"GraphVideo"[^}]+"display_url"\s*:\s*"([^"]+)""""),
            Regex("""<meta\s+property="og:video"\s+content="([^"]+)""""),
            Regex("""<meta\s+content="([^"]+)"\s+property="og:video""""),
        )
        for (pattern in videoPatterns) {
            pattern.findAll(html).forEach { m ->
                val u = m.groupValues[1].unescape()
                if (u.startsWith("http") && u !in mediaUrls) {
                    mediaUrls += u; mediaTypes += "VIDEO"
                }
            }
            if (mediaUrls.isNotEmpty()) return
        }

        // --- IMAGE patterns ---
        val imagePatterns = listOf(
            Regex(""""display_url"\s*:\s*"([^"]+)""""),
            Regex(""""thumbnail_src"\s*:\s*"([^"]+)""""),
            Regex(""""src"\s*:\s*"(https://[^"]+\.(jpg|jpeg|png|webp)[^"]*)""""),
            Regex("""<meta\s+property="og:image"\s+content="([^"]+)""""),
            Regex("""<meta\s+content="([^"]+)"\s+property="og:image""""),
        )
        for (pattern in imagePatterns) {
            pattern.findAll(html).forEach { m ->
                val u = m.groupValues[1].unescape()
                if (u.startsWith("http") && u !in mediaUrls &&
                    (u.contains("cdninstagram") || u.contains("fbcdn") || u.contains("instagram"))
                ) {
                    mediaUrls += u; mediaTypes += "IMAGE"
                }
            }
            if (mediaUrls.isNotEmpty()) return
        }
    }

    private fun extractOgImage(html: String): String? {
        return Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content="([^"]+)"\s+property="og:image"""")
                .find(html)?.groupValues?.get(1)
    }

    suspend fun downloadAndSave(
        originalUrl: String,
        mediaInfo: MediaInfo,
        onlyFirst: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val urlsToDownload = if (onlyFirst) listOf(mediaInfo.mediaUrls.first()) else mediaInfo.mediaUrls
        val typesToDownload = if (onlyFirst) listOf(mediaInfo.mediaTypes.first()) else mediaInfo.mediaTypes
        val savedItems = mutableListOf<MediaItem>()

        urlsToDownload.forEachIndexed { index, mediaUrl ->
            val mediaType = typesToDownload.getOrElse(index) { "IMAGE" }
            val isVideo = mediaType == "VIDEO"

            try {
                val request = Request.Builder().url(mediaUrl).build()
                val responseBytes = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to download media: HTTP ${response.code}")
                    response.body?.bytes() ?: throw IOException("Empty response body")
                }

                val timestamp = System.currentTimeMillis()
                val extension = if (isVideo) "mp4" else "jpg"
                val fileName = "InstaGet_${timestamp}_${index + 1}.$extension"

                val savedUri = saveToMediaStore(fileName, isVideo, responseBytes)
                    ?: throw IOException("Failed to save to MediaStore")

                val thumbnailPath = if (index == 0) mediaInfo.thumbnailUrl ?: savedUri.toString()
                else savedUri.toString()

                val item = MediaItem(
                    shortcode = "",
                    originalUrl = originalUrl,
                    localPath = savedUri.toString(),
                    mediaType = mediaType,
                    thumbnailPath = thumbnailPath,
                    fileName = fileName,
                    downloadedAt = timestamp,
                    isPremium = urlsToDownload.size > 1
                )
                dao.insert(item)
                savedItems.add(item)
                onProgress(((index + 1) * 100) / urlsToDownload.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading item $index: ${e.message}")
                if (savedItems.isEmpty()) {
                    return@withContext DownloadResult.Error(e.message ?: "Download failed")
                }
            }
        }

        if (savedItems.isEmpty()) DownloadResult.Error("No media could be downloaded")
        else DownloadResult.Success(savedItems)
    }

    private fun saveToMediaStore(fileName: String, isVideo: Boolean, bytes: ByteArray): Uri? {
        val collection: Uri
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            if (isVideo) {
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/InstaGet")
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/InstaGet")
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
