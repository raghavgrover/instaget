package com.instaget.downloader.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        private const val POST_PAGE_URL = "https://www.instagram.com/p/%s/"
        // Server-rendered data blobs on the post page
        private val JSON_SCRIPT_REGEX = Regex(
            """<script type="application/json"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val DOC_IDS = listOf(
            "8845758582119845",
            "24368985919464652",
            "10015901848480474"
        )
        private val SHORTCODE_REGEX =
            Regex("""instagram\.com/(?:[^/]+/)?(?:reels?|p|tv)/([A-Za-z0-9_-]+)""")
        // App share links (instagram.com/share/reel/<token>/, /share/p/<token>/, /share/<token>/)
        // carry an opaque token, not the shortcode — they redirect to the real post URL.
        private val SHARE_REGEX = Regex("""instagram\.com/share/""")
        // stories/username/123456789/
        private val STORY_REGEX =
            Regex("""instagram\.com/stories/[^/?]+/(\d+)""")
        private const val IG_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        // Mobile UA required for i.instagram.com private API
        private const val MOBILE_USER_AGENT =
            "Instagram/275.0.0.27.98 (iPhone; iOS 17.4.1; en_US; en-US; scale=3.00; 1125x2436; 654878038)"
        private const val STORY_API_URL_WEB = "https://www.instagram.com/api/v1/media/%s/info/"
        private const val STORY_API_URL_MOBILE = "https://i.instagram.com/api/v1/media/%s/info/"
        private val STORY_EXTRA_DOC_IDS = listOf("3802882259801936", "4428157403856178", "13748943738489442")
    }

    @Volatile private var cachedCsrfToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun extractShortcode(url: String): String? {
        // A share token is not a shortcode; it has to be resolved over the network first
        if (SHARE_REGEX.containsMatchIn(url)) return null
        SHORTCODE_REGEX.find(url)?.groupValues?.get(1)?.let { return it }
        // Story URLs encode the media as a numeric ID; convert to shortcode
        val mediaId = STORY_REGEX.find(url)?.groupValues?.get(1) ?: return null
        return mediaIdToShortcode(mediaId)
    }

    @VisibleForTesting internal fun mediaIdToShortcode(mediaId: String): String {
        var n = mediaId.toLong()
        var code = ""
        while (n > 0) {
            code = IG_ALPHABET[(n % 64).toInt()] + code
            n /= 64
        }
        return code
    }

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

    suspend fun fetchMediaInfo(inputUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val url = if (SHARE_REGEX.containsMatchIn(inputUrl)) {
            resolveShareUrl(inputUrl)
                ?: return@withContext Result.failure(Exception("Could not open this share link — copy the post link instead"))
        } else inputUrl

        val storyMediaId = STORY_REGEX.find(url)?.groupValues?.get(1)
        if (storyMediaId != null) {
            return@withContext fetchStoryInfo(storyMediaId)
        }

        val shortcode = extractShortcode(url)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Instagram URL — could not extract shortcode"))

        Log.d(TAG, "Shortcode: $shortcode")

        // Primary: the public post page embeds the full media JSON for logged-out
        // visitors. The anonymous GraphQL doc_ids below now answer 401 require_login,
        // so they are only kept as a fallback.
        try {
            val result = fetchFromPostPage(shortcode)
            if (result != null) {
                Log.d(TAG, "Got result from post page")
                return@withContext Result.success(result.copy(shortcode = shortcode))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Post page fetch failed: ${e.message}")
        }

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

    /** Follows a /share/ link's redirect and returns the real post URL, or null if it didn't resolve. */
    private fun resolveShareUrl(shareUrl: String): String? {
        val request = Request.Builder()
            .url(shareUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                Log.d(TAG, "Share link resolved to $finalUrl (HTTP ${response.code})")
                finalUrl.takeIf { !SHARE_REGEX.containsMatchIn(it) && extractShortcode(it) != null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Share link resolve failed: ${e.message}")
            null
        }
    }

    private fun fetchFromPostPage(shortcode: String): MediaInfo? {
        // Instagram only server-renders the media into the page for real browser
        // navigations: without the Sec-Fetch-* headers it returns an empty shell.
        val request = Request.Builder()
            .url(POST_PAGE_URL.format(shortcode))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        val html = client.newCall(request).execute().use { response ->
            Log.d(TAG, "Post page: HTTP ${response.code}")
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        return parsePostPage(html, shortcode)
    }

    /**
     * Pulls the media out of the post page's embedded JSON. The object sits deep inside a
     * Relay preload blob whose path changes between page variants, so rather than follow a
     * fixed path we search every JSON script for the object whose "code" is our shortcode.
     * That object has the same shape as the media-info API item, so [parseItem] reads it.
     */
    @VisibleForTesting internal fun parsePostPage(html: String, shortcode: String): MediaInfo? {
        val needle = "\"$shortcode\""
        for (match in JSON_SCRIPT_REGEX.findAll(html)) {
            val json = match.groupValues[1]
            if (!json.contains(needle)) continue
            if (!json.contains("image_versions2") && !json.contains("video_versions")) continue
            try {
                val item = findMediaItem(JSONObject(json), shortcode) ?: continue
                val info = parseItem(item)
                if (info.videoUrl == null && info.imageUrl == null && info.carouselItems.isEmpty()) continue
                return info
            } catch (e: Exception) {
                Log.w(TAG, "Post page JSON parse error: ${e.message}")
            }
        }
        return null
    }

    private fun findMediaItem(node: Any?, shortcode: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.optString("code") == shortcode &&
                    (node.has("image_versions2") || node.has("video_versions") || node.has("carousel_media"))
                ) return node
                for (key in node.keys()) {
                    findMediaItem(node.opt(key), shortcode)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findMediaItem(node.opt(i), shortcode)?.let { return it }
                }
            }
        }
        return null
    }

    private fun fetchStoryInfo(mediaId: String): Result<MediaInfo> {
        Log.d(TAG, "Fetching story, mediaId=$mediaId")
        val shortcode = mediaIdToShortcode(mediaId)
        val csrfToken = fetchCsrfToken()

        // 1. Try private API endpoints (www first — same domain as CSRF, then i.instagram.com)
        for (apiUrl in listOf(STORY_API_URL_WEB.format(mediaId), STORY_API_URL_MOBILE.format(mediaId))) {
            try {
                val reqBuilder = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .header("X-IG-App-ID", "936619743392459")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (!csrfToken.isNullOrBlank()) {
                    reqBuilder.header("X-CSRFToken", csrfToken)
                        .header("Cookie", "csrftoken=$csrfToken")
                }
                val body = client.newCall(reqBuilder.build()).execute().use { response ->
                    Log.d(TAG, "Story API $apiUrl → HTTP ${response.code}")
                    if (!response.isSuccessful) return@use null
                    val text = response.body?.string() ?: return@use null
                    if (text.trimStart().startsWith('<')) null else text  // reject HTML
                } ?: continue

                val root = JSONObject(body)
                val items = root.optJSONArray("items") ?: continue
                if (items.length() == 0) continue
                val info = flattenStoryResult(parseItem(items.getJSONObject(0)))
                if (info.mediaType == "video" && info.videoUrl == null) {
                    Log.w(TAG, "API returned video type but no URL, trying next")
                    continue
                }
                Log.d(TAG, "Story via API: type=${info.mediaType} hasVideo=${info.videoUrl != null}")
                return Result.success(info.copy(shortcode = shortcode))
            } catch (e: Exception) {
                Log.w(TAG, "Story API attempt failed: ${e.message}")
            }
        }

        // 2. Fall back to GraphQL with all doc_ids (standard + story-specific extras)
        val allDocIds = DOC_IDS + STORY_EXTRA_DOC_IDS
        for (docId in allDocIds) {
            try {
                val result = queryGraphQL(shortcode, docId, csrfToken)
                if (result != null) {
                    if (result.mediaType == "video" && result.videoUrl == null) {
                        Log.w(TAG, "doc_id=$docId returned video with no URL, continuing")
                        continue
                    }
                    // Story links refer to a single frame — never return a carousel
                    val single = flattenStoryResult(result)
                    Log.d(TAG, "Story via GraphQL doc_id=$docId: type=${single.mediaType}")
                    return Result.success(single.copy(shortcode = shortcode))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Story GraphQL doc_id=$docId failed: ${e.message}")
            }
        }

        // 3. Last resort — if we have image data for a known video story, report clearly
        // Try one final GraphQL pass accepting image-only result (story picture)
        for (docId in DOC_IDS) {
            try {
                val result = queryGraphQL(shortcode, docId, csrfToken) ?: continue
                val single = flattenStoryResult(result)
                if (single.mediaType == "video") {
                    return Result.failure(Exception(
                        "This story is a video but Instagram requires a logged-in session to download it. Only picture stories can be downloaded anonymously."
                    ))
                }
                return Result.success(single.copy(shortcode = shortcode))
            } catch (_: Exception) {}
        }

        return Result.failure(Exception("Stories can't be downloaded right now — Instagram requires a login to view them"))
    }

    @VisibleForTesting internal fun flattenStoryResult(info: MediaInfo): MediaInfo {
        if (info.mediaType != "carousel" || info.carouselItems.isEmpty()) return info
        val first = info.carouselItems.first()
        return info.copy(
            mediaType = first.mediaType,
            videoUrl = first.videoUrl,
            imageUrl = first.imageUrl,
            thumbnailUrl = first.imageUrl,
            carouselItems = emptyList()
        )
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

    @VisibleForTesting internal fun parseResponse(json: String): MediaInfo? {
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

        Log.d(TAG, "xdt typename=$typename is_video=${media.optBoolean("is_video")} has_video_url=${media.has("video_url")}")

        // Extract video_url first — its presence is the most reliable video indicator
        val videoUrl = media.optString("video_url").takeIf { it.isNotBlank() }
        val isVideo = videoUrl != null || typename == "XDTGraphVideo" || media.optBoolean("is_video", false)
        val isCarousel = typename == "XDTGraphSidecar"

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
                val cVideo = node.optString("video_url").takeIf { it.isNotBlank() }
                val cIsVideo = cVideo != null || node.optBoolean("is_video", false)
                val cImage = node.optString("display_url").takeIf { it.isNotBlank() }
                CarouselItem(if (cIsVideo) "video" else "image", cVideo, cImage)
            }
        } else emptyList()

        return MediaInfo("", username, caption, mediaType, videoUrl, imageUrl, imageUrl, carouselItems)
    }

    @VisibleForTesting internal fun parseItem(item: JSONObject): MediaInfo {
        val mediaTypeCode = item.optInt("media_type", 1)
        val username = item.optJSONObject("user")?.optString("username", "") ?: ""
        val caption = item.optJSONObject("caption")?.optString("text", "") ?: ""

        // Extract video URL first — its presence overrides the media_type code
        val videoUrl = item.optJSONArray("video_versions")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }

        val mediaType = when {
            mediaTypeCode == 8 -> "carousel"
            videoUrl != null || mediaTypeCode == 2 -> "video"
            else -> "image"
        }

        Log.d(TAG, "parseItem media_type=$mediaTypeCode resolved=$mediaType hasVideoUrl=${videoUrl != null}")

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
                val cVideo = ci.optJSONArray("video_versions")
                    ?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                val cType = if (cVideo != null || ci.optInt("media_type") == 2) "video" else "image"
                val cImage = ci.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optString("url")?.takeIf { it.isNotBlank() }
                CarouselItem(cType, cVideo, cImage)
            }
        } else emptyList()

        return MediaInfo("", username, caption, mediaType, videoUrl, imageUrl, imageUrl, carouselItems)
    }
}
