package com.instaget.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for ThreadsScraper's offline parsing (OG tags, SSR JSON, tokens,
 * carousel enrichment). They pin down current behaviour so dependency bumps / R8 changes
 * can't silently break scraping. Fixtures use fake CDN URLs in Threads' real HTML shapes.
 */
class ThreadsScraperTest {

    private val scraper = ThreadsScraper()

    private val postImage1 = "https://scontent-lhr8-1.cdninstagram.com/v/t51.29350-15/111_n.jpg?stp=dst&_nc_ht=x"
    private val postImage2 = "https://scontent-lhr8-1.cdninstagram.com/v/t51.29350-15/222_n.jpg?stp=dst&_nc_ht=x"

    private fun ogPage(vararg tags: Pair<String, String>) = buildString {
        append("<html><head>")
        tags.forEach { (k, v) -> append("""<meta property="og:$k" content="$v" />""") }
        append("</head><body></body></html>")
    }

    @Test
    fun urlHelpers_recogniseFullAndShareLinks() {
        assertTrue(scraper.isThreadsUrl("https://www.threads.net/@alice/post/C1a2B3"))
        assertTrue(scraper.isThreadsUrl("https://www.threads.com/share/AbC123/"))
        assertFalse(scraper.isThreadsUrl("https://www.instagram.com/p/ABC/"))
        assertEquals("C1a2B3", scraper.extractPostId("https://www.threads.com/@alice/post/C1a2B3?xmt=1"))
        assertEquals("alice", scraper.extractUsername("https://www.threads.com/@alice/post/C1a2B3"))
        assertNull(scraper.extractPostId("https://www.threads.com/share/AbC123/"))
    }

    @Test
    fun parseFromOGTags_imagePost() {
        val html = ogPage(
            "title" to "@alice on Threads",
            "description" to "Hello &amp; welcome &#39;all&#39;",
            "image" to postImage1.replace("&", "&amp;")
        )
        val info = scraper.parseFromOGTags(html, "C1a2B3", "fallback")!!
        assertEquals("image", info.mediaType)
        assertEquals("alice", info.username)
        assertEquals("Hello & welcome 'all'", info.text)
        assertEquals(listOf(postImage1), info.imageUrls)
        assertNull(info.videoUrl)
        assertNull(info.videoThumbnailUrl)
    }

    @Test
    fun parseFromOGTags_videoPost() {
        val html = ogPage(
            "title" to "bob on Threads",
            "description" to "clip",
            "image" to postImage1.replace("&", "&amp;"),
            "video" to "https://scontent.cdninstagram.com/o1/v/t2/f2/m86/vid.mp4?efg=1&amp;x=2"
        )
        val info = scraper.parseFromOGTags(html, "P1", "")!!
        assertEquals("video", info.mediaType)
        assertEquals("bob", info.username)
        assertEquals("https://scontent.cdninstagram.com/o1/v/t2/f2/m86/vid.mp4?efg=1&x=2", info.videoUrl)
        assertEquals(postImage1, info.videoThumbnailUrl)
        assertTrue(info.imageUrls.isEmpty())
    }

    @Test
    fun parseFromOGTags_avatarOrGenericImageMeansTextPost() {
        val avatar = ogPage(
            "title" to "@carol on Threads",
            "description" to "just words",
            "image" to "https://scontent.cdninstagram.com/v/t51.2885-19/profile_n.jpg"
        )
        assertEquals("text", scraper.parseFromOGTags(avatar, "P2", "")!!.mediaType)

        val generic = ogPage("title" to "@carol on Threads", "image" to "https://scontent.xx.fbcdn.net/v/t39.30808-6/og.jpg")
        assertEquals("text", scraper.parseFromOGTags(generic, "P2", "")!!.mediaType)
    }

    @Test
    fun parseFromOGTags_noTagsReturnsNull_andContentFirstOrderIsParsed() {
        assertNull(scraper.parseFromOGTags("<html><body>app shell</body></html>", "P", "u"))

        val reversed = """<meta content="@dave on Threads" property="og:title">"""
        assertEquals(mapOf("title" to "@dave on Threads"), scraper.parseOGProperties(reversed))
        assertEquals("dave", scraper.parseFromOGTags(reversed, "P", "u")!!.username)
    }

    @Test
    fun parseFromOGTags_usesUrlUsernameWhenTitleHasNone() {
        val html = ogPage("title" to "Threads", "description" to "x")
        assertEquals("fromurl", scraper.parseFromOGTags(html, "P", "fromurl")!!.username)
    }

    @Test
    fun isPostImage_rules() {
        assertTrue(scraper.isPostImage(postImage1))
        assertFalse(scraper.isPostImage("https://scontent.cdninstagram.com/v/t51.2885-19/a.jpg"))
        assertFalse(scraper.isPostImage("https://scontent.xx.fbcdn.net/v/t39.30808-6/a.jpg"))
        assertFalse(scraper.isPostImage("https://example.com/v/a.jpg"))
        assertFalse(scraper.isPostImage(""))
    }

    @Test
    fun tryEnrichCarousel_collectsDistinctImagesFromSameBucket() {
        val single = scraper.parseFromOGTags(
            ogPage("title" to "@e on Threads", "image" to postImage1.replace("&", "&amp;")), "P", ""
        )!!
        val html = """
            <img src="${postImage1.replace("&", "&amp;")}">
            <img src="$postImage2">
            <img src="$postImage1">
            <img src="https://scontent.cdninstagram.com/v/t51.2885-19/avatar.jpg">
        """.trimIndent()
        val enriched = scraper.tryEnrichCarousel(html, single)
        assertEquals("carousel", enriched.mediaType)
        assertEquals(2, enriched.imageUrls.size)
        assertEquals(postImage1, enriched.imageUrls[0])
        assertEquals(postImage2, enriched.imageUrls[1])

        // Only the anchor image present → unchanged single image
        assertEquals(single, scraper.tryEnrichCarousel("<img src=\"$postImage1\">", single))
    }

    @Test
    fun extractVideoFromSsrData_skipsNullEntriesAndUnescapes() {
        val html = """
            {"carousel_media":[{"video_versions":null,"x":1}],
             "video_versions":[{"type":101,"url":"https:\/\/scontent.cdninstagram.com\/o1\/v\/t2\/abc.mp4?a=1&amp;b=2"}]}
        """.trimIndent()
        assertEquals(
            "https://scontent.cdninstagram.com/o1/v/t2/abc.mp4?a=1&b=2",
            scraper.extractVideoFromSsrData(html, 123L)
        )
        assertNull(scraper.extractVideoFromSsrData("""{"video_versions":null}""", 1L))
    }

    @Test
    fun extractVideoFromSsrData_escapedCdnFallback() {
        val html = """var x = "https:\/\/video-lhr.cdninstagram.com\/v\/clip.mp4?z=1";"""
        assertEquals("https://video-lhr.cdninstagram.com/v/clip.mp4?z=1", scraper.extractVideoFromSsrData(html, 1L))
    }

    @Test
    fun tokenAndIdExtraction() {
        assertEquals("AVq1abc", scraper.extractLsdToken("""["LSD",[],{"token":"AVq1abc"},123]"""))
        assertEquals("tok2", scraper.extractLsdToken("""{"LSD":{"token":"tok2"}}"""))
        assertEquals("tok3", scraper.extractLsdToken("""<input type="hidden" name="lsd" value="tok3" />"""))
        assertNull(scraper.extractLsdToken("<html></html>"))

        val ids = scraper.extractDocIdsFromHtml(
            """{"doc_id":"1234567890123456"},{"queryId":9876543210987},{"__id":"123"},{"doc_id":"1234567890123456"}"""
        )
        assertEquals(listOf("1234567890123456", "9876543210987"), ids)

        assertEquals("XYZ", scraper.extractCsrfToken("mid=abc; csrftoken=XYZ; ig_did=1"))
        assertEquals("", scraper.extractCsrfToken("mid=abc"))

        assertEquals(1L, scraper.shortcodeToMediaId("B"))
        assertEquals(64L, scraper.shortcodeToMediaId("BA"))
        assertEquals(-1L, scraper.shortcodeToMediaId("A!"))
    }

    @Test
    fun applyVideoFromJson_setsVideoAndThumbnail() {
        val base = ThreadsPostInfo("P", "u", "t", "image", null, listOf(postImage1), null)
        val json = """
            {"data":{"media":{"video_versions":[{"url":"https:\/\/video.cdninstagram.com\/v\/x.mp4"}],
             "image_versions2":{"candidates":[{"url":"https:\/\/scontent.cdninstagram.com\/v\/thumb.jpg"}]}}}}
        """.trimIndent()
        val out = scraper.applyVideoFromJson(json, base)
        assertEquals("video", out.mediaType)
        assertEquals("https://video.cdninstagram.com/v/x.mp4", out.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/thumb.jpg", out.videoThumbnailUrl)
        assertTrue(out.imageUrls.isEmpty())

        assertEquals(base, scraper.applyVideoFromJson("""{"data":null}""", base))
    }
}
