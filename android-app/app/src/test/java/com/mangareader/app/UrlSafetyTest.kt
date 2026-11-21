package com.mangareader.app

import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {

    @Test
    fun `validateExternalUrl accepts standard public https URLs`() {
        val res = UrlSafety.validateExternalUrl("https://manhuatop.example.com/manga/kurogane/chapter-12")
        assertTrue(res.valid)
        assertEquals("https://manhuatop.example.com/manga/kurogane/chapter-12", res.normalizedUrl)
    }

    @Test
    fun `validateExternalUrl blocks localhost, loopback, metadata, private IPs, and unsafe schemes`() {
        val blocked = listOf(
            "http://localhost:3000/secret",
            "http://127.0.0.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.10/db",
            "http://[::1]/loopback",
            "http://0x7f000001/",
            "http://2130706433/",
            "http://user:pass@example.com/",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,<h1>x</h1>"
        )

        for (url in blocked) {
            val res = UrlSafety.validateExternalUrl(url)
            assertFalse("Expected $url to be blocked", res.valid)
        }
    }

    @Test
    fun `isPrivateOrLoopbackIp detects IPv4-mapped IPv6 and ULA ranges`() {
        assertTrue(UrlSafety.isPrivateOrLoopbackIp("::ffff:127.0.0.1"))
        assertTrue(UrlSafety.isPrivateOrLoopbackIp("fd12:3456:789a::1"))
        assertTrue(UrlSafety.isPrivateOrLoopbackIp("fe80::1"))
        assertFalse(UrlSafety.isPrivateOrLoopbackIp("8.8.8.8"))
    }

    @Test
    fun `isSafeClientUrl validates http and https while blocking javascript and data schemes`() {
        assertTrue(UrlSafety.isSafeClientUrl("https://cdn.example.com/p1.webp"))
        assertTrue(UrlSafety.isSafeClientUrl("/data/user/0/app/files/page_001.jpg", allowLocalFile = true))
        assertFalse(UrlSafety.isSafeClientUrl("/data/../../etc/passwd", allowLocalFile = true))
        assertFalse(UrlSafety.isSafeClientUrl("javascript:alert(1)"))
        assertFalse(UrlSafety.isSafeClientUrl("data:image/svg+xml,<svg/>"))
    }

    @Test
    fun `calculateSecFetchSite returns none when referer is absent`() {
        assertEquals("none", AppConstants.calculateSecFetchSite("cdn.example.com", null))
        assertEquals("same-origin", AppConstants.calculateSecFetchSite("cdn.example.com", "https://cdn.example.com/ch1"))
        assertEquals("same-site", AppConstants.calculateSecFetchSite("img.example.com", "https://www.example.com/ch1"))
        assertEquals("cross-site", AppConstants.calculateSecFetchSite("othercdn.net", "https://example.com/ch1"))
    }
}
