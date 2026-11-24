package com.mangareader.app

import com.mangareader.app.data.extractor.ChapterLoadOutcome
import com.mangareader.app.data.extractor.ChapterLoaderRepository
import com.mangareader.app.data.extractor.WebViewFallbackReason
import com.mangareader.app.data.network.ChapterRequestInterceptor
import com.mangareader.app.data.network.CookieProvider
import com.mangareader.app.data.network.MangaNetworkClients
import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import com.mangareader.app.util.UrlValidationResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChapterLoaderRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun allowMockServerOnly(): (String) -> UrlValidationResult {
        val mockPort = server.port
        val mockHost = server.hostName
        return { rawUrl ->
            if (
                rawUrl.startsWith("http://$mockHost:$mockPort/") ||
                rawUrl.startsWith("http://localhost:$mockPort/") ||
                rawUrl.startsWith("http://127.0.0.1:$mockPort/")
            ) {
                UrlValidationResult(valid = true, normalizedUrl = rawUrl)
            } else {
                UrlSafety.validateExternalUrl(rawUrl)
            }
        }
    }

    @Test
    fun `loadChapter loads HTML page natively with HTML Accept header, synced cookies, lazy images, and next link`() = runTest {
        val html = """
            <!DOCTYPE html>
            <html>
              <head><title>Kurogane Chapter 12 - Reader</title></head>
              <body>
                <img src="/assets/logo.png" />
                <img src="/img/placeholder.gif" data-src="https://cdn.example.com/kurogane/ch12/01.webp" />
                <img data-original="/images/ch12/02.jpg" src="/spinner.svg" />
                <img data-srcset="https://cdn.example.com/kurogane/ch12/03.png 1x, https://cdn.example.com/kurogane/ch12/03_2x.png 2x" />
                <a rel="next" href="/manga/kurogane/chapter-13">Next Chapter</a>
              </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(html)
        )

        val validator = allowMockServerOnly()
        val chapterClient = MangaNetworkClients.buildChapterHttpClient(
            cookieProvider = CookieProvider { "session_id=reader42" },
            urlValidator = validator,
            enforcePublicDns = false
        )
        val repo = ChapterLoaderRepository(
            chapterHttpClient = chapterClient,
            urlValidator = validator,
            imageUrlFilter = { validator(it).valid }
        )

        val chapterUrl = server.url("/manga/kurogane/chapter-12").toString()
        val refererUrl = server.url("/manga/kurogane/chapter-11").toString()
        val outcome = repo.loadChapter(chapterUrl = chapterUrl, refererUrl = refererUrl)

        assertTrue("Expected Success, got $outcome", outcome is ChapterLoadOutcome.Success)
        val chapter = (outcome as ChapterLoadOutcome.Success).chapter
        assertEquals("Kurogane Chapter 12 - Reader", chapter.title)
        assertEquals(chapterUrl, chapter.chapterUrl)
        assertEquals(
            listOf(
                "https://cdn.example.com/kurogane/ch12/01.webp",
                server.url("/images/ch12/02.jpg").toString(),
                "https://cdn.example.com/kurogane/ch12/03.png"
            ),
            chapter.images
        )
        assertEquals(server.url("/manga/kurogane/chapter-13").toString(), chapter.nextChapterUrl)
        assertEquals(server.url("/manga/kurogane/chapter-11").toString(), chapter.prevChapterUrl)

        val recorded = server.takeRequest()
        assertEquals(ChapterRequestInterceptor.HTML_ACCEPT_HEADER, recorded.getHeader("Accept"))
        assertEquals(AppConstants.USER_AGENT, recorded.getHeader("User-Agent"))
        assertEquals(refererUrl, recorded.getHeader("Referer"))
        assertEquals("session_id=reader42", recorded.getHeader("Cookie"))
        assertEquals("document", recorded.getHeader("Sec-Fetch-Dest"))
    }

    @Test
    fun `loadChapter follows safe redirects and uses final URL for UrlChapterAnalyzer fallback`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .addHeader("Location", "/manga/kurogane/chapter-20")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Ch 20</title></head><body><img data-src='https://cdn.example.com/20/1.jpg' /></body></html>")
        )

        val validator = allowMockServerOnly()
        val repo = ChapterLoaderRepository(
            chapterHttpClient = MangaNetworkClients.buildChapterHttpClient(
                cookieProvider = CookieProvider { null },
                urlValidator = validator,
                enforcePublicDns = false
            ),
            urlValidator = validator,
            imageUrlFilter = { validator(it).valid }
        )

        val outcome = repo.loadChapter(server.url("/old-link").toString())
        assertTrue("Expected Success, got $outcome", outcome is ChapterLoadOutcome.Success)
        val chapter = (outcome as ChapterLoadOutcome.Success).chapter
        assertEquals(server.url("/manga/kurogane/chapter-20").toString(), chapter.chapterUrl)
        assertEquals(server.url("/manga/kurogane/chapter-21").toString(), chapter.nextChapterUrl)
        assertEquals(server.url("/manga/kurogane/chapter-19").toString(), chapter.prevChapterUrl)
    }

    @Test
    fun `loadChapter falls back to WebView with SECURITY_CHALLENGE on Cloudflare HTML or HTTP 403`() = runTest {
        val validator = allowMockServerOnly()
        val chapterClient = MangaNetworkClients.buildChapterHttpClient(
            cookieProvider = CookieProvider { null },
            urlValidator = validator,
            enforcePublicDns = false
        )
        val repo = ChapterLoaderRepository(
            chapterHttpClient = chapterClient,
            urlValidator = validator,
            imageUrlFilter = { validator(it).valid }
        )

        // Case 1: HTTP 200 Cloudflare Turnstile challenge HTML
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><head><title>Just a moment...</title></head><body><div class='cf-turnstile'></div></body></html>")
        )
        val res1 = repo.loadChapter(server.url("/manga/cf-ch-1").toString())
        assertTrue(res1 is ChapterLoadOutcome.FallbackToWebView)
        assertEquals(WebViewFallbackReason.SECURITY_CHALLENGE, (res1 as ChapterLoadOutcome.FallbackToWebView).reason)

        // Case 2: HTTP 403 Forbidden
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        val res2 = repo.loadChapter(server.url("/manga/cf-ch-2").toString())
        assertTrue(res2 is ChapterLoadOutcome.FallbackToWebView)
        val fb2 = res2 as ChapterLoadOutcome.FallbackToWebView
        assertEquals(WebViewFallbackReason.SECURITY_CHALLENGE, fb2.reason)
        assertEquals(403, fb2.httpStatus)
    }

    @Test
    fun `loadChapter falls back to WebView with JS_RENDERED_OR_EMPTY when HTML contains no images`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><head><title>SPA Reader</title></head><body><div id='root'></div><script src='/app.js'></script></body></html>")
        )

        val validator = allowMockServerOnly()
        val repo = ChapterLoaderRepository(
            chapterHttpClient = MangaNetworkClients.buildChapterHttpClient(
                cookieProvider = CookieProvider { null },
                urlValidator = validator,
                enforcePublicDns = false
            ),
            urlValidator = validator,
            imageUrlFilter = { validator(it).valid }
        )

        val outcome = repo.loadChapter(server.url("/spa/chapter-1").toString())
        assertTrue(outcome is ChapterLoadOutcome.FallbackToWebView)
        assertEquals(WebViewFallbackReason.JS_RENDERED_OR_EMPTY, (outcome as ChapterLoadOutcome.FallbackToWebView).reason)
    }

    @Test
    fun `chapterHttpClient enforces 4-MB HTML size limit on declared Content-Length and chunked streams`() = runTest {
        val validator = allowMockServerOnly()
        // Use a small custom cap (1024 bytes) to test the exact same limit enforcement fast without allocating >4 MB twice
        val chapterClient = MangaNetworkClients.buildChapterHttpClient(
            cookieProvider = CookieProvider { null },
            maxHtmlBytes = 1024L,
            urlValidator = validator,
            enforcePublicDns = false
        )
        val repo = ChapterLoaderRepository(
            chapterHttpClient = chapterClient,
            urlValidator = validator,
            imageUrlFilter = { validator(it).valid }
        )

        // 1. Declared Content-Length > limit
        val oversizedBody = "A".repeat(2048)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(oversizedBody)
        )
        val out1 = repo.loadChapter(server.url("/huge-content-length").toString())
        assertTrue(out1 is ChapterLoadOutcome.FallbackToWebView)
        assertEquals(WebViewFallbackReason.PAYLOAD_TOO_LARGE, (out1 as ChapterLoadOutcome.FallbackToWebView).reason)

        // 2. Chunked stream without Content-Length exceeding limit
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setChunkedBody(oversizedBody, 256)
        )
        val out2 = repo.loadChapter(server.url("/huge-chunked").toString())
        assertTrue(out2 is ChapterLoadOutcome.FallbackToWebView)
        assertEquals(WebViewFallbackReason.PAYLOAD_TOO_LARGE, (out2 as ChapterLoadOutcome.FallbackToWebView).reason)
    }

    @Test
    fun `loadChapter blocks direct private URLs and redirects to internal SSRF targets`() = runTest {
        val validator = allowMockServerOnly()
        val repo = ChapterLoaderRepository(
            chapterHttpClient = MangaNetworkClients.buildChapterHttpClient(
                cookieProvider = CookieProvider { null },
                urlValidator = validator,
                enforcePublicDns = false
            ),
            urlValidator = validator
        )

        // Direct internal targets -> blocked before making any network request
        val blockedTargets = listOf(
            "http://127.0.0.1/chapter-1",
            "http://localhost./chapter-1",
            "http://[::ffff:a9fe:a9fe]/latest/meta-data/",
            "http://example.com:6379/chapter-1"
        )
        for (target in blockedTargets) {
            val out = repo.loadChapter(target)
            assertTrue("Expected BlockedOrInvalid for $target", out is ChapterLoadOutcome.BlockedOrInvalid)
        }
        assertEquals(0, server.requestCount)

        // Redirect from allowed host to internal metadata IP -> blocked by SafeRedirectInterceptor before connect
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://169.254.169.254/latest/meta-data/")
        )
        val redirectOutcome = repo.loadChapter(server.url("/redirect-internal").toString())
        assertTrue(
            "Expected BlockedOrInvalid on internal redirect, got $redirectOutcome",
            redirectOutcome is ChapterLoadOutcome.BlockedOrInvalid
        )
        assertEquals(1, server.requestCount)
    }
}
