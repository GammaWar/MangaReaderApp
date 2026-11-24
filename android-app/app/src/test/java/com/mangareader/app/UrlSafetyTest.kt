package com.mangareader.app

import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {

    @Test
    fun `validateExternalUrl accepts standard public https URLs, default ports 80 and 443, and public IPv6`() {
        val res = UrlSafety.validateExternalUrl("https://manhuatop.example.com/manga/kurogane/chapter-12")
        assertTrue(res.valid)
        assertEquals("https://manhuatop.example.com/manga/kurogane/chapter-12", res.normalizedUrl)

        assertTrue(UrlSafety.validateExternalUrl("https://cdn.example.com:443/page.jpg").valid)
        assertTrue(UrlSafety.validateExternalUrl("http://cdn.example.com:80/page.jpg").valid)
        assertTrue(UrlSafety.validateExternalUrl("https://[2606:2800:220:1:248:1893:25c8:1946]/page.jpg").valid)
        assertTrue(UrlSafety.validateExternalUrl("https://[::ffff:93.184.216.34]/page.jpg").valid)
    }

    @Test
    fun `validateExternalUrl blocks localhost, trailing dots, metadata, private IPs, IPv6 encodings, and non-standard ports`() {
        val blocked = listOf(
            "http://localhost:3000/secret",
            "http://localhost/secret",
            "http://localhost./secret",
            "http://sub.localhost./secret",
            "http://metadata.google.internal./computeMetadata/v1/",
            "http://router.local./admin",
            "http://127.0.0.1/admin",
            "http://127.1/admin",
            "http://0177.0.0.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/internal",
            "http://100.64.0.1/cgnat",
            "http://192.168.1.1/router",
            "http://172.16.0.10/db",
            "http://198.18.0.1/benchmark",
            "http://192.0.2.1/testnet",
            "http://203.0.113.5/testnet3",
            "http://225.0.0.1/multicast",
            "http://239.255.255.250/ssdp",
            "http://241.0.0.1/reserved",
            "http://[::1]/loopback",
            "http://[::ffff:169.254.169.254]/latest/meta-data/",
            "http://[::ffff:a9fe:a9fe]/latest/meta-data/",
            "http://[::ffff:7f00:1]/admin",
            "http://[0:0:0:0:0:ffff:127.0.0.1]/admin",
            "http://[64:ff9b::127.0.0.1]/nat64",
            "http://[64:ff9b::7f00:1]/nat64",
            "http://[64:ff9b:1::1]/nat64-local",
            "http://[2002:7f00:0001::]/6to4",
            "http://[2001:0000::1]/teredo",
            "http://[2001:db8::1]/doc",
            "http://[100::1]/discard",
            "http://[ff02::1]/multicast",
            "http://[fec0::1]/site-local",
            "http://[fe80::1%25eth0]/zone",
            "http://example.com:22/ssh",
            "http://example.com:6379/redis",
            "https://example.com:8080/alt",
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
    fun `isPrivateOrLoopbackIp detects IPv4 CIDRs, hex IPv4-mapped IPv6, NAT64, 6to4, Zone-IDs, Multicast, and ULA`() {
        val privateIps = listOf(
            "::ffff:127.0.0.1",
            "::ffff:7f00:1",
            "::ffff:a9fe:a9fe",
            "0:0:0:0:0:ffff:0a00:0001",
            "::ffff:0:127.0.0.1",
            "::127.0.0.1",
            "64:ff9b::7f00:1",
            "64:ff9b::169.254.169.254",
            "64:ff9b:1::1",
            "2002:7f00:1::",
            "2001:0::1",
            "2001:db8::1",
            "100::1",
            "fd12:3456:789a::1",
            "fc00::1",
            "fe80::1",
            "fe80::1%eth0",
            "fe80::1%25wlan0",
            "fec0::1",
            "ff02::1",
            "100.64.0.1",
            "198.18.0.1",
            "225.0.0.1",
            "239.255.255.250",
            "241.0.0.1",
            "0177.0.0.1",
            "127.1",
            "0x7f.0.0.1"
        )
        for (ip in privateIps) {
            assertTrue("Expected IP $ip to be private/blocked", UrlSafety.isPrivateOrLoopbackIp(ip))
        }

        val publicIps = listOf(
            "8.8.8.8",
            "93.184.216.34",
            "1.1.1.1",
            "::ffff:93.184.216.34",
            "::ffff:5db8:d822",
            "64:ff9b::8.8.8.8",
            "2606:2800:220:1:248:1893:25c8:1946"
        )
        for (ip in publicIps) {
            assertFalse("Expected IP $ip to be public", UrlSafety.isPrivateOrLoopbackIp(ip))
        }

        assertEquals("2606:2800:220:1::1", UrlSafety.stripIpv6Brackets("[2606:2800:220:1::1]"))
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
    fun `Sec-Fetch-Site calculations respect multi-label public suffixes and origins`() {
        assertEquals("none", AppConstants.calculateSecFetchSite("cdn.example.com", null))
        assertEquals("same-origin", AppConstants.calculateSecFetchSite("cdn.example.com", "https://cdn.example.com/ch1"))
        assertEquals("same-site", AppConstants.calculateSecFetchSite("img.example.com", "https://www.example.com/ch1"))
        assertEquals("cross-site", AppConstants.calculateSecFetchSite("othercdn.net", "https://example.com/ch1"))

        assertEquals(
            "same-origin",
            AppConstants.computeSecFetchSite("https://cdn.example.co.uk/1.jpg", "https://cdn.example.co.uk:443/ch1")
        )
        assertEquals(
            "same-site",
            AppConstants.computeSecFetchSite("https://img.manga.co.jp/1.jpg", "https://reader.manga.co.jp/ch1")
        )
        assertEquals(
            "cross-site",
            AppConstants.computeSecFetchSite("https://site-a.pages.dev/1.jpg", "https://site-b.pages.dev/ch1")
        )
        assertEquals(
            "cross-site",
            AppConstants.computeSecFetchSite("https://d111.cloudfront.net/1.jpg", "https://d222.cloudfront.net/ch1")
        )
    }
}
