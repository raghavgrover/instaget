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
        // Threads' native share button now generates short links like
        // threads.com/share/<code>/ instead of the full /@user/post/id URL.
        // These carry no username/post info themselves and must be resolved first.
        private val SHARE_REGEX =
            Regex("""threads\.(?:net|com)/share/([A-Za-z0-9_-]+)""")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        // Threads only serves the real HTTP 302 redirect (share link → full post URL)
        // to recognised crawler user-agents used for link-preview generation.
        // A normal browser UA gets a 200 with client-side JS redirect that we can't run.
        private const val CRAWLER_USER_AGENT = "facebookexternalhit/1.1"
        private const val IG_APP_ID = "238260118697367"
        private const val GRAPHQL_URL = "https://www.threads.net/api/graphql"

        private val CDN_IMAGE_REGEX = Regex(
            """https://(?:(?:scontent|video)[^"'\s]*\.(?:cdninstagram\.com|fbcdn\.net)|instagram\.[^"'\s]*\.fbcdn\.net)/(?:o1/)?v/[^"'\s]+\.(?:jpg|webp)[^"'\s]*"""
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

    // Dedicated client for share-link resolution: short timeouts so a stalled
    // attempt fails fast and the caller can retry, rather than tying up 20-30s.
    private val resolveClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isThreadsUrl(url: String) =
        POST_REGEX.containsMatchIn(url) || SHARE_REGEX.containsMatchIn(url)
    fun extractPostId(url: String) = POST_REGEX.find(url)?.groupValues?.get(2)
    fun extractUsername(url: String) = POST_REGEX.find(url)?.groupValues?.get(1)

    /**
     * Resolves a threads.com/share/<code>/ link to its canonical
     * threads.com/@username/post/<id> URL. Threads only returns a real HTTP 302
     * for this when the request comes from a recognised crawler user-agent
     * (used for link-preview generation) — a normal browser UA gets a 200 with
     * a client-side JS redirect that a non-JS HTTP client can't follow.
     *
     * Retries once on failure (mobile networks are more prone to transient
     * hiccups than a dev machine) before giving up. Returns null only after
     * both attempts fail — the caller distinguishes this from "not a share
     * link" (which returns the url unchanged) to surface a clear error
     * instead of the generic "Invalid Threads URL" message.
     */
    private fun resolveShareUrl(url: String): String? {
        if (!SHARE_REGEX.containsMatchIn(url)) return url
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", CRAWLER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                resolveClient.newCall(request).execute().use { response ->
                    val resolved = response.request.url.toString()
                    if (POST_REGEX.containsMatchIn(resolved)) {
                        Log.d(TAG, "Resolved share URL (attempt ${attempt + 1}): $url -> $resolved")
                        return resolved
                    }
                    Log.w(TAG, "Share URL resolved but didn't match post format (attempt ${attempt + 1}): $resolved")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve share URL (attempt ${attempt + 1}): ${e.message}")
            }
        }
        return null
    }

    suspend fun fetchPostInfo(rawUrl: String): Result<ThreadsPostInfo> = withContext(Dispatchers.IO) {
        val url = resolveShareUrl(rawUrl)
            ?: return@withContext Result.failure(
                Exception("Could not open this Threads link — please check your connection and try again")
            )
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

            // The regular browser-UA fetch sometimes returns a client-side-rendered
            // "app shell" with no embedded OG tags at all (Threads increasingly gates
            // full SSR content behind a session for logged-out requests). When that
            // happens, fall back to a crawler-UA fetch — Meta reliably serves OG
            // metadata to recognised link-preview bots even when the browser page is empty.
            var ogHtml = html
            if (parseOGProperties(html).isEmpty()) {
                Log.w(TAG, "No OG tags in browser-UA page — retrying with crawler UA")
                ogHtml = fetchPageAsCrawler(url) ?: html
            }

            val info = parseFromOGTags(ogHtml, postId, urlUsername)
                ?: return@withContext Result.failure(
                    Exception("Could not parse post — it may be private or Threads blocked the request")
                )

            val enriched = tryEnrichCarousel(html, info).copy(sessionCookies = cookies)

            val final = if (enriched.videoUrl == null && enriched.mediaType != "text" && enriched.mediaType != "carousel") {
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

        // Primary: find "video_versions" with actual content (not null) then grab the .mp4 URL
        // Skip "video_versions":null entries — carousel items have null video_versions
        var searchFrom = 0
        while (true) {
            val idx = html.indexOf("\"video_versions\"", searchFrom)
            if (idx < 0) break
            searchFrom = idx + 16
            // Skip if this is "video_versions":null
            val peek = html.substring(idx + 16, minOf(html.length, idx + 25)).trimStart()
            if (peek.startsWith(":null") || peek.startsWith(": null")) continue

            val ctx = html.substring(idx, minOf(html.length, idx + 3000))
            Log.d(TAG, "video_versions (non-null) context (600c): ${ctx.take(600)}")

            // Only accept a real video URL — must contain .mp4
            val urlMatch = Regex(""""url"\s*:\s*"(https:[^"]+\.mp4[^"]*?)"""").find(ctx)
            if (urlMatch != null) {
                val raw = urlMatch.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                Log.d(TAG, "Candidate video URL: ${raw.take(120)}")
                if (raw.startsWith("https://") &&
                    (raw.contains("cdninstagram.com") || raw.contains("fbcdn.net"))) {
                    return raw
                }
            }
            break
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

    /**
     * Fetches the post page using a recognised crawler user-agent (used for
     * link-preview generation). Meta reliably serves OG metadata to these even
     * when the same URL returns an empty client-side-rendered shell to a normal
     * browser UA. Used as a fallback ONLY for OG-tag parsing — this response
     * does not contain video_versions/SSR JSON, so it's never used for video
     * or carousel extraction.
     */
    private fun fetchPageAsCrawler(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CRAWLER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            resolveClient.newCall(request).execute().use { response ->
                Log.d(TAG, "GET (crawler) $url → HTTP ${response.code}")
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crawler-UA fetch failed: ${e.message}")
            null
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

        // Only use OG video tags here — never scan the full HTML for .mp4 at this stage.
        // findVideoUrlInHtml would pick up video URLs from OTHER posts embedded in the SSR
        // data (suggested content, replies), wrongly tagging carousel posts as videos.
        // Actual video posts are handled by tryFetchVideo using a targeted media-ID search.
        val ogVideoUrl = (og["video:secure_url"] ?: og["video:url"] ?: og["video"])?.replace("&amp;", "&")
        val videoUrl   = ogVideoUrl

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
        // Match all known CDN host patterns + both /v/ and /o1/v/ paths + jpg and webp images
        val bucketRegex = Regex(
            """https://(?:(?:scontent|video)[^"'\s]*\.(?:cdninstagram\.com|fbcdn\.net)|instagram\.[^"'\s]*\.fbcdn\.net)/(?:o1/)?v/""" +
            Regex.escape(bucket) + """/[^"'\s]+\.(?:jpg|webp)[^"'\s]*"""
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
        // All Meta CDN buckets ending in -19 are profile/avatar buckets (e.g. t51.2885-19, t51.82787-19)
        if (Regex("""/t\d+\.\d+-19/""").containsMatchIn(lower)) return false
        // t39.x is Meta's generic link-preview/OG-image bucket — used as a fallback
        // thumbnail for posts (often videos) whose real media isn't exposed to
        // unauthenticated/bot requests. Never actual post-uploaded content; accepting
        // it would silently download the wrong (generic) image instead of surfacing
        // a clear "media unavailable" error.
        if (Regex("""/t39\.\d+-\d+/""").containsMatchIn(lower)) return false
        if (lower.contains("profile_pic") || lower.contains("profilepic")) return false
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
