package com.mangareader.app

import com.mangareader.app.data.network.BitmapDecodeGuard
import com.mangareader.app.data.network.CdnSecurityChallengeException
import com.mangareader.app.data.network.DiagnosticKind
import com.mangareader.app.data.network.DiagnosticSeverity
import com.mangareader.app.data.network.ImageDiagnostics
import com.mangareader.app.data.network.NullBitmapDecodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDiagnosticsTest {

    @Test
    fun `isHtmlPayload and isXmlPayload detect disguised security challenges vs real image bytes`() {
        assertTrue(BitmapDecodeGuard.isHtmlPayload("<!DOCTYPE html><html><title>Just a moment...</title>"))
        assertTrue(BitmapDecodeGuard.isHtmlPayload("   <HTML lang=\"en\"><body>Blocked</body></html>"))
        assertTrue(BitmapDecodeGuard.isXmlPayload("<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertTrue(BitmapDecodeGuard.isHtmlPayload("<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertFalse(BitmapDecodeGuard.isHtmlPayload(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
        assertFalse(BitmapDecodeGuard.isHtmlPayload(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)))
    }

    @Test
    fun `isValidImageContentType strictly allows image wildcard and rejects html and xml`() {
        assertTrue(BitmapDecodeGuard.isValidImageContentType("image/webp"))
        assertTrue(BitmapDecodeGuard.isValidImageContentType("image/jpeg; charset=binary"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("text/html; charset=utf-8"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("application/xml; charset=utf-8"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("text/xml"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType(null))
        assertTrue(BitmapDecodeGuard.isChallengeContentType("text/html; charset=utf-8"))
        assertTrue(BitmapDecodeGuard.isChallengeContentType("application/xml"))
        assertFalse(BitmapDecodeGuard.isChallengeContentType("image/png"))
    }

    @Test
    fun `diagnoseBitmapDecodeFailure detects text html and spoofed application xml challenges`() {
        val htmlDiag = ImageDiagnostics.diagnoseBitmapDecodeFailure(
            headerContentType = "text/html; charset=utf-8",
            bufferPrefix = "<!DOCTYPE html><html><title>Just a moment...</title></html>".toByteArray(),
            httpStatus = 200
        )
        assertEquals(DiagnosticKind.HTML_CHALLENGE, htmlDiag.kind)
        assertEquals("text/html", htmlDiag.receivedContentType)
        assertFalse(htmlDiag.shouldAutoRetry)
        assertTrue(htmlDiag.suggestOpenBrowser)

        val spoofedXmlDiag = ImageDiagnostics.diagnoseBitmapDecodeFailure(
            headerContentType = "image/jpeg",
            bufferPrefix = "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>".toByteArray()
        )
        assertEquals(DiagnosticKind.HTML_CHALLENGE, spoofedXmlDiag.kind)
        assertEquals("application/xml", spoofedXmlDiag.receivedContentType)
        assertTrue(spoofedXmlDiag.suggestOpenBrowser)

        val corruptBinary = ImageDiagnostics.diagnoseBitmapDecodeFailure(
            headerContentType = "image/jpeg",
            bufferPrefix = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x00, 0xde.toByte(), 0xad.toByte())
        )
        assertEquals(DiagnosticKind.DECODE_ERROR, corruptBinary.kind)
        assertTrue(corruptBinary.suggestCacheBypass)
        assertFalse(corruptBinary.shouldAutoRetry)
    }

    @Test
    fun `classifyImageThrowable and classifyImageError distinguish 404, challenge, null bitmap, 429, and 503`() {
        val d404 = ImageDiagnostics.classifyImageError(httpStatus = 404)
        assertEquals(DiagnosticKind.HTTP_404, d404.kind)
        assertFalse(d404.shouldAutoRetry)
        assertFalse(d404.suggestCacheBypass)
        assertEquals(DiagnosticSeverity.CRIMSON, d404.severity)

        val dXmlEx = ImageDiagnostics.classifyImageThrowable(
            CdnSecurityChallengeException("https://cdn.test/1.jpg", "application/xml")
        )
        assertEquals(DiagnosticKind.HTML_CHALLENGE, dXmlEx.kind)
        assertEquals("application/xml", dXmlEx.receivedContentType)
        assertTrue(dXmlEx.suggestOpenBrowser)
        assertTrue(dXmlEx.suggestCacheBypass)
        assertTrue(dXmlEx.suggestSessionRefresh)

        val dNullEx = ImageDiagnostics.classifyImageThrowable(
            NullBitmapDecodeException("https://cdn.test/2.jpg", "image/jpeg")
        )
        assertEquals(DiagnosticKind.DECODE_ERROR, dNullEx.kind)
        assertTrue(dNullEx.suggestCacheBypass)
        assertFalse(dNullEx.shouldAutoRetry)

        val d429 = ImageDiagnostics.classifyImageError(httpStatus = 429)
        assertEquals(DiagnosticKind.HTTP_429, d429.kind)
        assertTrue(d429.shouldAutoRetry)
        assertFalse(d429.suggestCacheBypass)

        val d503 = ImageDiagnostics.classifyImageError(httpStatus = 503)
        assertEquals(DiagnosticKind.HTTP_5XX, d503.kind)
        assertTrue(d503.shouldAutoRetry)
    }

    @Test
    fun `getBackoffDelayMs follows 400ms to 800ms to 1600ms exponential backoff`() {
        assertEquals(400L, ImageDiagnostics.getBackoffDelayMs(0))
        assertEquals(800L, ImageDiagnostics.getBackoffDelayMs(1))
        assertEquals(1600L, ImageDiagnostics.getBackoffDelayMs(2))
        assertEquals(1600L, ImageDiagnostics.getBackoffDelayMs(5))
    }
}
