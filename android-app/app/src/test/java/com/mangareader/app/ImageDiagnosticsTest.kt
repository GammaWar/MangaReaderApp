package com.mangareader.app

import com.mangareader.app.data.network.BitmapDecodeGuard
import com.mangareader.app.data.network.CdnSecurityChallengeException
import com.mangareader.app.data.network.DiagnosticKind
import com.mangareader.app.data.network.DiagnosticSeverity
import com.mangareader.app.data.network.ImageDiagnostics
import com.mangareader.app.data.network.NullBitmapDecodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDiagnosticsTest {

    @Test
    fun `isHtmlPayload and isXmlPayload detect disguised security challenges and UTF-8 BOM vs real image bytes`() {
        assertTrue(BitmapDecodeGuard.isHtmlPayload("<!DOCTYPE html><html><title>Just a moment...</title>"))
        assertTrue(BitmapDecodeGuard.isHtmlPayload("\uFEFF  <!doctype html><html><body>Challenge</body></html>"))
        assertTrue(BitmapDecodeGuard.isHtmlPayload("   <HTML lang=\"en\"><body>Blocked</body></html>"))
        assertTrue(BitmapDecodeGuard.isXmlPayload("<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertTrue(BitmapDecodeGuard.isXmlPayload("\uFEFF<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertTrue(BitmapDecodeGuard.isHtmlPayload("<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertFalse(BitmapDecodeGuard.isHtmlPayload(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
        assertFalse(BitmapDecodeGuard.isHtmlPayload(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)))
    }

    @Test
    fun `isValidImageContentType strictly allows safe raster types and rejects html, xml, and svg`() {
        assertTrue(BitmapDecodeGuard.isValidImageContentType("image/webp"))
        assertTrue(BitmapDecodeGuard.isValidImageContentType("image/jpeg; charset=binary"))
        assertTrue(BitmapDecodeGuard.isValidImageContentType("image/png"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("image/svg+xml"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("text/html; charset=utf-8"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("application/xml; charset=utf-8"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("text/xml"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType("application/octet-stream"))
        assertFalse(BitmapDecodeGuard.isValidImageContentType(null))
        assertTrue(BitmapDecodeGuard.isChallengeContentType("text/html; charset=utf-8"))
        assertTrue(BitmapDecodeGuard.isChallengeContentType("application/xml"))
        assertFalse(BitmapDecodeGuard.isChallengeContentType("image/png"))

        assertEquals("image/jpeg", BitmapDecodeGuard.resolveSafeImageContentType("image/jpg; charset=binary"))
        assertEquals("image/webp", BitmapDecodeGuard.resolveSafeImageContentType("IMAGE/WEBP"))
        assertNull(BitmapDecodeGuard.resolveSafeImageContentType("image/svg+xml"))
    }

    @Test
    fun `hasValidImageMagicBytes validates raster image headers and rejects empty or spoofed payloads`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x10)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00)
        val webp = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20
        )
        val gif89a = "GIF89a\u0001\u0000".toByteArray(Charsets.ISO_8859_1)
        val bmp = byteArrayOf(0x42, 0x4d, 0x36, 0x00)
        val avif = byteArrayOf(0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66)
        val ico = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01, 0x00)

        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(jpeg, "image/jpeg"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(png, "image/png"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(webp, "image/webp"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(gif89a, "image/gif"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(bmp, "image/bmp"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(avif, "image/avif"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(ico, "image/x-icon"))
        assertTrue(BitmapDecodeGuard.hasValidImageMagicBytes(jpeg, null))

        // Reject empty, too short, JSON, HTML, XML, SVG, or mismatched unsafe MIME type
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes(ByteArray(0), "image/jpeg"))
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes(byteArrayOf(0xff.toByte()), "image/jpeg"))
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes("{\"error\":\"denied\"}".toByteArray(), "image/jpeg"))
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes("<!DOCTYPE html><html></html>".toByteArray(), "image/jpeg"))
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), "image/svg+xml"))
        assertFalse(BitmapDecodeGuard.hasValidImageMagicBytes(jpeg, "text/html"))
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
