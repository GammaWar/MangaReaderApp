package com.mangareader.app

import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.Options
import com.mangareader.app.data.cache.CachePruneReport
import com.mangareader.app.data.cache.CacheStats
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.network.BitmapDecodeGuard
import com.mangareader.app.data.network.CdnSecurityChallengeException
import com.mangareader.app.data.network.ImageDiagnostics
import com.mangareader.app.data.network.NullBitmapDecodeException
import com.mangareader.app.util.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Zentrale Application-Klasse mit:
 * - Konfigurierbarem ChapterCacheManager (Standardlimit 4 GB, Bereich 512 MB – 32 GB,
 *   ohne automatische 31-Tage-Löschung — Bereinigung erfolgt manuell oder bei Überschreiten des Limits)
 * - Gehärtetem OkHttp/Coil-Client (15s Connect / 20s Read Timeout, Cookie-Fallback,
 *   3-Stufen-Referer-Kette, Exponential Backoff für 429/5xx, HTML/XML-Challenge- & Null-Bitmap-Schutz,
 *   sicherem Bild-Disk-Cache).
 */
class MangaReaderApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var cacheManager: ChapterCacheManager
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    private lateinit var coilCacheDir: File
    private lateinit var httpCacheDir: File
    private lateinit var chapterCacheDir: File
    private lateinit var legacyCoilCacheDir: File

    override fun onCreate() {
        super.onCreate()

        coilCacheDir = File(cacheDir, COIL_CACHE_DIR_NAME)
        httpCacheDir = File(cacheDir, HTTP_CACHE_DIR_NAME)
        chapterCacheDir = File(cacheDir, CHAPTER_CACHE_DIR_NAME)
        legacyCoilCacheDir = File(cacheDir, LEGACY_COIL_CACHE_DIR_NAME)

        val prefs = getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        val configuredLimitBytes = ChapterCacheManager.clampCacheLimitBytes(
            prefs.getLong(KEY_CACHE_LIMIT_BYTES, ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES)
        )

        cacheManager = ChapterCacheManager(
            cacheRootDir = cacheDir,
            managedDirectories = listOf(coilCacheDir, httpCacheDir, chapterCacheDir, legacyCoilCacheDir),
            initialMaxSizeBytes = configuredLimitBytes
        )

        okHttpClient = buildHardenedOkHttpClient(httpCacheDir)

        // Speicherlimit-Prüfung beim App-Start + periodisch alle 6 Stunden (ohne automatische 31-Tage-Löschung)
        applicationScope.launch {
            val startupReport = cacheManager.pruneIfNeeded(forceCheck = true)
            Log.i(
                TAG,
                "Startup-Cache-Prüfung: ${startupReport.removedSizeEvictedCount} Einträge wegen Speicherlimit entfernt, " +
                    "${startupReport.freedBytes / (1024 * 1024)} MB freigegeben."
            )
            cacheManager.startPeriodicMaintenance(applicationScope)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val dir = if (::coilCacheDir.isInitialized) coilCacheDir else File(cacheDir, COIL_CACHE_DIR_NAME)
        val client = if (::okHttpClient.isInitialized) {
            okHttpClient
        } else {
            buildHardenedOkHttpClient(File(cacheDir, HTTP_CACHE_DIR_NAME))
        }

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .components {
                add(GuardedBitmapDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(dir)
                    // Permit up to configurable 32 GB max; ChapterCacheManager enforces exact user limit
                    .maxSizeBytes(ChapterCacheManager.MAX_CONFIGURABLE_CACHE_SIZE_BYTES)
                    .build()
            }
            .crossfade(false) // Flackerfreies Rendering ohne Aufblitzen
            .respectCacheHeaders(false)
            .build()
    }

    suspend fun getCacheStats(): CacheStats = cacheManager.getStats()

    fun getConfiguredCacheLimitMb(): Int =
        ChapterCacheManager.bytesToMb(cacheManager.getMaxSizeBytes())

    suspend fun updateCacheLimitBytes(newMaxBytes: Long): CachePruneReport {
        val clamped = ChapterCacheManager.clampCacheLimitBytes(newMaxBytes)
        getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CACHE_LIMIT_BYTES, clamped)
            .apply()
        cacheManager.updateMaxSizeBytes(clamped)
        return cacheManager.pruneIfNeeded(forceCheck = true)
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearCacheManually(): CachePruneReport = withContext(Dispatchers.IO) {
        runCatching {
            val loader = Coil.imageLoader(this@MangaReaderApplication)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        runCatching { okHttpClient.cache?.evictAll() }
        cacheManager.clearAllManually()
    }

    suspend fun runManualCacheLimitCheck(): CachePruneReport =
        cacheManager.pruneIfNeeded(forceCheck = true)

    @OptIn(ExperimentalCoilApi::class)
    suspend fun invalidateSingleImageCache(imageUrl: String) = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext
        runCatching {
            val loader = Coil.imageLoader(this@MangaReaderApplication)
            loader.memoryCache?.remove(MemoryCache.Key(imageUrl))
            loader.diskCache?.remove(imageUrl)
        }
        runCatching {
            okHttpClient.cache?.urls()?.let { iter ->
                while (iter.hasNext()) {
                    if (iter.next() == imageUrl) {
                        iter.remove()
                    }
                }
            }
        }
    }

    private fun buildHardenedOkHttpClient(httpCacheDir: File): OkHttpClient {
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
            .retryOnConnectionFailure(true)
            .cache(Cache(httpCacheDir, HTTP_DISK_CACHE_LIMIT_BYTES))
            .addInterceptor(MangaHeaderAndChallengeInterceptor())
            .addNetworkInterceptor(SafeImageCacheInterceptor(cacheManager, applicationScope))
            .build()
    }

    companion object {
        private const val TAG = "MangaReaderApp"
        private const val CACHE_PREFS_NAME = "mangareader_cache_prefs"
        private const val KEY_CACHE_LIMIT_BYTES = "cache_limit_bytes"
        private const val COIL_CACHE_DIR_NAME = "image_cache"
        private const val HTTP_CACHE_DIR_NAME = "http_cache"
        private const val CHAPTER_CACHE_DIR_NAME = "chapter_cache"
        private const val LEGACY_COIL_CACHE_DIR_NAME = "manga_image_cache"

        private const val HTTP_DISK_CACHE_LIMIT_BYTES: Long = 1L * 1024L * 1024L * 1024L
        const val HEADER_BYPASS_CACHE = "X-Manga-Bypass-Cache"
        const val PARAM_IMAGE_URL = "manga_image_url"
    }
}

/**
 * Coil Decoder Wrapper für frühe HTML/XML-Challenge-Erkennung & Null-Bitmap-Diagnose:
 * - Prüft die führenden 256 Bytes + MIME-Type vor der BitmapFactory-Dekodierung.
 * - Erkennt `text/html` und `application/xml` (auch getarnt als HTTP 200) und wirft [CdnSecurityChallengeException].
 * - Fängt Null-Bitmap-Dekodierungsfehler ab und wirft [NullBitmapDecodeException] für direkten Cache-Bypass.
 */
private class GuardedBitmapDecoder(
    private val source: SourceResult,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val delegateFactory: BitmapFactoryDecoder.Factory
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val imageUrl = options.parameters.value<String>(MangaReaderApplication.PARAM_IMAGE_URL)
            ?: source.source.fileOrNull()?.toFile()?.absolutePath
            ?: "Bild"

        val prefixString = runCatching {
            val peeked = source.source.source().peek()
            peeked.request(256)
            val byteCount = minOf(peeked.buffer.size, 256L).toInt()
            if (byteCount > 0) String(peeked.readByteArray(byteCount.toLong()), Charsets.UTF_8) else ""
        }.getOrDefault("")

        val preChallenge = BitmapDecodeGuard.detectSecurityChallengeContentType(source.mimeType, prefixString)
        if (preChallenge != null) {
            Log.w(
                "GuardedBitmapDecoder",
                "Security/CDN challenge detected prior to BitmapFactory decode: Content-Type=$preChallenge url=$imageUrl"
            )
            throw CdnSecurityChallengeException(imageUrl, preChallenge, openInBrowserRequired = true)
        }

        val delegate = delegateFactory.create(source, options, imageLoader)
        return try {
            delegate.decode() ?: throwDecodeOrChallenge(imageUrl, source.mimeType, prefixString, null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: CdnSecurityChallengeException) {
            throw ex
        } catch (ex: NullBitmapDecodeException) {
            throw ex
        } catch (err: Throwable) {
            throwDecodeOrChallenge(imageUrl, source.mimeType, prefixString, err)
        }
    }

    private fun throwDecodeOrChallenge(
        imageUrl: String,
        mimeType: String?,
        prefixString: String,
        cause: Throwable?
    ): Nothing {
        val postChallenge = BitmapDecodeGuard.detectSecurityChallengeContentType(mimeType, prefixString)
        if (postChallenge != null) {
            throw CdnSecurityChallengeException(imageUrl, postChallenge, openInBrowserRequired = true)
        }
        Log.w(
            "GuardedBitmapDecoder",
            "BitmapFactory returned null bitmap for $imageUrl (Content-Type=${mimeType ?: "unknown"})",
            cause
        )
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
 * OkHttp Interceptor mit:
 * - Cookie-Fallback (Bild-URL, Referer, Kapitel-Origin, Bild-Origin)
 * - Sicherem Accept-Header (ohne erzwungenes AVIF)
 * - 3-Stufen-Referer-Fallback (Kapitel-URL -> Kapitel-Origin -> Bild-Origin -> Ohne Referer)
 * - Exponential Backoff (400ms -> 800ms -> 1600ms) bei HTTP 429 / 500–504 / Timeout
 * - Früher HTML- & XML-Challenge-Erkennung via peekBody(256)
 */
private class MangaHeaderAndChallengeInterceptor : Interceptor {
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
        val imageOrigin = "${cleanedRequest.url.scheme}://${cleanedRequest.url.host}/"

        val mergedCookies = resolveMergedCookies(imageUrlStr, refererHeader, chapterOrigin, imageOrigin)
        val refererTiers = listOf(refererHeader, chapterOrigin, imageOrigin, null).distinct()

        var lastResponse: Response? = null
        var lastChallengeType: String? = null
        var lastException: IOException? = null

        for (tierReferer in refererTiers) {
            var retryAttempt = 0
            while (retryAttempt <= ImageDiagnostics.MAX_AUTO_RETRIES) {
                lastResponse?.close()
                lastResponse = null

                val builder = cleanedRequest.newBuilder()
                    .removeHeader("Origin") // Verhindert Ablehnungen strenger Bild-CDNs
                    .header("User-Agent", AppConstants.USER_AGENT)
                    .header("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8")
                    .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\"")
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
                } catch (ioe: IOException) {
                    lastException = ioe
                    if (retryAttempt < ImageDiagnostics.MAX_AUTO_RETRIES) {
                        val backoff = ImageDiagnostics.getBackoffDelayMs(retryAttempt)
                        retryAttempt++
                        sleepSafely(backoff)
                        continue
                    }
                    throw ioe
                }

                val contentType = response.header("Content-Type").orEmpty()
                val peekPrefix = runCatching {
                    response.peekBody(256).string()
                }.getOrDefault("")

                val detectedChallengeType = BitmapDecodeGuard.detectSecurityChallengeContentType(
                    headerContentType = contentType,
                    bufferPrefix = peekPrefix
                )

                // 1. Verifiziertes echtes Bild -> sofort zurückgeben
                if (response.isSuccessful && detectedChallengeType == null && BitmapDecodeGuard.isValidImageContentType(contentType)) {
                    return response
                }

                // Falls Server keinen expliziten image/* Content-Type sendet, aber erfolgreiche Binärdaten ohne Challenge liefert
                if (response.isSuccessful && detectedChallengeType == null && contentType.isBlank()) {
                    return response
                }

                // 2. HTTP 404: Datei fehlt definitiv -> kein Retry
                if (response.code == 404) {
                    return response
                }

                // 3. HTML- oder XML-Challenge -> nächsten Referer-Tier versuchen; erst nach allen Tiers Exception werfen
                if (detectedChallengeType != null) {
                    lastChallengeType = detectedChallengeType
                    Log.w(
                        "MangaReaderApp",
                        "Security/CDN challenge detected for $imageUrlStr (Content-Type=$detectedChallengeType, tier=$tierReferer)"
                    )
                    response.close()
                    break // Nächsten Referer-Tier ausprobieren
                }

                // 4. HTTP 403 / 401 -> nächsten Referer-Tier versuchen
                if (response.code == 403 || response.code == 401) {
                    lastChallengeType = null
                    lastResponse = response
                    break // Nächsten Referer-Tier ausprobieren
                }

                // 5. HTTP 429 oder 500..504 -> Exponential Backoff (400ms -> 800ms -> 1600ms)
                if (response.code == 429 || response.code in 500..504) {
                    if (retryAttempt < ImageDiagnostics.MAX_AUTO_RETRIES) {
                        response.close()
                        val backoff = ImageDiagnostics.getBackoffDelayMs(retryAttempt)
                        retryAttempt++
                        sleepSafely(backoff)
                        continue
                    }
                    return response
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

        return lastResponse ?: throw (lastException ?: IOException("Verbindung zum Bild-Server fehlgeschlagen"))
    }

    private fun sleepSafely(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun resolveMergedCookies(vararg targets: String?): String? {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val parts = linkedMapOf<String, String>()

        targets.filterNotNull().distinct().forEach { target ->
            cookieManager.getCookie(target)
                ?.split(";")
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
        return if (parts.isEmpty()) null else parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/**
 * Network-Interceptor: Speichert ausschließlich verifizierte Bild-Antworten (`image/...`) im Cache
 * (niemals HTML oder XML) und triggert danach die Speicherlimit-Prüfung (Standard: 4 GB).
 */
private class SafeImageCacheInterceptor(
    private val cacheManager: ChapterCacheManager,
    private val scope: CoroutineScope
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type")

        if (!response.isSuccessful || !BitmapDecodeGuard.isValidImageContentType(contentType)) {
            return response
        }

        cacheManager.scheduleThresholdCheck(scope)

        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=2592000, immutable")
            .build()
    }
}
