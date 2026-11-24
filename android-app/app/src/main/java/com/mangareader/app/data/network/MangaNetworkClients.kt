package com.mangareader.app.data.network

import android.util.Log
import android.webkit.CookieManager
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import com.mangareader.app.MangaReaderApplication
import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import com.mangareader.app.util.UrlValidationResult
import kotlinx.coroutines.CancellationException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Abstraktion für WebView-Cookies, damit Netzwerk-Interceptors ohne Android-Runtime testbar bleiben.
 */
fun interface CookieProvider {
    fun getCookie(url: String): String?
}

object WebViewCookieProvider : CookieProvider {
    override fun getCookie(url: String): String? {
        return runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }
}

object CookieMerger {
    fun mergeCookies(
        cookieProvider: CookieProvider,
        explicitCookieHeader: String? = null,
        vararg targets: String?
    ): String? {
        val parts = linkedMapOf<String, String>()

        fun addRawCookieHeader(raw: String?) {
            raw?.split(";")
                ?.map { it.trim() }
                ?.filter { it.contains("=") }
                ?.forEach { pair ->
                    val idx = pair.indexOf('=')
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isNotEmpty()) {
                        parts[name] = value
                    }
                }
        }

        targets.filterNotNull().filter { it.isNotBlank() }.distinct().forEach { target ->
            addRawCookieHeader(cookieProvider.getCookie(target))
        }
        // Explicit request cookies take precedence if provided
        addRawCookieHeader(explicitCookieHeader)

        return if (parts.isEmpty()) null else parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/**
 * Deduplizierte Referer-Fallback-Kette für Manga-CDNs:
 * 1. Exakte Kapitel-URL (falls angegeben)
 * 2. Kapitel-Origin (falls verschieden von Kapitel-URL)
 * 3. Bild-Host-Origin (falls verschieden)
 * 4. Ohne Referer (`null`)
 */
object RefererStrategy {
    fun buildRefererTiers(imageUrl: String, refererHeader: String?): List<String?> {
        val cleanReferer = refererHeader?.trim()?.takeIf { it.isNotEmpty() }
        val chapterOrigin = cleanReferer?.let { AppConstants.getRefererForUrl(it) }
        val imageOrigin = AppConstants.getRefererForUrl(imageUrl).takeIf { it.isNotBlank() && it != imageUrl }

        val tiers = mutableListOf<String?>()
        fun addUnique(value: String?) {
            if (!tiers.contains(value)) {
                tiers.add(value)
            }
        }

        if (cleanReferer != null) addUnique(cleanReferer)
        if (chapterOrigin != null) addUnique(chapterOrigin)
        if (imageOrigin != null) addUnique(imageOrigin)
        addUnique(null)
        return tiers
    }
}

/**
 * DNS-Resolver mit SSRF-Schutz gegen DNS-Rebinding / interne DNS-Einträge:
 * Lehnt aufgelöste private, Loopback-, Link-Local- oder Metadaten-IPs vor dem TCP-Connect ab.
 */
class SafeDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty()) {
            throw SsrfBlockedException(hostname, "Host konnte nicht aufgelöst werden.")
        }
        for (addr in addresses) {
            val hostAddr = addr.hostAddress.orEmpty()
            if (UrlSafety.isPrivateOrLoopbackIp(hostAddr)) {
                throw SsrfBlockedException(
                    targetUrl = hostname,
                    reason = "DNS-Ziel zeigt auf eine interne oder lokale IP-Adresse ($hostAddr)."
                )
            }
        }
        return addresses
    }
}

/**
 * Application-Interceptor für manuelles, SSRF-gehärtetes Redirect-Handling:
 * Läuft VOR OkHttps `ConnectInterceptor`, sodass weder für die Initial-URL noch für
 * irgendeinen Redirect-Hop jemals ein TCP-Socket zu internen/blockierten Zielen geöffnet wird.
 * Entfernt zudem `Cookie` und `Authorization` bei Cross-Origin-Weiterleitungen.
 */
class SafeRedirectInterceptor(
    private val urlValidator: (String) -> UrlValidationResult = UrlSafety::validateExternalUrl,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var currentRequest = chain.request()

        for (hop in 0..maxRedirects) {
            val currentUrl = currentRequest.url.toString()
            val check = urlValidator(currentUrl)
            if (!check.valid) {
                throw SsrfBlockedException(
                    targetUrl = currentUrl,
                    reason = check.reason ?: "Ziel-URL aus Sicherheitsgründen blockiert."
                )
            }

            val response = chain.proceed(currentRequest)
            if (response.code !in REDIRECT_STATUS_CODES) {
                return response
            }

            val location = response.header("Location")?.trim()
            if (location.isNullOrEmpty()) {
                return response
            }

            val nextHttpUrl = currentRequest.url.resolve(location)
            if (nextHttpUrl == null) {
                return response
            }

            val nextUrlStr = nextHttpUrl.toString()
            val nextCheck = urlValidator(nextUrlStr)
            if (!nextCheck.valid) {
                response.close()
                throw SsrfBlockedException(
                    targetUrl = nextUrlStr,
                    reason = nextCheck.reason ?: "Weiterleitungsziel aus Sicherheitsgründen blockiert."
                )
            }

            if (hop == maxRedirects) {
                response.close()
                throw IOException("Zu viele Weiterleitungen (max. $maxRedirects).")
            }

            val sameOrigin = currentRequest.url.scheme.equals(nextHttpUrl.scheme, ignoreCase = true) &&
                currentRequest.url.host.equals(nextHttpUrl.host, ignoreCase = true) &&
                currentRequest.url.port == nextHttpUrl.port

            val nextBuilder = currentRequest.newBuilder().url(nextHttpUrl)
            if (!sameOrigin) {
                nextBuilder.removeHeader("Authorization")
                nextBuilder.removeHeader("Proxy-Authorization")
                nextBuilder.removeHeader("Cookie")
                if (currentRequest.header("Sec-Fetch-Site") != null) {
                    nextBuilder.header("Sec-Fetch-Site", "cross-site")
                }
            }
            if (response.code == 303 && currentRequest.method != "GET" && currentRequest.method != "HEAD") {
                nextBuilder.method("GET", null)
            }

            response.close()
            currentRequest = nextBuilder.build()
        }

        throw IOException("Zu viele Weiterleitungen (max. $maxRedirects).")
    }

    companion object {
        const val DEFAULT_MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * OkHttp Application-Interceptor für den dedizierten `imageHttpClient`:
 * - Einheitlicher User-Agent (`AppConstants.USER_AGENT`)
 * - WebView-Cookie-Merge (Bild-URL, Referer, Kapitel-Origin, Bild-Origin)
 * - Sicherer `Accept`-Header (WebP, PNG, JPEG, kein erzwungenes AVIF)
 * - 3-Stufen-Referer-Fallback (Kapitel-URL/Origin -> Bild-Origin -> Ohne Referer)
 * - Exponential Backoff (`400ms -> 800ms -> 1600ms`) bei HTTP 429 / 500–504 / IO-Timeout
 * - 404 Fast-Fail ohne Retries oder weitere Referer-Stufen
 * - Frühe HTML/XML-Challenge-Erkennung + Magic-Byte-Validierung via `peekBody(1024)`
 */
class ImageRequestInterceptor(
    private val cookieProvider: CookieProvider = WebViewCookieProvider,
    private val sleeper: (Long) -> Unit = ::defaultSleepSafely
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val bypassCache = original.header(MangaReaderApplication.HEADER_BYPASS_CACHE) == "1"
        val cleanedRequest = if (bypassCache) {
            original.newBuilder()
                .removeHeader(MangaReaderApplication.HEADER_BYPASS_CACHE)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
        } else {
            original
        }

        val imageUrlStr = cleanedRequest.url.toString()
        val refererHeader = cleanedRequest.header("Referer")?.takeIf { it.isNotBlank() }
        val chapterOrigin = refererHeader?.let { AppConstants.getRefererForUrl(it) }
        val imageOrigin = AppConstants.getRefererForUrl(imageUrlStr)
        val explicitCookie = cleanedRequest.header("Cookie")

        val mergedCookies = CookieMerger.mergeCookies(
            cookieProvider = cookieProvider,
            explicitCookieHeader = explicitCookie,
            imageUrlStr,
            refererHeader,
            chapterOrigin,
            imageOrigin
        )
        val refererTiers = RefererStrategy.buildRefererTiers(imageUrlStr, refererHeader)

        var lastResponse: Response? = null
        var lastChallengeType: String? = null
        var lastInvalidPayloadType: String? = null
        var lastException: IOException? = null

        for (tierReferer in refererTiers) {
            var retryAttempt = 0
            while (retryAttempt <= ImageDiagnostics.MAX_AUTO_RETRIES) {
                lastResponse?.close()
                lastResponse = null

                val builder = cleanedRequest.newBuilder()
                    .removeHeader("Origin") // Verhindert Ablehnungen strenger Manga-CDNs
                    .header("User-Agent", AppConstants.USER_AGENT)
                    .header("Accept", IMAGE_ACCEPT_HEADER)
                    .header("Accept-Language", ACCEPT_LANGUAGE_HEADER)
                    .header("Sec-Ch-Ua", SEC_CH_UA)
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Sec-Ch-Ua-Platform", "\"Android\"")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", AppConstants.computeSecFetchSite(imageUrlStr, tierReferer))

                if (tierReferer != null) {
                    builder.header("Referer", tierReferer)
                } else {
                    builder.removeHeader("Referer")
                }

                if (!mergedCookies.isNullOrBlank()) {
                    builder.header("Cookie", mergedCookies)
                }

                val response = try {
                    chain.proceed(builder.build())
                } catch (ssrf: SsrfBlockedException) {
                    // Never retry SSRF blocks across backoff or Referer tiers
                    throw ssrf
                } catch (ioe: IOException) {
                    lastException = ioe
                    if (retryAttempt < ImageDiagnostics.MAX_AUTO_RETRIES) {
                        val backoff = ImageDiagnostics.getBackoffDelayMs(retryAttempt)
                        retryAttempt++
                        sleeper(backoff)
                        continue
                    }
                    throw ioe
                }

                // 1. HTTP 404: Datei fehlt definitiv -> sofortiger Fast-Fail ohne Retry / Referer-Fallback
                if (response.code == 404) {
                    return response
                }

                // 2. HTTP 429 oder 500..504 -> Exponential Backoff (400ms -> 800ms -> 1600ms)
                if (response.code == 429 || response.code in 500..504) {
                    if (retryAttempt < ImageDiagnostics.MAX_AUTO_RETRIES) {
                        response.close()
                        val backoff = ImageDiagnostics.getBackoffDelayMs(retryAttempt)
                        retryAttempt++
                        sleeper(backoff)
                        continue
                    }
                    return response
                }

                val contentType = response.header("Content-Type").orEmpty()
                val normalizedContentType = BitmapDecodeGuard.normalizeContentType(contentType)
                val peekBytes = runCatching {
                    response.peekBody(BitmapDecodeGuard.INSPECTION_PREFIX_BYTES.toLong()).bytes()
                }.getOrDefault(ByteArray(0))

                val detectedChallengeType = BitmapDecodeGuard.detectSecurityChallengeContentType(
                    headerContentType = contentType,
                    bufferPrefix = peekBytes
                )

                // 3. HTML- oder XML-Challenge (auch getarnt als HTTP 200) -> nächsten Referer-Tier versuchen
                if (detectedChallengeType != null) {
                    lastChallengeType = detectedChallengeType
                    runCatching {
                        Log.w(
                            TAG,
                            "Security/CDN challenge detected for $imageUrlStr (Content-Type=$detectedChallengeType, tier=$tierReferer)"
                        )
                    }
                    response.close()
                    break
                }

                // 4. HTTP 403 / 401 -> nächsten Referer-Tier versuchen
                if (response.code == 403 || response.code == 401) {
                    lastChallengeType = null
                    lastResponse = response
                    break
                }

                // 5. Erfolgreiche 2xx-Antwort: Magic-Bytes & Content-Type strikt verifizieren
                if (response.isSuccessful) {
                    val hasValidType = BitmapDecodeGuard.isValidImageContentType(contentType) ||
                        normalizedContentType.isBlank() ||
                        normalizedContentType == "application/octet-stream"
                    val hasValidMagic = BitmapDecodeGuard.hasValidImageMagicBytes(
                        bufferPrefix = peekBytes,
                        contentType = if (BitmapDecodeGuard.isValidImageContentType(contentType)) contentType else null
                    )

                    if (hasValidType && hasValidMagic) {
                        return response
                    }

                    // Ungültige/leere/gespoofte Payload auf HTTP 200 -> nicht als Bild akzeptieren
                    lastInvalidPayloadType = normalizedContentType.ifBlank { "application/octet-stream" }
                    runCatching {
                        Log.w(
                            TAG,
                            "Invalid image magic bytes or empty body for $imageUrlStr (Content-Type=$lastInvalidPayloadType, tier=$tierReferer)"
                        )
                    }
                    response.close()
                    break
                }

                return response
            }
        }

        if (lastChallengeType != null) {
            lastResponse?.close()
            throw CdnSecurityChallengeException(
                imageUrl = imageUrlStr,
                receivedContentType = lastChallengeType,
                openInBrowserRequired = true
            )
        }

        if (lastResponse != null) {
            return lastResponse
        }

        if (lastInvalidPayloadType != null) {
            throw NullBitmapDecodeException(
                imageUrl = imageUrlStr,
                receivedContentType = lastInvalidPayloadType
            )
        }

        throw (lastException ?: IOException("Verbindung zum Bild-Server fehlgeschlagen"))
    }

    companion object {
        private const val TAG = "ImageRequestInterceptor"
        const val IMAGE_ACCEPT_HEADER = "image/webp,image/png,image/jpeg,image/*;q=0.8"
        const val ACCEPT_LANGUAGE_HEADER = "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"
        const val SEC_CH_UA = "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\""
    }
}

/**
 * OkHttp Network-Interceptor für den `imageHttpClient`:
 * Speichert ausschließlich verifizierte Raster-Bilder (`image/...` + gültige Magic-Bytes)
 * im Disk-Cache und markiert HTML-/XML-Challenges oder ungültige Payloads als `no-store`.
 */
class SafeImageCacheInterceptor(
    private val onImageCached: () -> Unit = {}
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type")
        val normalizedType = BitmapDecodeGuard.normalizeContentType(contentType)

        if (!response.isSuccessful) {
            return response.newBuilder()
                .header("Cache-Control", "no-store")
                .build()
        }

        val peekBytes = runCatching {
            response.peekBody(BitmapDecodeGuard.INSPECTION_PREFIX_BYTES.toLong()).bytes()
        }.getOrDefault(ByteArray(0))

        val isChallenge = BitmapDecodeGuard.detectSecurityChallengeContentType(contentType, peekBytes) != null
        val isValidType = BitmapDecodeGuard.isValidImageContentType(contentType) ||
            normalizedType.isBlank() ||
            normalizedType == "application/octet-stream"
        val isValidMagic = BitmapDecodeGuard.hasValidImageMagicBytes(
            bufferPrefix = peekBytes,
            contentType = if (BitmapDecodeGuard.isValidImageContentType(contentType)) contentType else null
        )

        if (isChallenge || !isValidType || !isValidMagic) {
            return response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .header("Cache-Control", "no-store")
                .build()
        }

        onImageCached()

        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=2592000, immutable")
            .build()
    }
}

/**
 * OkHttp Application-Interceptor für den dedizierten `chapterHttpClient`:
 * - HTML-`Accept`-Header (`text/html`, `application/xhtml+xml`, `application/xml`)
 * - Einheitlicher User-Agent (`AppConstants.USER_AGENT`)
 * - WebView-Cookie-Sync (Ziel-URL, Referer, Origin)
 * - Striktes 4-MB-Größenlimit (sowohl via `Content-Length` als auch per Streaming-Cap)
 * - KEINE Bild-Challenge-Blockade für `text/html` (behebt `prefetchNextChapter()`)
 */
class ChapterRequestInterceptor(
    private val cookieProvider: CookieProvider = WebViewCookieProvider,
    private val maxHtmlBytes: Long = MAX_HTML_BYTES
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val urlStr = original.url.toString()
        val refererHeader = original.header("Referer")?.takeIf { it.isNotBlank() }
        val targetOrigin = AppConstants.getRefererForUrl(urlStr)
        val explicitCookie = original.header("Cookie")

        val mergedCookies = CookieMerger.mergeCookies(
            cookieProvider = cookieProvider,
            explicitCookieHeader = explicitCookie,
            urlStr,
            refererHeader,
            targetOrigin
        )

        val builder = original.newBuilder()
            .header("User-Agent", AppConstants.USER_AGENT)
            .header("Accept", HTML_ACCEPT_HEADER)
            .header("Accept-Language", ImageRequestInterceptor.ACCEPT_LANGUAGE_HEADER)
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", AppConstants.computeSecFetchSite(urlStr, refererHeader))

        if (!mergedCookies.isNullOrBlank()) {
            builder.header("Cookie", mergedCookies)
        }

        val response = chain.proceed(builder.build())
        val declaredLength = response.header("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxHtmlBytes) {
            response.close()
            throw ChapterPayloadTooLargeException(maxHtmlBytes, urlStr)
        }

        val body = response.body ?: return response
        return response.newBuilder()
            .body(BoundedResponseBody(body, maxHtmlBytes, urlStr))
            .build()
    }

    companion object {
        const val MAX_HTML_BYTES: Long = 4L * 1024L * 1024L // 4 MB
        const val HTML_ACCEPT_HEADER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
}

private class BoundedResponseBody(
    private val delegate: ResponseBody,
    private val maxBytes: Long,
    private val url: String
) : ResponseBody() {

    private val boundedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
        private var totalRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val bytesRead = super.read(sink, byteCount)
            if (bytesRead > 0L) {
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    throw ChapterPayloadTooLargeException(maxBytes, url)
                }
            }
            return bytesRead
        }
    }.buffer()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = boundedSource

    override fun close() {
        delegate.close()
    }
}

/**
 * Coil Decoder Wrapper für frühe HTML/XML-Challenge-Erkennung, Magic-Byte-Prüfung & Null-Bitmap-Diagnose:
 * - Prüft die führenden 1024 Bytes + MIME-Type vor der `BitmapFactory`-Dekodierung.
 * - Erkennt `text/html` und `application/xml` (auch getarnt als HTTP 200) und wirft [CdnSecurityChallengeException].
 * - Fängt leere/ungültige Payloads & Null-Bitmap-Dekodierungsfehler ab und wirft [NullBitmapDecodeException].
 */
class GuardedBitmapDecoder(
    private val source: SourceResult,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val delegateFactory: BitmapFactoryDecoder.Factory
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val imageUrl = options.parameters.value<String>(MangaReaderApplication.PARAM_IMAGE_URL)
            ?: source.source.fileOrNull()?.toFile()?.absolutePath
            ?: "Bild"

        val prefixBytes = runCatching {
            val peeked = source.source.source().peek()
            val maxInspect = BitmapDecodeGuard.INSPECTION_PREFIX_BYTES.toLong()
            peeked.request(maxInspect)
            val byteCount = minOf(peeked.buffer.size, maxInspect)
            if (byteCount > 0L) peeked.readByteArray(byteCount) else ByteArray(0)
        }.getOrDefault(ByteArray(0))

        val preChallenge = BitmapDecodeGuard.detectSecurityChallengeContentType(source.mimeType, prefixBytes)
        if (preChallenge != null) {
            runCatching {
                Log.w(
                    "GuardedBitmapDecoder",
                    "Security/CDN challenge detected prior to BitmapFactory decode: Content-Type=$preChallenge url=$imageUrl"
                )
            }
            throw CdnSecurityChallengeException(imageUrl, preChallenge, openInBrowserRequired = true)
        }

        if (prefixBytes.isEmpty() || !BitmapDecodeGuard.hasValidImageMagicBytes(prefixBytes, null)) {
            runCatching {
                Log.w(
                    "GuardedBitmapDecoder",
                    "Invalid or empty image magic bytes prior to BitmapFactory decode for $imageUrl (Content-Type=${source.mimeType ?: "unknown"})"
                )
            }
            throw NullBitmapDecodeException(imageUrl, source.mimeType)
        }

        val delegate = delegateFactory.create(source, options, imageLoader)
        return try {
            delegate.decode() ?: throwDecodeOrChallenge(imageUrl, source.mimeType, prefixBytes, null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: CdnSecurityChallengeException) {
            throw ex
        } catch (ex: NullBitmapDecodeException) {
            throw ex
        } catch (err: Throwable) {
            throwDecodeOrChallenge(imageUrl, source.mimeType, prefixBytes, err)
        }
    }

    private fun throwDecodeOrChallenge(
        imageUrl: String,
        mimeType: String?,
        prefixBytes: ByteArray,
        cause: Throwable?
    ): Nothing {
        val postChallenge = BitmapDecodeGuard.detectSecurityChallengeContentType(mimeType, prefixBytes)
        if (postChallenge != null) {
            throw CdnSecurityChallengeException(imageUrl, postChallenge, openInBrowserRequired = true)
        }
        runCatching {
            Log.w(
                "GuardedBitmapDecoder",
                "BitmapFactory returned null bitmap for $imageUrl (Content-Type=${mimeType ?: "unknown"})",
                cause
            )
        }
        throw NullBitmapDecodeException(imageUrl, mimeType, cause)
    }

    class Factory : Decoder.Factory {
        private val bitmapFactory = BitmapFactoryDecoder.Factory()

        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder {
            return GuardedBitmapDecoder(result, options, imageLoader, bitmapFactory)
        }
    }
}

/**
 * Zentrale Factory für die strikt getrennten nativen OkHttp-Clients:
 * - [buildImageHttpClient]: Gehärtet für Manga-Seitenbilder (Coil & Offline-Download).
 * - [buildChapterHttpClient]: Optimiert für HTML-Kapitelseiten & Hintergrund-Pre-Fetching.
 */
object MangaNetworkClients {
    const val HTTP_DISK_CACHE_LIMIT_BYTES: Long = 1L * 1024L * 1024L * 1024L

    fun buildImageHttpClient(
        httpCacheDir: File? = null,
        cookieProvider: CookieProvider = WebViewCookieProvider,
        sleeper: (Long) -> Unit = ::defaultSleepSafely,
        urlValidator: ((String) -> UrlValidationResult)? = UrlSafety::validateExternalUrl,
        enforcePublicDns: Boolean = true,
        onImageCached: () -> Unit = {}
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(urlValidator == null)
            .followSslRedirects(urlValidator == null)
            .retryOnConnectionFailure(true)
            .apply {
                if (enforcePublicDns) {
                    dns(SafeDns())
                }
                if (httpCacheDir != null) {
                    cache(Cache(httpCacheDir, HTTP_DISK_CACHE_LIMIT_BYTES))
                }
            }
            .addInterceptor(ImageRequestInterceptor(cookieProvider = cookieProvider, sleeper = sleeper))
            .apply {
                if (urlValidator != null) {
                    addInterceptor(SafeRedirectInterceptor(urlValidator))
                }
            }
            .addNetworkInterceptor(SafeImageCacheInterceptor(onImageCached = onImageCached))
            .build()
    }

    fun buildChapterHttpClient(
        cookieProvider: CookieProvider = WebViewCookieProvider,
        maxHtmlBytes: Long = ChapterRequestInterceptor.MAX_HTML_BYTES,
        urlValidator: ((String) -> UrlValidationResult)? = UrlSafety::validateExternalUrl,
        enforcePublicDns: Boolean = true
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        }

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(urlValidator == null)
            .followSslRedirects(urlValidator == null)
            .retryOnConnectionFailure(true)
            .apply {
                if (enforcePublicDns) {
                    dns(SafeDns())
                }
            }
            .addInterceptor(
                ChapterRequestInterceptor(
                    cookieProvider = cookieProvider,
                    maxHtmlBytes = maxHtmlBytes
                )
            )
            .apply {
                if (urlValidator != null) {
                    addInterceptor(SafeRedirectInterceptor(urlValidator))
                }
            }
            .build()
    }
}

private fun defaultSleepSafely(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
