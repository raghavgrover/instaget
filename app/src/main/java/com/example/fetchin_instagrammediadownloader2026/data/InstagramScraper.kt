package com.example.fetchin_instagrammediadownloader2026.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MediaInfo(
    val shortcode: String,
    val username: String,
    val caption: String,
    val mediaType: String,          // "video", "image", "carousel"
    val videoUrl: String?,
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val carouselItems: List<CarouselItem> = emptyList()
)

data class CarouselItem(
    val mediaType: String,
    val videoUrl: String?,
    val imageUrl: String?
)

class InstagramScraper {

    companion object {
        private const val TAG = "InstagramScraper"
        private const val GRAPHQL_URL = "https://www.instagram.com/graphql/query"
        private const val HOME_URL = "https://www.instagram.com/"
        private val DOC_IDS = listOf(
            "8845758582119845",
            "24368985919464652",
            "10015901848480474"
        )
        private val SHORTCODE_REGEX =
            Regex("""instagram\.com/(?:[^/]+/)?(?:reel|p|tv)/([A-Za-z0-9_-]+)""")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    @Volatile private var cachedCsrfToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun extractShortcode(url: String): String? =
        SHORTCODE_REGEX.find(url)?.groupValues?.get(1)

    private fun fetchCsrfToken(): String? {
        cachedCsrfToken?.let { return it }
        val request = Request.Builder()
            .url(HOME_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Home GET: HTTP ${response.code}")
                val setCookies = response.headers("Set-Cookie")
                for (cookie in setCookies) {
                    if (cookie.startsWith("csrftoken=")) {
                        val token = cookie.substringAfter("csrftoken=").substringBefore(";")
                        Log.d(TAG, "CSRF token: $token")
                        cachedCsrfToken = token
                        return token
                    }
                }
                // Also scan body for csrftoken in page source as fallback
                val body = response.body?.string() ?: return null
                val match = Regex(""""csrf_token":"([^"]+)"""").find(body)
                    ?: Regex("""csrftoken=([A-Za-z0-9]+)""").find(body)
                match?.groupValues?.get(1)?.also {
                    Log.d(TAG, "CSRF token from body: $it")
                    cachedCsrfToken = it
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch CSRF token: ${e.message}")
            null
        }
    }

    suspend fun fetchMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(url)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Instagram URL — could not extract shortcode"))

        Log.d(TAG, "Shortcode: $shortcode")

        val csrfToken = fetchCsrfToken()
        if (csrfToken == null) {
            Log.w(TAG, "No CSRF token obtained, proceeding anyway")
        } else {
            Log.d(TAG, "Using CSRF token: $csrfToken")
        }

        for (docId in DOC_IDS) {
            try {
                val result = queryGraphQL(shortcode, docId, csrfToken)
                if (result != null) {
                    Log.d(TAG, "Got result with doc_id=$docId")
                    return@withContext Result.success(result.copy(shortcode = shortcode))
                }
            } catch (e: Exception) {
                Log.w(TAG, "doc_id=$docId failed: ${e.message}")
            }
        }

        Result.failure(Exception("Could not fetch media — post may be private or Instagram changed their API"))
    }

    private fun queryGraphQL(shortcode: String, docId: String, csrfToken: String?): MediaInfo? {
        val body = FormBody.Builder()
            .addEncoded("variables", """{"shortcode":"$shortcode"}""")
            .addEncoded("doc_id", docId)
            .build()

        val requestBuilder = Request.Builder()
            .url(GRAPHQL_URL)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Ig-App-Id", "936619743392459")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (!csrfToken.isNullOrBlank()) {
            requestBuilder
                .header("X-CSRFToken", csrfToken)
                .header("Cookie", "csrftoken=$csrfToken")
        }

        val responseText = client.newCall(requestBuilder.build()).execute().use { response ->
            Log.d(TAG, "HTTP ${response.code} for doc_id=$docId")
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        return parseResponse(responseText)
    }

    private fun parseResponse(json: String): MediaInfo? {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return null

            // Try new path first: data.xdt_shortcode_media (direct object)
            val xdtShortcode = data.optJSONObject("xdt_shortcode_media")
            if (xdtShortcode != null) {
                return parseXdtShortcodeMedia(xdtShortcode)
            }

            // Fallback: data.xdt_api__v1__media__shortcode__web_info.items[0]
            val webInfo = data.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?: return null
            val items = webInfo.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            parseItem(items.getJSONObject(0))
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    private fun parseXdtShortcodeMedia(media: JSONObject): MediaInfo {
        val typename = media.optString("__typename", "")
        val username = media.optJSONObject("owner")?.optString("username", "") ?: ""
        val caption = media.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text", "") ?: ""

        val isVideo = typename == "XDTGraphVideo" || media.optBoolean("is_video", false)
        val isCarousel = typename == "XDTGraphSidecar"

        val videoUrl = if (isVideo) media.optString("video_url").takeIf { it.isNotBlank() } else null
        val imageUrl = media.optString("display_url").takeIf { it.isNotBlank() }
            ?: media.optString("thumbnail_src").takeIf { it.isNotBlank() }

        val mediaType = when {
            isCarousel -> "carousel"
            isVideo -> "video"
            else -> "image"
        }

        val carouselItems = if (isCarousel) {
            val edges = media.optJSONObject("edge_sidecar_to_children")
                ?.optJSONArray("edges") ?: return MediaInfo("", username, caption, mediaType, videoUrl, imageUrl, imageUrl)
            (0 until edges.length()).map { i ->
                val node = edges.getJSONObject(i).getJSONObject("node")
                val cIsVideo = node.optBoolean("is_video", false)
                val cVideo = if (cIsVideo) node.optString("video_url").takeIf { it.isNotBlank() } else null
                val cImage = node.optString("display_url").takeIf { it.isNotBlank() }
                CarouselItem(if (cIsVideo) "video" else "image", cVideo, cImage)
            }
        } else emptyList()

        return MediaInfo("", username, caption, mediaType, videoUrl, imageUrl, imageUrl, carouselItems)
    }

    private fun parseItem(item: JSONObject): MediaInfo {
        val mediaTypeCode = item.optInt("media_type", 1)
        val username = item.optJSONObject("user")?.optString("username", "") ?: ""
        val caption = item.optJSONObject("caption")?.optString("text", "") ?: ""

        val mediaType = when (mediaTypeCode) {
            2 -> "video"
            8 -> "carousel"
            else -> "image"
        }

        val videoUrl = item.optJSONArray("video_versions")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }

        val imageUrl = item.optJSONObject("image_versions2")
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }

        val carouselItems = if (mediaTypeCode == 8) {
            val arr = item.optJSONArray("carousel_media") ?: return MediaInfo(
                "", username, caption, mediaType, videoUrl, imageUrl, imageUrl
            )
            (0 until arr.length()).map { i ->
                val ci = arr.getJSONObject(i)
                val cType = if (ci.optInt("media_type") == 2) "video" else "image"
                val cVideo = ci.optJSONArray("video_versions")
                    ?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                val cImage = ci.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optString("url")?.takeIf { it.isNotBlank() }
                CarouselItem(cType, cVideo, cImage)
            }
        } else emptyList()

        return MediaInfo("", username, caption, mediaType, videoUrl, imageUrl, imageUrl, carouselItems)
    }
}
