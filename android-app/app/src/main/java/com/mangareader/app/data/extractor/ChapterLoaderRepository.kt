package com.mangareader.app.data.extractor

import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.network.ChapterPayloadTooLargeException
import com.mangareader.app.data.network.ChapterRequestInterceptor
import com.mangareader.app.data.network.SsrfBlockedException
import com.mangareader.app.util.UrlSafety
import com.mangareader.app.util.UrlValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class WebViewFallbackReason {
    SECURITY_CHALLENGE,
    JS_RENDERED_OR_EMPTY,
    UPSTREAM_HTTP_ERROR,
    PAYLOAD_TOO_LARGE,
    NETWORK_ERROR
}

sealed class ChapterLoadOutcome {
    data class Success(val chapter: Chapter) : ChapterLoadOutcome()

    data class FallbackToWebView(
        val url: String,
        val reason: WebViewFallbackReason,
        val message: String,
        val httpStatus: Int? = null
    ) : ChapterLoadOutcome()

    data class BlockedOrInvalid(
        val url: String,
        val reason: String
    ) : ChapterLoadOutcome()
}

/**
 * Dediziertes Repository für 100 % natives HTML-Kapitel-Laden über den `chapterHttpClient`
 * (ohne Express-Server) kombiniert mit `UrlChapterAnalyzer` und klar klassifiziertem
 * Fallback auf `WebView` + `ImageExtractorJs` für JS-/SPA- oder Cloudflare-geschützte Seiten.
 */
class ChapterLoaderRepository(
    private val chapterHttpClient: OkHttpClient,
    private val urlValidator: (String) -> UrlValidationResult = UrlSafety::validateExternalUrl,
    private val imageUrlFilter: (String) -> Boolean = { UrlSafety.validateExternalUrl(it).valid },
    private val maxImagesPerChapter: Int = DEFAULT_MAX_IMAGES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun loadChapter(
        chapterUrl: String,
        refererUrl: String? = null,
        prevChapterUrl: String? = null
    ): ChapterLoadOutcome = withContext(ioDispatcher) {
        val validation = urlValidator(chapterUrl)
        if (!validation.valid) {
            return@withContext ChapterLoadOutcome.BlockedOrInvalid(
                url = chapterUrl,
                reason = validation.reason ?: "Unsichere oder ungültige Kapitel-URL blockiert."
            )
        }

        val safeUrl = validation.normalizedUrl ?: chapterUrl.trim()
        val safeReferer = refererUrl?.trim()?.takeIf { it.isNotEmpty() && urlValidator(it).valid }

        val request = Request.Builder()
            .url(safeUrl)
            .apply {
                if (safeReferer != null) {
                    header("Referer", safeReferer)
                }
            }
            .build()

        try {
            chapterHttpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val finalValidation = urlValidator(finalUrl)
                if (!finalValidation.valid) {
                    return@withContext ChapterLoadOutcome.BlockedOrInvalid(
                        url = finalUrl,
                        reason = finalValidation.reason ?: "Weiterleitungsziel aus Sicherheitsgründen blockiert."
                    )
                }

                if (response.code == 403 || response.code == 503) {
                    return@withContext ChapterLoadOutcome.FallbackToWebView(
                        url = finalUrl,
                        reason = WebViewFallbackReason.SECURITY_CHALLENGE,
                        message = "HTTP ${response.code}: Sicherheits-/Cloudflare-Prüfung aktiv — nutze WebView-Fallback.",
                        httpStatus = response.code
                    )
                }

                if (!response.isSuccessful) {
                    return@withContext ChapterLoadOutcome.FallbackToWebView(
                        url = finalUrl,
                        reason = WebViewFallbackReason.UPSTREAM_HTTP_ERROR,
                        message = "HTTP ${response.code} beim nativen Laden — nutze WebView-Fallback.",
                        httpStatus = response.code
                    )
                }

                val html = response.body?.string().orEmpty()
                if (html.isBlank()) {
                    return@withContext ChapterLoadOutcome.FallbackToWebView(
                        url = finalUrl,
                        reason = WebViewFallbackReason.JS_RENDERED_OR_EMPTY,
                        message = "Leere HTML-Antwort — wechsle auf WebView-Extraktion."
                    )
                }

                val extractedImages = UrlChapterAnalyzer
                    .extractImagesFromHtml(html, finalUrl)
                    .filter(imageUrlFilter)
                    .take(maxImagesPerChapter)

                if (extractedImages.isEmpty()) {
                    val isChallenge = isBotOrCloudflareChallengeHtml(html)
                    return@withContext ChapterLoadOutcome.FallbackToWebView(
                        url = finalUrl,
                        reason = if (isChallenge) {
                            WebViewFallbackReason.SECURITY_CHALLENGE
                        } else {
                            WebViewFallbackReason.JS_RENDERED_OR_EMPTY
                        },
                        message = if (isChallenge) {
                            "Cloudflare-/Bot-Prüfung erkannt — öffne Seite im Browser."
                        } else {
                            "Seite lädt Bilder dynamisch per JavaScript — nutze WebView-Extraktion."
                        },
                        httpStatus = response.code
                    )
                }

                val analysis = UrlChapterAnalyzer.analyzeChapterUrl(finalUrl)
                val htmlNextUrl = UrlChapterAnalyzer.extractNextChapterUrlFromHtml(html, finalUrl)
                    ?.takeIf { it != finalUrl && urlValidator(it).valid }
                val nextChapterUrl = htmlNextUrl ?: analysis.nextChapterUrl?.takeIf { urlValidator(it).valid }
                val resolvedPrevUrl = prevChapterUrl
                    ?.takeIf { it.isNotBlank() && it != finalUrl }
                    ?: analysis.prevChapterUrl?.takeIf { urlValidator(it).valid }

                val title = UrlChapterAnalyzer.extractTitleFromHtml(html, finalUrl)
                    .ifBlank { UrlChapterAnalyzer.extractChapterTitleFromUrl(finalUrl) }

                ChapterLoadOutcome.Success(
                    Chapter(
                        title = title,
                        chapterUrl = finalUrl,
                        images = extractedImages,
                        nextChapterUrl = nextChapterUrl,
                        prevChapterUrl = resolvedPrevUrl,
                        isOffline = false
                    )
                )
            }
        } catch (ssrf: SsrfBlockedException) {
            ChapterLoadOutcome.BlockedOrInvalid(
                url = ssrf.targetUrl,
                reason = ssrf.reason
            )
        } catch (tooLarge: ChapterPayloadTooLargeException) {
            ChapterLoadOutcome.FallbackToWebView(
                url = safeUrl,
                reason = WebViewFallbackReason.PAYLOAD_TOO_LARGE,
                message = tooLarge.message
                    ?: "Kapitelseite überschreitet das ${ChapterRequestInterceptor.MAX_HTML_BYTES / (1024 * 1024)}-MB-Limit."
            )
        } catch (e: Exception) {
            ChapterLoadOutcome.FallbackToWebView(
                url = safeUrl,
                reason = WebViewFallbackReason.NETWORK_ERROR,
                message = "Natives Laden fehlgeschlagen (${e.message ?: "Netzwerkfehler"}) — nutze WebView-Fallback."
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_IMAGES = 200

        private val BOT_CHALLENGE_MARKERS = listOf(
            "<title>just a moment",
            "cf-browser-verification",
            "attention required! | cloudflare",
            "challenges.cloudflare.com",
            "cf-turnstile",
            "checking your browser before accessing",
            "ddos-guard",
            "enable javascript and cookies to continue"
        )

        fun isBotOrCloudflareChallengeHtml(html: String): Boolean {
            val head = html.take(8192).lowercase()
            return BOT_CHALLENGE_MARKERS.any { marker -> head.contains(marker) }
        }
    }
}
