package com.mangareader.app.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Exception indicating that a `BitmapFactory` null-bitmap failure or HTTP response was caused by a
 * `text/html` or `application/xml` security/CDN challenge — instructs the UI to
 * immediately display `ImageErrorView` with the "Im Browser öffnen" action.
 */
class CdnSecurityChallengeException(
    val imageUrl: String,
    val receivedContentType: String,
    val openInBrowserRequired: Boolean = true,
    val httpStatus: Int = 403
) : IOException("Security/CDN challenge detected ($receivedContentType instead of image/*) for $imageUrl")

/**
 * Exception indicating that `BitmapFactory` returned null (corrupted/incomplete bytes, not HTML/XML challenge).
 */
class NullBitmapDecodeException(
    val imageUrl: String,
    val receivedContentType: String? = null,
    cause: Throwable? = null
) : IOException("BitmapFactory returned a null bitmap for $imageUrl (${receivedContentType ?: "unknown"})", cause)

enum class DiagnosticKind {
    HTTP_404,
    HTTP_403,
    HTML_CHALLENGE,
    HTTP_429,
    HTTP_5XX,
    TIMEOUT,
    DECODE_ERROR,
    NETWORK_ERROR
}

enum class DiagnosticSeverity {
    CRIMSON,
    AMBER,
    YELLOW,
    SLATE
}

data class ImageDiagnostic(
    val kind: DiagnosticKind,
    val httpStatus: Int? = null,
    val receivedContentType: String? = null,
    val title: String,
    val description: String,
    val hint: String,
    val severity: DiagnosticSeverity,
    val shouldAutoRetry: Boolean,
    val suggestCacheBypass: Boolean,
    val suggestSessionRefresh: Boolean,
    val suggestOpenBrowser: Boolean
)

data class BufferVerificationResult(
    val detectedContentType: String,
    val isSecurityChallenge: Boolean,
    val isValidImageHeader: Boolean
)

object BitmapDecodeGuard {
    private const val TAG = "BitmapDecodeGuard"

    fun normalizeContentType(contentType: String?): String {
        if (contentType.isNullOrBlank()) return ""
        return contentType.substringBefore(';').trim().lowercase()
    }

    fun isXmlPayload(prefixBytes: ByteArray): Boolean {
        val text = String(prefixBytes, 0, minOf(prefixBytes.size, 256), Charsets.UTF_8)
        return isXmlPayload(text)
    }

    fun isXmlPayload(prefix: String): Boolean {
        val trimmed = prefix.take(256).trimStart().lowercase()
        return trimmed.startsWith("<?xml") ||
            trimmed.startsWith("<error>") ||
            trimmed.startsWith("<error ") ||
            (trimmed.contains("<code") && trimmed.contains("accessdenied"))
    }

    fun isHtmlPayload(prefixBytes: ByteArray): Boolean {
        val text = String(prefixBytes, 0, minOf(prefixBytes.size, 256), Charsets.UTF_8)
        return isHtmlPayload(text)
    }

    fun isHtmlPayload(prefix: String): Boolean {
        val trimmed = prefix.take(256).trimStart().lowercase()
        return trimmed.startsWith("<!doctype html") ||
            trimmed.startsWith("<!doctype") ||
            trimmed.startsWith("<html") ||
            trimmed.startsWith("<?xml") ||
            trimmed.startsWith("<error>") ||
            trimmed.startsWith("<error ") ||
            trimmed.contains("<title>just a moment") ||
            trimmed.contains("cf-browser-verification") ||
            trimmed.contains("attention required! | cloudflare")
    }

    fun isChallengeContentType(contentType: String?): Boolean {
        val normalized = normalizeContentType(contentType)
        if (normalized.isEmpty()) return false
        return normalized == "text/html" ||
            normalized == "application/xhtml+xml" ||
            normalized == "application/xml" ||
            normalized == "text/xml" ||
            normalized.contains("html") ||
            normalized.contains("xml")
    }

    fun isValidImageContentType(contentType: String?): Boolean {
        val normalized = normalizeContentType(contentType)
        if (normalized.isEmpty()) return false
        if (isChallengeContentType(normalized) || normalized.startsWith("text/")) return false
        return normalized.startsWith("image/")
    }

    /**
     * Inspects HTTP Content-Type header AND leading payload bytes to detect HTML or XML challenges.
     */
    fun detectSecurityChallengeContentType(
        headerContentType: String?,
        bufferPrefix: String?
    ): String? {
        val normalizedHeader = normalizeContentType(headerContentType)
        val prefix = bufferPrefix.orEmpty()

        if (normalizedHeader == "application/xml" || normalizedHeader == "text/xml" || normalizedHeader.contains("xml")) {
            return if (normalizedHeader.isNotBlank()) normalizedHeader else "application/xml"
        }
        if (normalizedHeader == "text/html" || normalizedHeader == "application/xhtml+xml" || normalizedHeader.contains("html")) {
            return if (normalizedHeader.isNotBlank()) normalizedHeader else "text/html"
        }
        if (isXmlPayload(prefix)) {
            return "application/xml"
        }
        if (isHtmlPayload(prefix)) {
            return "text/html"
        }
        return null
    }

    fun verifyReceivedImageBuffer(
        headerContentType: String?,
        bufferPrefix: ByteArray? = null
    ): BufferVerificationResult {
        val normalizedHeader = normalizeContentType(headerContentType)
        val prefixText = bufferPrefix?.let {
            String(it, 0, minOf(it.size, 256), Charsets.UTF_8)
        }
        val detectedChallenge = detectSecurityChallengeContentType(headerContentType, prefixText)

        if (detectedChallenge != null) {
            return BufferVerificationResult(
                detectedContentType = detectedChallenge,
                isSecurityChallenge = true,
                isValidImageHeader = false
            )
        }

        return BufferVerificationResult(
            detectedContentType = normalizedHeader.ifBlank { "application/octet-stream" },
            isSecurityChallenge = false,
            isValidImageHeader = isValidImageContentType(headerContentType)
        )
    }

    @Throws(CdnSecurityChallengeException::class)
    fun decodeOrDiagnose(
        imageUrl: String,
        bytes: ByteArray,
        headerContentType: String?
    ): Bitmap? {
        val prefix = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8)
        val preCheckChallenge = detectSecurityChallengeContentType(headerContentType, prefix)
        if (preCheckChallenge != null) {
            runCatching {
                Log.w(
                    TAG,
                    "Security/CDN challenge detected prior to BitmapFactory decode: Content-Type=$preCheckChallenge url=$imageUrl"
                )
            }
            throw CdnSecurityChallengeException(imageUrl, preCheckChallenge, openInBrowserRequired = true)
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            val verifiedChallenge = detectSecurityChallengeContentType(headerContentType, prefix)
            if (verifiedChallenge != null) {
                runCatching {
                    Log.w(
                        TAG,
                        "BitmapFactory returned null bitmap due to Security/CDN challenge: Content-Type=$verifiedChallenge url=$imageUrl"
                    )
                }
                throw CdnSecurityChallengeException(imageUrl, verifiedChallenge, openInBrowserRequired = true)
            }
            runCatching {
                Log.w(
                    TAG,
                    "BitmapFactory returned null bitmap for $imageUrl (Content-Type=${headerContentType ?: "unknown"}) — suggest per-image cache bypass"
                )
            }
        }
        return bitmap
    }
}

object ImageDiagnostics {

    val BACKOFF_DELAYS_MS = longArrayOf(400L, 800L, 1600L)
    const val MAX_AUTO_RETRIES = 3

    fun getBackoffDelayMs(attemptIndex: Int): Long {
        if (attemptIndex < 0) return BACKOFF_DELAYS_MS[0]
        return BACKOFF_DELAYS_MS[minOf(attemptIndex, BACKOFF_DELAYS_MS.lastIndex)]
    }

    fun diagnoseBitmapDecodeFailure(
        headerContentType: String? = null,
        bufferPrefix: ByteArray? = null,
        httpStatus: Int? = null,
        errorMessage: String? = null
    ): ImageDiagnostic {
        val verification = BitmapDecodeGuard.verifyReceivedImageBuffer(headerContentType, bufferPrefix)
        if (verification.isSecurityChallenge) {
            return classifyImageError(
                httpStatus = httpStatus ?: 403,
                diagnosticHeader = if (verification.detectedContentType.contains("xml")) "XML_CHALLENGE" else "HTML_CHALLENGE",
                receivedContentType = verification.detectedContentType
            )
        }

        return classifyImageError(
            httpStatus = httpStatus,
            diagnosticHeader = "NULL_BITMAP",
            receivedContentType = verification.detectedContentType,
            errorMessage = errorMessage ?: "BitmapFactory returned a null bitmap. Could not decode image data"
        )
    }

    fun classifyImageThrowable(throwable: Throwable): ImageDiagnostic {
        when (throwable) {
            is CdnSecurityChallengeException -> {
                return classifyImageError(
                    httpStatus = throwable.httpStatus,
                    diagnosticHeader = if (throwable.receivedContentType.contains("xml")) "XML_CHALLENGE" else "HTML_CHALLENGE",
                    receivedContentType = throwable.receivedContentType,
                    errorMessage = throwable.message
                )
            }
            is NullBitmapDecodeException -> {
                return classifyImageError(
                    diagnosticHeader = "NULL_BITMAP",
                    receivedContentType = throwable.receivedContentType,
                    errorMessage = throwable.message
                )
            }
            is HttpException -> {
                val diagHeader = runCatching { throwable.response.headers["X-Manga-Diagnostic"] }.getOrNull()
                val receivedType = runCatching {
                    throwable.response.headers["X-Manga-Received-Content-Type"]
                        ?: throwable.response.headers["Content-Type"]
                }.getOrNull()
                return classifyImageError(
                    httpStatus = throwable.response.code,
                    diagnosticHeader = diagHeader,
                    errorMessage = throwable.message,
                    receivedContentType = receivedType
                )
            }
            is SocketTimeoutException -> {
                return classifyImageError(
                    httpStatus = 504,
                    diagnosticHeader = "TIMEOUT",
                    errorMessage = throwable.message
                )
            }
        }

        val cause = throwable.cause
        if (cause != null && cause !== throwable) {
            if (cause is CdnSecurityChallengeException || cause is NullBitmapDecodeException) {
                return classifyImageThrowable(cause)
            }
        }

        val msg = throwable.message.orEmpty()
        val parsedCode = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.toIntOrNull()
        val parsedType = Regex("""\((text/html|application/xml|text/xml|application/xhtml\+xml)""", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)

        return classifyImageError(
            httpStatus = parsedCode,
            errorMessage = msg,
            receivedContentType = parsedType
        )
    }

    fun classifyImageError(
        httpStatus: Int? = null,
        diagnosticHeader: String? = null,
        errorMessage: String? = null,
        receivedContentType: String? = null
    ): ImageDiagnostic {
        val header = diagnosticHeader.orEmpty().uppercase()
        val msg = errorMessage.orEmpty().lowercase()
        val receivedType = BitmapDecodeGuard.normalizeContentType(receivedContentType)

        if (
            header == "HTML_CHALLENGE" ||
            header == "XML_CHALLENGE" ||
            header == "CDN_CHALLENGE" ||
            BitmapDecodeGuard.isChallengeContentType(receivedType) ||
            msg.contains("html-sicherheitsabfrage") ||
            msg.contains("cdn-sicherheitsabfrage") ||
            msg.contains("security/cdn challenge")
        ) {
            val isXml = receivedType.contains("xml") || header == "XML_CHALLENGE"
            val displayType = receivedType.ifBlank { if (isXml) "application/xml" else "text/html" }
            return ImageDiagnostic(
                kind = DiagnosticKind.HTML_CHALLENGE,
                httpStatus = httpStatus ?: 403,
                receivedContentType = displayType,
                title = if (isXml) {
                    "Sicherheits-/CDN-Prüfung (application/xml erkannt)"
                } else {
                    "Sicherheits-/CDN-Prüfung (text/html erkannt)"
                },
                description = if (isXml) {
                    "Statt eines Bildes (image/*) lieferte der Server eine XML-Antwort ($displayType, z. B. CDN-/AccessDenied-Challenge), wodurch der Bild-Decoder abgebrochen wurde."
                } else {
                    "Statt eines Bildes (image/*) lieferte der Server eine HTML-Sicherheitsseite ($displayType, z. B. Cloudflare-/Bot-Prüfung), was zu einem Null-Bitmap-Fehler führt."
                },
                hint = "Öffne die Seite direkt im Browser, um die Sicherheits-/CDN-Prüfung abzuschließen und frische Cookies zu erhalten.",
                severity = DiagnosticSeverity.AMBER,
                shouldAutoRetry = false,
                suggestCacheBypass = true,
                suggestSessionRefresh = true,
                suggestOpenBrowser = true
            )
        }

        if (
            header == "NULL_BITMAP" ||
            header == "DECODE_ERROR" ||
            msg.contains("null bitmap") ||
            msg.contains("could not decode") ||
            msg.contains("bitmapfactory")
        ) {
            return ImageDiagnostic(
                kind = DiagnosticKind.DECODE_ERROR,
                httpStatus = httpStatus,
                receivedContentType = receivedType.ifBlank { null },
                title = "Dekodierungsfehler (Null Bitmap)",
                description = "Die Bilddatei konnte nicht dekodiert werden (Null Bitmap) — häufig verursacht durch einen beschädigten oder unvollständigen Eintrag im lokalen Cache.",
                hint = "Nutze „Cache-Bypass für dieses Bild“, um den defekten Cache-Eintrag zu verwerfen und die Datei frisch vom Server zu laden.",
                severity = DiagnosticSeverity.AMBER,
                shouldAutoRetry = false,
                suggestCacheBypass = true,
                suggestSessionRefresh = true,
                suggestOpenBrowser = true
            )
        }

        if (httpStatus == 404) {
            return ImageDiagnostic(
                kind = DiagnosticKind.HTTP_404,
                httpStatus = 404,
                title = "HTTP 404: Datei existiert nicht",
                description = "Diese Bilddatei ist auf dem Manga-Server nicht vorhanden oder der Link im Seitenquelltext ist veraltet.",
                hint = "Kein automatischer Retry – fehlende Seiten werden sofort markiert.",
                severity = DiagnosticSeverity.CRIMSON,
                shouldAutoRetry = false,
                suggestCacheBypass = false,
                suggestSessionRefresh = false,
                suggestOpenBrowser = false
            )
        }

        if (httpStatus == 403 || httpStatus == 401) {
            return ImageDiagnostic(
                kind = DiagnosticKind.HTTP_403,
                httpStatus = httpStatus,
                title = "HTTP $httpStatus: Zugriff verweigert",
                description = "Die Datei existiert, aber das CDN blockiert den direkten Abruf (Anti-Hotlinking oder abgelaufene Sitzung).",
                hint = "Referer-Fallback und Sitzungserneuerung mit Cache-Bypass ausführen.",
                severity = DiagnosticSeverity.AMBER,
                shouldAutoRetry = false,
                suggestCacheBypass = false,
                suggestSessionRefresh = true,
                suggestOpenBrowser = true
            )
        }

        if (httpStatus == 429) {
            return ImageDiagnostic(
                kind = DiagnosticKind.HTTP_429,
                httpStatus = 429,
                title = "HTTP 429: Zu viele Anfragen (Rate-Limit)",
                description = "Die Datei ist vorhanden, aber der Bildserver drosselt temporär wegen vieler paralleler Seitenabrufe.",
                hint = "Automatischer Retry mit Exponential Backoff (400 ms → 800 ms → 1600 ms) aktiv.",
                severity = DiagnosticSeverity.YELLOW,
                shouldAutoRetry = true,
                suggestCacheBypass = false,
                suggestSessionRefresh = false,
                suggestOpenBrowser = false
            )
        }

        if (
            httpStatus == 408 ||
            httpStatus == 504 ||
            header == "TIMEOUT" ||
            msg.contains("timeout") ||
            msg.contains("aborted")
        ) {
            val code = httpStatus ?: 504
            return ImageDiagnostic(
                kind = DiagnosticKind.TIMEOUT,
                httpStatus = code,
                title = "HTTP $code: Zeitüberschreitung (Timeout)",
                description = "Der CDN-Knoten antwortete nicht innerhalb des 15s/20s-Zeitfensters.",
                hint = "Kürzeres Timeout schützt vor ewigen Ladekreisen – erneuter Versuch behebt Knoten-Hänger oft sofort.",
                severity = DiagnosticSeverity.SLATE,
                shouldAutoRetry = true,
                suggestCacheBypass = false,
                suggestSessionRefresh = false,
                suggestOpenBrowser = false
            )
        }

        if (httpStatus != null && httpStatus in 500..599) {
            return ImageDiagnostic(
                kind = DiagnosticKind.HTTP_5XX,
                httpStatus = httpStatus,
                title = "HTTP $httpStatus: Server vorübergehend gestört",
                description = "Der Bildserver meldet eine temporäre Überlastung oder einen internen Fehler.",
                hint = "Automatischer Retry im Hintergrund wird durchgeführt.",
                severity = DiagnosticSeverity.SLATE,
                shouldAutoRetry = true,
                suggestCacheBypass = false,
                suggestSessionRefresh = false,
                suggestOpenBrowser = false
            )
        }

        return ImageDiagnostic(
            kind = DiagnosticKind.NETWORK_ERROR,
            httpStatus = httpStatus,
            title = "Verbindungsfehler beim Bildabruf",
            description = "Die Bilddatei konnte wegen eines Verbindungsabbruchs oder ungültiger Bilddaten nicht geladen werden.",
            hint = "Mit „Erneut versuchen“ oder „Cache-Bypass für dieses Bild“ direkt neu anfordern.",
            severity = DiagnosticSeverity.SLATE,
            shouldAutoRetry = true,
            suggestCacheBypass = true,
            suggestSessionRefresh = true,
            suggestOpenBrowser = false
        )
    }
}
