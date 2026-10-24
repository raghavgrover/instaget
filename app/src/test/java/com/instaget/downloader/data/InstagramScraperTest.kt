package com.instaget.downloader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for InstagramScraper's offline parsing. They pin down current
 * behaviour so dependency bumps / R8 changes can't silently break scraping.
 * Fixtures mirror the JSON shapes the scraper reads (GraphQL xdt_shortcode_media and the
 * media-info "items" API) with fake CDN URLs.
 */
class InstagramScraperTest {

    private val scraper = InstagramScraper()

    @Test
    fun extractShortcode_handlesReelPostAndUserPrefixedUrls() {
        assertEquals("ABC123", scraper.extractShortcode("https://www.instagram.com/reel/ABC123/?igsh=xyz"))
        assertEquals("Xy_z-9", scraper.extractShortcode("https://instagram.com/p/Xy_z-9/"))
        assertEquals("TVcode", scraper.extractShortcode("https://www.instagram.com/someuser/tv/TVcode/"))
        assertEquals("Q1w2", scraper.extractShortcode("https://www.instagram.com/someuser/p/Q1w2/"))
    }

    @Test
    fun extractShortcode_convertsStoryMediaIdToShortcode() {
        assertEquals("BA", scraper.extractShortcode("https://www.instagram.com/stories/someuser/64/"))
        assertEquals("BA", scraper.mediaIdToShortcode("64"))
        assertEquals("B", scraper.mediaIdToShortcode("1"))
    }

    @Test
    fun extractShortcode_returnsNullForNonPostUrls() {
        assertNull(scraper.extractShortcode("https://www.instagram.com/someuser/"))
        assertNull(scraper.extractShortcode("https://example.com/p/ABC/"))
    }

    @Test
    fun parseResponse_xdtVideo() {
        val json = """
            {"data":{"xdt_shortcode_media":{
              "__typename":"XDTGraphVideo","is_video":true,
              "video_url":"https://scontent.cdninstagram.com/v/t50/video.mp4?a=1",
              "display_url":"https://scontent.cdninstagram.com/v/t51/thumb.jpg",
              "owner":{"username":"alice"},
              "edge_media_to_caption":{"edges":[{"node":{"text":"hello world"}}]}
            }}}
        """.trimIndent()
        val info = scraper.parseResponse(json)!!
        assertEquals("video", info.mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/t50/video.mp4?a=1", info.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/t51/thumb.jpg", info.imageUrl)
        assertEquals("https://scontent.cdninstagram.com/v/t51/thumb.jpg", info.thumbnailUrl)
        assertEquals("alice", info.username)
        assertEquals("hello world", info.caption)
        assertTrue(info.carouselItems.isEmpty())
    }

    @Test
    fun parseResponse_xdtImageFallsBackToThumbnailSrc() {
        val json = """
            {"data":{"xdt_shortcode_media":{
              "__typename":"XDTGraphImage",
              "thumbnail_src":"https://scontent.cdninstagram.com/v/t51/img.jpg",
              "owner":{"username":"bob"}
            }}}
        """.trimIndent()
        val info = scraper.parseResponse(json)!!
        assertEquals("image", info.mediaType)
        assertNull(info.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/t51/img.jpg", info.imageUrl)
        assertEquals("", info.caption)
    }

    @Test
    fun parseResponse_xdtSidecarCarousel() {
        val json = """
            {"data":{"xdt_shortcode_media":{
              "__typename":"XDTGraphSidecar",
              "display_url":"https://scontent.cdninstagram.com/v/t51/cover.jpg",
              "owner":{"username":"carol"},
              "edge_sidecar_to_children":{"edges":[
                {"node":{"display_url":"https://scontent.cdninstagram.com/v/t51/1.jpg"}},
                {"node":{"is_video":true,"video_url":"https://scontent.cdninstagram.com/v/t50/2.mp4",
                         "display_url":"https://scontent.cdninstagram.com/v/t51/2.jpg"}}
              ]}
            }}}
        """.trimIndent()
        val info = scraper.parseResponse(json)!!
        assertEquals("carousel", info.mediaType)
        assertEquals(2, info.carouselItems.size)
        assertEquals(CarouselItem("image", null, "https://scontent.cdninstagram.com/v/t51/1.jpg"), info.carouselItems[0])
        assertEquals(
            CarouselItem("video", "https://scontent.cdninstagram.com/v/t50/2.mp4", "https://scontent.cdninstagram.com/v/t51/2.jpg"),
            info.carouselItems[1]
        )
    }

    @Test
    fun parseResponse_webInfoCarouselItems() {
        val json = """
            {"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{
              "media_type":8,"user":{"username":"dave"},"caption":{"text":"cap"},
              "carousel_media":[
                {"media_type":1,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/a.jpg"}]}},
                {"media_type":2,"video_versions":[{"url":"https://scontent.cdninstagram.com/v/b.mp4"}],
                 "image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/b.jpg"}]}}
              ]
            }]}}}
        """.trimIndent()
        val info = scraper.parseResponse(json)!!
        assertEquals("carousel", info.mediaType)
        assertEquals("dave", info.username)
        assertEquals("cap", info.caption)
        assertEquals("image", info.carouselItems[0].mediaType)
        assertEquals("video", info.carouselItems[1].mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/b.mp4", info.carouselItems[1].videoUrl)
    }

    @Test
    fun parseItem_videoUrlOverridesImageMediaType() {
        val item = JSONObject(
            """{"media_type":1,"video_versions":[{"url":"https://scontent.cdninstagram.com/v/s.mp4"}],
                "image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/s.jpg"}]}}"""
        )
        val info = scraper.parseItem(item)
        assertEquals("video", info.mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/s.mp4", info.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/s.jpg", info.imageUrl)
    }

    @Test
    fun parseResponse_returnsNullForUnknownOrBrokenPayloads() {
        assertNull(scraper.parseResponse("""{"data":{}}"""))
        assertNull(scraper.parseResponse("""{"status":"fail"}"""))
        assertNull(scraper.parseResponse("<html>login</html>"))
        assertNull(scraper.parseResponse("""{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[]}}}"""))
    }

    @Test
    fun flattenStoryResult_keepsOnlyFirstCarouselFrame() {
        val carousel = MediaInfo(
            shortcode = "", username = "u", caption = "", mediaType = "carousel",
            videoUrl = null, imageUrl = "cover", thumbnailUrl = "cover",
            carouselItems = listOf(CarouselItem("video", "v1.mp4", "v1.jpg"), CarouselItem("image", null, "i2.jpg"))
        )
        val single = scraper.flattenStoryResult(carousel)
        assertEquals("video", single.mediaType)
        assertEquals("v1.mp4", single.videoUrl)
        assertEquals("v1.jpg", single.imageUrl)
        assertEquals("v1.jpg", single.thumbnailUrl)
        assertTrue(single.carouselItems.isEmpty())

        val image = carousel.copy(mediaType = "image", carouselItems = emptyList())
        assertEquals(image, scraper.flattenStoryResult(image))
    }
}
