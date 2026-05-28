package com.instaget.downloader.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ThreadsPostInfo(
    val postId: String,
    val username: String,
    val text: String,
    val mediaType: String,          // "text", "image", "video", "carousel"
    val videoUrl: String?,
    val imageUrls: List<String> = emptyList(),
    val videoThumbnailUrl: String?,
    val sessionCookies: String = ""
)

class ThreadsScraper {

    companion object {
        private const val TAG = "ThreadsScraper"
        private val POST_REGEX =
            Regex("""threads\.(?:net|com)/@([^/?#\s]+)/post/([A-Za-z0-9_-]+)""")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val IG_APP_ID = "238260118697367"
        private const val GRAPHQL_URL = "https://www.threads.net/api/graphql"

        private val CDN_IMAGE_REGEX = Regex(
            """https://(?:scontent[^"'\s]*\.cdninstagram\.com|instagram\.[^"'\s]*\.fbcdn\.net)/v/[^"'\s]+\.jpg[^"'\s]*"""
        )
        private val CDN_VIDEO_REGEX = Regex(
            """https://(?:video[^"'\s]*\.cdninstagram\.com|scontent[^"'\s]*\.cdninstagram\.com|instagram\.[^"'\s]*\.fbcdn\.net)/(?:o1/)?v/[^"'\s]+\.mp4[^"'\s]*"""
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isThreadsUrl(url: String) = POST_REGEX.containsMatchIn(url)
    fun extractPostId(url: String) = POST_REGEX.find(url)?.groupValues?.get(2)
    fun extractUsername(url: String) = POST_REGEX.find(url)?.groupValues?.get(1)

    suspend fun fetchPostInfo(url: String): Result<ThreadsPostInfo> = withContext(Dispatchers.IO) {
        val postId = extractPostId(url)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Invalid Threads URL — expected threads.net/@username/post/ID")
            )
        val urlUsername = extractUsername(url) ?: ""

        return@withContext try {
            val (html, cookies) = fetchPage(url)
            if (html.isBlank()) {
                return@withContext Result.failure(Exception("Empty response from Threads"))
            }

            val lsdToken = extractLsdToken(html)
            val dynamicDocIds = extractDocIdsFromHtml(html)
            val numericMediaId = shortcodeToMediaId(postId)
            Log.d(TAG, "HTML=${html.length}chars LSD=${lsdToken?.take(12) ?: "NOT_FOUND"} dynamicDocIds=${dynamicDocIds.size} mediaId=$numericMediaId")

            val info = parseFromOGTags(html, postId, urlUsername)
                ?: return@withContext Result.failure(
                    Exception("Could not parse post — it may be private or Threads blocked the request")
                )

            val enriched = tryEnrichCarousel(html, info).copy(sessionCookies = cookies)

            val final = if (enriched.videoUrl == null && enriched.mediaType != "text") {
                tryFetchVideo(html, postId, numericMediaId, lsdToken, dynamicDocIds, enriched)
            } else {
                enriched
            }

            Result.success(final)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPostInfo error", e)
            Result.failure(Exception("Failed to fetch Threads post: ${e.message}"))
        }
    }

    // ── Video fetching ──────────────────────────────────────────────────────────

    private fun tryFetchVideo(
        html: String,
        postId: String,
        numericMediaId: Long,
        lsdToken: String?,
        dynamicDocIds: List<String>,
        info: ThreadsPostInfo
    ): ThreadsPostInfo {
        val cookies = info.sessionCookies
        val csrfToken = extractCsrfToken(cookies)

        // Strategy A: scan SSR data in HTML for video URL using numeric media ID
        val ssrVideo = extractVideoFromSsrData(html, numericMediaId)
        if (ssrVideo != null) {
            Log.d(TAG, "Strategy A SUCCESS (SSR): ${ssrVideo.take(80)}")
            return info.copy(
                mediaType = "video",
                videoUrl = ssrVideo,
                videoThumbnailUrl = info.imageUrls.firstOrNull(),
                imageUrls = emptyList()
            )
        }

        if (lsdToken.isNullOrBlank()) {
            Log.w(TAG, "No LSD token — cannot try GraphQL")
            return info
        }

        // Build the full list of doc_ids to try:
        // dynamic ones from the page HTML first (most likely current), then known fallbacks
        val knownDocIds = listOf("6232751443445612", "7327598140644372", "25531498899829322")
        val allDocIds = (dynamicDocIds + knownDocIds).distinct()
        Log.d(TAG, "Trying ${allDocIds.size} doc_ids via GraphQL")

        // Strategy B: GraphQL with each doc_id × multiple variable formats
        // Variable formats to try (postID shortcode, numeric ID, different key names)
        val variableFormats = listOf(
            """{"postID":"$postId"}""",
            """{"postID":"$numericMediaId"}""",
            """{"mediaID":"$numericMediaId"}""",
            """{"threadId":"$postId"}""",
            """{"id":"$postId"}"""
        )

        for (docId in allDocIds) {
            for (vars in variableFormats) {
                val r = graphQLRequest(docId, vars, lsdToken, cookies, csrfToken, info)
                if (r.videoUrl != null) {
                    Log.d(TAG, "Strategy B SUCCESS doc_id=$docId vars=$vars")
                    return r
                }
            }
        }

        Log.d(TAG, "All strategies exhausted — keeping mediaType=${info.mediaType}")
        return info
    }

    // ── SSR data extraction ─────────────────────────────────────────────────────

    /**
     * The Threads page HTML contains Relay/SSR JSON with video_versions embedded.
     * The CDN video URL may NOT have a .mp4 extension — extract it by finding the
     * "url" field directly after the "video_versions" key.
     */
    private fun extractVideoFromSsrData(html: String, mediaId: Long): String? {
        // Log markers for diagnostics
        val markers = listOf("video_versions", "video_url", "dash_manifest", ".m3u8", "\"media_type\":2")
        Log.d(TAG, "HTML video markers: ${markers.filter { html.contains(it) }}")

        // Primary: find "video_versions" then grab the first "url" value after it
        // The URL may or may not end in .mp4 — match any https CDN URL
        val versionsIdx = html.indexOf("\"video_versions\"")
        if (versionsIdx >= 0) {
            val ctx = html.substring(versionsIdx, minOf(html.length, versionsIdx + 3000))
            Log.d(TAG, "video_versions context (600c): ${ctx.take(600)}")

            // Pattern: "url":"https:..." — must start with https: and end at the closing quote
            // Use [^"] so we don't need the full URL to fit in a fixed window
            val urlMatch = Regex(""""url"\s*:\s*"(https:[^"]+)"""").find(ctx)
            if (urlMatch != null) {
                val raw = urlMatch.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                Log.d(TAG, "Candidate video URL: ${raw.take(120)}")
                // Accept any https URL from a known CDN or with a video-like path
                if (raw.startsWith("https://") &&
                    (raw.contains("cdninstagram.com") || raw.contains("fbcdn.net") ||
                     raw.contains("threads.net") || raw.contains("/v/"))) {
                    return raw
                }
            }
        }

        // Fallback: JSON-escaped CDN URL — must contain .mp4 to avoid picking up thumbnails
        val escapedRegex = Regex(
            """https:\\/\\/(?:video[^"'\\]+\.cdninstagram\.com|scontent[^"'\\]+\.cdninstagram\.com|instagram\.[^"'\\]+\.fbcdn\.net)\\/(?:o1\\/)?v\\/[^"'\\]+\.mp4[^"'\\]*"""
        )
        val escaped = escapedRegex.find(html)?.value?.replace("\\/", "/")?.replace("&amp;", "&")
        if (escaped != null) {
            Log.d(TAG, "Found JSON-escaped video URL: ${escaped.take(100)}")
            return escaped
        }

        return null
    }

    // ── GraphQL request ─────────────────────────────────────────────────────────

    private fun graphQLRequest(
        docId: String,
        variables: String,
        lsdToken: String,
        cookies: String,
        csrfToken: String,
        info: ThreadsPostInfo
    ): ThreadsPostInfo {
        return try {
            val formBody = FormBody.Builder()
                .add("lsd", lsdToken)
                .add("variables", variables)
                .add("doc_id", docId)
                .build()

            val req = Request.Builder()
                .url(GRAPHQL_URL)
                .post(formBody)
                .header("X-FB-LSD", lsdToken)
                .header("X-ASBD-ID", "129477")
                .header("X-IG-App-ID", IG_APP_ID)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "*/*, application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.threads.net/")
                .header("Origin", "https://www.threads.net")
                .apply {
                    if (cookies.isNotBlank()) header("Cookie", cookies)
                    if (csrfToken.isNotBlank()) header("X-CSRFToken", csrfToken)
                }
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string() ?: ""
                // Only log non-trivial errors (not "not found") to keep log readable
                if (body.contains("video_versions") || (!body.contains("not found") && !body.contains("soft-deleted"))) {
                    Log.d(TAG, "GQL doc=$docId vars=${variables.take(30)} → ${response.code} body[0..300]=${body.take(300)}")
                }
                if (!response.isSuccessful) return info
                applyVideoFromJson(body, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GQL error: ${e.message}")
            info
        }
    }

    // ── Dynamic doc_id extraction ───────────────────────────────────────────────

    /**
     * Extract GraphQL doc_ids embedded in the Threads page HTML/JS.
     * These are 13–17 digit numbers that appear as values for "doc_id", "queryId", or "__id".
     */
    private fun extractDocIdsFromHtml(html: String): List<String> {
        val pattern = Regex(""""(?:doc_id|queryId|__id)"\s*:\s*"?(\d{13,17})"?""")
        val ids = pattern.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it.length in 13..17 }
            .toList()
        Log.d(TAG, "Dynamic doc_ids from HTML (${ids.size}): ${ids.take(10)}")
        return ids
    }

    // ── LSD token extraction ────────────────────────────────────────────────────

    private fun extractLsdToken(html: String): String? {
        Regex(""""LSD"\s*,\s*\[\s*\]\s*,\s*\{"token"\s*:\s*"([^"]+)"\}""")
            .find(html)?.groupValues?.get(1)?.also { return it }
        Regex(""""LSD"\s*:\s*\{"token"\s*:\s*"([^"]+)"\}""")
            .find(html)?.groupValues?.get(1)?.also { return it }
        Regex("""name="lsd"\s+value="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.also { return it }
        // Broader fallback: look for lsd key-value anywhere
        Regex(""""lsd"\s*:\s*"([A-Za-z0-9_\-]{6,24})"""")
            .find(html)?.groupValues?.get(1)?.also { return it }
        return null
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────

    private fun applyVideoFromJson(json: String, info: ThreadsPostInfo): ThreadsPostInfo {
        val videoUrl = extractVideoUrlFromJson(json) ?: return info
        val thumbnailUrl = extractThumbnailFromJson(json) ?: info.imageUrls.firstOrNull()
        Log.d(TAG, "Video URL from JSON: ${videoUrl.take(100)}")
        return info.copy(
            mediaType = "video",
            videoUrl = videoUrl,
            videoThumbnailUrl = thumbnailUrl,
            imageUrls = emptyList()
        )
    }

    private fun extractVideoUrlFromJson(json: String): String? {
        val versionsIdx = json.indexOf("\"video_versions\"")
        if (versionsIdx < 0) return null
        val urlIdx = json.indexOf("\"url\"", versionsIdx)
        if (urlIdx < 0) return null
        val colon = json.indexOf(':', urlIdx + 5)
        if (colon < 0) return null
        val q1 = json.indexOf('"', colon + 1); if (q1 < 0) return null
        val q2 = json.indexOf('"', q1 + 1);   if (q2 < 0) return null
        return json.substring(q1 + 1, q2)
            .replace("\\/", "/").replace("&amp;", "&")
            .takeIf { it.contains("cdninstagram") || it.contains("fbcdn") || it.contains("mp4") }
    }

    private fun extractThumbnailFromJson(json: String): String? {
        val candidatesIdx = json.indexOf("\"candidates\"")
        if (candidatesIdx < 0) return null
        val urlIdx = json.indexOf("\"url\"", candidatesIdx)
        if (urlIdx < 0) return null
        val colon = json.indexOf(':', urlIdx + 5)
        if (colon < 0) return null
        val q1 = json.indexOf('"', colon + 1); if (q1 < 0) return null
        val q2 = json.indexOf('"', q1 + 1);   if (q2 < 0) return null
        return json.substring(q1 + 1, q2).replace("\\/", "/").replace("&amp;", "&")
    }

    // ── Page fetch ──────────────────────────────────────────────────────────────

    private fun findVideoUrlInHtml(html: String): String? {
        val plain = CDN_VIDEO_REGEX.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinctBy { it.substringBefore("?").substringAfterLast("/") }
            .firstOrNull()
        if (plain != null) { Log.d(TAG, "Video (plain): ${plain.take(100)}"); return plain }
        Log.d(TAG, "findVideoUrlInHtml: no .mp4 in HTML")
        return null
    }

    private fun fetchPage(url: String): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .build()
        return client.newCall(request).execute().use { response ->
            Log.d(TAG, "GET $url → HTTP ${response.code}")
            val cookies = response.headers.values("Set-Cookie")
                .joinToString("; ") { it.substringBefore(";").trim() }
                .also { Log.d(TAG, "Cookies: ${it.take(80)}…") }
            Pair(response.body?.string() ?: "", cookies)
        }
    }

    // ── OG tag parsing ──────────────────────────────────────────────────────────

    private fun parseFromOGTags(html: String, postId: String, urlUsername: String): ThreadsPostInfo? {
        val og = parseOGProperties(html)
        if (og.isEmpty()) { Log.w(TAG, "No OG tags found"); return null }

        val title    = og["title"] ?: ""
        val desc     = og["description"] ?: ""
        val imageUrl = og["image"]?.replace("&amp;", "&")
        val ogType   = og["type"] ?: "article"

        val ogVideoUrl = (og["video:secure_url"] ?: og["video:url"] ?: og["video"])?.replace("&amp;", "&")
        val videoUrl   = ogVideoUrl ?: findVideoUrlInHtml(html)

        Log.d(TAG, "OG type=$ogType hasImage=${imageUrl != null} hasVideo=${videoUrl != null}")

        val username = extractUsernameFromTitle(title) ?: urlUsername
        val text = (desc.ifBlank { title.substringAfter("Threads: \"").removeSuffix("\"") }).htmlUnescape()

        val mediaType = when {
            videoUrl != null                          -> "video"
            imageUrl != null && isPostImage(imageUrl) -> "image"
            else                                      -> "text"
        }

        return ThreadsPostInfo(
            postId = postId, username = username, text = text,
            mediaType = mediaType, videoUrl = videoUrl,
            imageUrls = if (mediaType == "image" && imageUrl != null) listOf(imageUrl) else emptyList(),
            videoThumbnailUrl = if (mediaType == "video") imageUrl else null
        )
    }

    // ── Carousel enrichment ─────────────────────────────────────────────────────

    private fun tryEnrichCarousel(html: String, info: ThreadsPostInfo): ThreadsPostInfo {
        if (info.mediaType == "video") return info
        val anchorUrl = info.imageUrls.firstOrNull() ?: return info
        val bucket = Regex("""/(t\d+\.\d+-\d+)/""").find(anchorUrl)?.groupValues?.get(1)
            ?: return info

        Log.d(TAG, "Carousel scan bucket=$bucket")
        val bucketRegex = Regex(
            """https://(?:scontent[^"'\s]*\.cdninstagram\.com|instagram\.[^"'\s]*\.fbcdn\.net)/v/""" +
            Regex.escape(bucket) + """/[^"'\s]+\.jpg[^"'\s]*"""
        )
        val found = bucketRegex.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .distinctBy { it.substringBefore("?").substringAfterLast("/") }
            .toList()
        Log.d(TAG, "Carousel: ${found.size} image(s)")
        return when {
            found.size >= 2 -> info.copy(mediaType = "carousel", imageUrls = found)
            found.size == 1 && info.imageUrls.isEmpty() -> info.copy(mediaType = "image", imageUrls = found)
            else -> info
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    private fun extractCsrfToken(cookies: String) =
        cookies.split(";").firstOrNull { it.trim().startsWith("csrftoken=") }
            ?.substringAfter("csrftoken=")?.trim() ?: ""

    private fun shortcodeToMediaId(shortcode: String): Long {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (ch in shortcode.take(11)) {
            val idx = alphabet.indexOf(ch); if (idx < 0) return -1L
            id = id * 64 + idx
        }
        return id
    }

    private fun extractUsernameFromTitle(title: String): String? {
        Regex("""^@([^\s]+)\s+on\s+Threads""", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.get(1)?.also { return it }
        return Regex("""^([^\s]+)\s+on\s+Threads""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)
    }

    private fun parseOGProperties(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        Regex("""<meta\s+property="og:([^"]+)"\s+content="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { result[it.groupValues[1]] = it.groupValues[2] }
        Regex("""<meta\s+content="([^"]*)"\s+property="og:([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { result[it.groupValues[2]] = it.groupValues[1] }
        return result
    }

    private fun isPostImage(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("t51.2885-19") || lower.contains("profile_pic") || lower.contains("profilepic")) return false
        return (lower.contains("cdninstagram.com") || lower.contains("fbcdn.net")) && lower.contains("/v/")
    }

    private fun String.htmlUnescape(): String {
        var s = replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            try { String(Character.toChars(m.groupValues[1].toInt(16))) } catch (_: Exception) { m.value }
        }
        s = Regex("&#([0-9]+);").replace(s) { m ->
            try { String(Character.toChars(m.groupValues[1].toInt())) } catch (_: Exception) { m.value }
        }
        return s
    }
}
