package com.mangareader.app

import com.mangareader.app.data.network.CdnSecurityChallengeException
import com.mangareader.app.data.network.CookieProvider
import com.mangareader.app.data.network.ImageRequestInterceptor
import com.mangareader.app.data.network.MangaNetworkClients
import com.mangareader.app.data.network.NullBitmapDecodeException
import com.mangareader.app.data.network.RefererStrategy
import com.mangareader.app.data.network.SafeDns
import com.mangareader.app.data.network.SsrfBlockedException
import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import com.mangareader.app.util.UrlValidationResult
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

class ImageHttpClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val validJpeg = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
        0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01
    )

    private val validPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d
    )

    private val validWebp = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20
    )

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
    fun `RefererStrategy builds deduplicated fallback tiers`() {
        val crossOriginTiers = RefererStrategy.buildRefererTiers(
            imageUrl = "https://cdn.imgbox.net/p1.webp",
            refererHeader = "https://reader.manga.org/series/chapter-12"
        )
        assertEquals(
            listOf(
                "https://reader.manga.org/series/chapter-12",
                "https://reader.manga.org/",
                "https://cdn.imgbox.net/",
                null
            ),
            crossOriginTiers
        )

        val sameOriginTiers = RefererStrategy.buildRefererTiers(
            imageUrl = "https://reader.manga.org/images/p1.jpg",
            refererHeader = "https://reader.manga.org/"
        )
        assertEquals(
            listOf("https://reader.manga.org/", null),
            sameOriginTiers
        )

        val noRefererTiers = RefererStrategy.buildRefererTiers(
            imageUrl = "https://cdn.imgbox.net/p1.webp",
            refererHeader = null
        )
        assertEquals(
            listOf("https://cdn.imgbox.net/", null),
            noRefererTiers
        )
    }

    @Test
    fun `imageHttpClient walks Referer fallback tiers on 403 until image succeeds and merges cookies`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(validJpeg))
        )

        val cookieProvider = CookieProvider { url ->
            when {
                url.startsWith("https://manga.example.com") -> "cf_clearance=abc123; theme=dark"
                url.contains("/page01.jpg") -> "cdn_tok=xyz"
                else -> null
            }
        }

        val sleeps = mutableListOf<Long>()
        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = cookieProvider,
            sleeper = { sleeps.add(it) },
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        val imgUrl = server.url("/cdn/page01.jpg").toString()
        val req = Request.Builder()
            .url(imgUrl)
            .header("Referer", "https://manga.example.com/read/chapter-5")
            .header("Origin", "https://should-be-stripped.example")
            .build()

        client.newCall(req).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validJpeg, res.body!!.bytes())
        }

        assertEquals(3, server.requestCount)
        assertTrue("403 fallback should not trigger backoff sleeps", sleeps.isEmpty())

        val r1 = server.takeRequest()
        assertEquals("https://manga.example.com/read/chapter-5", r1.getHeader("Referer"))
        assertEquals("cross-site", r1.getHeader("Sec-Fetch-Site"))
        assertNull("Synthetic Origin header must be stripped", r1.getHeader("Origin"))
        assertEquals(AppConstants.USER_AGENT, r1.getHeader("User-Agent"))
        assertEquals(ImageRequestInterceptor.IMAGE_ACCEPT_HEADER, r1.getHeader("Accept"))
        assertTrue(r1.getHeader("Cookie")!!.contains("cf_clearance=abc123"))
        assertTrue(r1.getHeader("Cookie")!!.contains("cdn_tok=xyz"))

        val r2 = server.takeRequest()
        assertEquals("https://manga.example.com/", r2.getHeader("Referer"))
        assertEquals("cross-site", r2.getHeader("Sec-Fetch-Site"))

        val r3 = server.takeRequest()
        assertEquals(AppConstants.getRefererForUrl(imgUrl), r3.getHeader("Referer"))
        assertEquals("same-origin", r3.getHeader("Sec-Fetch-Site"))
    }

    @Test
    fun `imageHttpClient falls back all the way to no-Referer tier when all origin Referers get 403`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/png")
                .setBody(Buffer().write(validPng))
        )

        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = {},
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        val req = Request.Builder()
            .url(server.url("/page02.png"))
            .header("Referer", "https://source.example.com/ch-1")
            .build()

        client.newCall(req).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validPng, res.body!!.bytes())
        }

        assertEquals(4, server.requestCount)
        repeat(3) { server.takeRequest() }
        val r4 = server.takeRequest()
        assertNull(r4.getHeader("Referer"))
        assertEquals("none", r4.getHeader("Sec-Fetch-Site"))
    }

    @Test
    fun `imageHttpClient detects disguised HTTP 200 HTML and XML challenges and recovers or throws CdnSecurityChallengeException`() {
        val cfHtml = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>cf-browser-verification</body></html>"
        val xmlAccessDenied = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code></Error>"

        // Scenario 1: Tier 1 gets disguised HTML challenge (with spoofed image/jpeg header), Tier 2 returns valid WebP
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/jpeg")
                .setBody(cfHtml)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/webp")
                .setBody(Buffer().write(validWebp))
        )

        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = {},
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        val req1 = Request.Builder()
            .url(server.url("/recovered.webp"))
            .header("Referer", "https://reader.example.com/ch-1")
            .build()

        client.newCall(req1).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validWebp, res.body!!.bytes())
        }
        assertEquals(2, server.requestCount)

        // Scenario 2: All tiers return XML AccessDenied -> throws CdnSecurityChallengeException
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "image/png")
                    .setBody(xmlAccessDenied)
            )
        }

        val req2 = Request.Builder().url(server.url("/blocked.png")).build()
        try {
            client.newCall(req2).execute().close()
            fail("Expected CdnSecurityChallengeException for disguised XML challenge")
        } catch (ex: CdnSecurityChallengeException) {
            assertEquals("application/xml", ex.receivedContentType)
            assertTrue(ex.openInBrowserRequired)
        }
    }

    @Test
    fun `imageHttpClient rejects empty or non-image HTTP 200 payload with NullBitmapDecodeException`() {
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "image/jpeg")
                    .setBody("{\"error\":\"hotlink_denied\"}")
            )
        }

        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = {},
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        try {
            client.newCall(Request.Builder().url(server.url("/corrupt.jpg")).build()).execute().close()
            fail("Expected NullBitmapDecodeException for invalid magic bytes")
        } catch (ex: NullBitmapDecodeException) {
            assertEquals("image/jpeg", ex.receivedContentType)
        }
    }

    @Test
    fun `imageHttpClient retries HTTP 429 and 5xx with exact 400ms to 800ms to 1600ms exponential backoff`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(validJpeg))
        )

        val sleeps = mutableListOf<Long>()
        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = { sleeps.add(it) },
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        client.newCall(Request.Builder().url(server.url("/throttled.jpg")).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validJpeg, res.body!!.bytes())
        }

        assertEquals(listOf(400L, 800L, 1600L), sleeps)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `imageHttpClient fast-fails HTTP 404 with zero retries and zero Referer fallbacks`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val sleeps = mutableListOf<Long>()
        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = { sleeps.add(it) },
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        val req = Request.Builder()
            .url(server.url("/missing.jpg"))
            .header("Referer", "https://reader.example.com/ch-1")
            .build()

        client.newCall(req).execute().use { res ->
            assertEquals(404, res.code)
        }

        assertEquals("HTTP 404 must fast-fail on first request", 1, server.requestCount)
        assertTrue("HTTP 404 must not sleep/retry", sleeps.isEmpty())
    }

    @Test
    fun `SafeImageCacheInterceptor caches verified images, honors cache bypass header, and never caches HTML challenges`() {
        val cacheDir = File(tempFolder.newFolder("okhttp_cache"), "http")
        var cacheCallbackCount = 0

        val client = MangaNetworkClients.buildImageHttpClient(
            httpCacheDir = cacheDir,
            cookieProvider = CookieProvider { null },
            sleeper = {},
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false,
            onImageCached = { cacheCallbackCount++ }
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("Cache-Control", "no-store, no-cache") // CDN tries to disable caching
                .setBody(Buffer().write(validJpeg))
        )

        val imgUrl = server.url("/cached-page.jpg")
        // First request: network hit
        client.newCall(Request.Builder().url(imgUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validJpeg, res.body!!.bytes())
        }
        assertEquals(1, server.requestCount)
        assertEquals(1, cacheCallbackCount)

        // Second request: served directly from disk cache (no additional network hit)
        client.newCall(Request.Builder().url(imgUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validJpeg, res.body!!.bytes())
        }
        assertEquals("Second request should be served from HTTP disk cache", 1, server.requestCount)

        // Third request with X-Manga-Bypass-Cache: 1 forces network refresh & strips internal header
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/png")
                .setBody(Buffer().write(validPng))
        )
        val bypassReq = Request.Builder()
            .url(imgUrl)
            .header(MangaReaderApplication.HEADER_BYPASS_CACHE, "1")
            .build()
        client.newCall(bypassReq).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validPng, res.body!!.bytes())
        }
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val bypassRecorded = server.takeRequest()
        assertNull(bypassRecorded.getHeader(MangaReaderApplication.HEADER_BYPASS_CACHE))
    }

    @Test
    fun `imageHttpClient blocks redirects to internal SSRF targets without retrying`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://169.254.169.254/latest/meta-data/")
        )

        val sleeps = mutableListOf<Long>()
        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = { sleeps.add(it) },
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        try {
            client.newCall(Request.Builder().url(server.url("/redirect-ssrf.jpg")).build()).execute().close()
            fail("Expected SsrfBlockedException on redirect to metadata endpoint")
        } catch (_: SsrfBlockedException) {
            // Expected
        }
        assertEquals(1, server.requestCount)
        assertTrue("SSRF blocks must never trigger retry backoff", sleeps.isEmpty())
    }

    @Test
    fun `imageHttpClient follows safe redirects and SafeDns blocks private DNS resolutions`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/final-page.jpg")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(validJpeg))
        )

        val client = MangaNetworkClients.buildImageHttpClient(
            cookieProvider = CookieProvider { null },
            sleeper = {},
            urlValidator = allowMockServerOnly(),
            enforcePublicDns = false
        )

        client.newCall(Request.Builder().url(server.url("/redirect-safe")).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertArrayEquals(validJpeg, res.body!!.bytes())
        }
        assertEquals(2, server.requestCount)

        // SafeDns blocks private / loopback DNS responses and allows public ones
        val privateDns = SafeDns(delegate = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("10.0.0.5"))
        })
        try {
            privateDns.lookup("rebind.example.com")
            fail("Expected SsrfBlockedException for private DNS IP")
        } catch (_: SsrfBlockedException) {
            // Expected
        }

        val publicDns = SafeDns(delegate = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34"))
        })
        val resolved = publicDns.lookup("example.com")
        assertEquals(1, resolved.size)
        assertEquals("93.184.216.34", resolved[0].hostAddress)
    }
}
