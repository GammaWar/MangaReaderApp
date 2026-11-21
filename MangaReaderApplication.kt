package com.mangareader

import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * MangaReaderApplication
 *
 * Zentrale Application-Klasse mit:
 * - Konfigurierbarem ChapterCacheManager (Standardlimit 4 GB, frei anpassbar von 512 MB bis 32 GB,
 *   ohne automatische 31-Tage-Löschung — Bereinigung erfolgt manuell oder bei Überschreiten des Limits)
 * - Gehärtetem OkHttp/Coil-Client (15s Connect / 20s Read Timeout, Cookie-Fallback,
 *   3-Stufen-Referer-Kette, HTML-Challenge-/Null-Bitmap-Schutz, sicherer image/*-Cache)
 */
class MangaReaderApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var cacheManager: ChapterCacheManager
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()

        val coilCacheDir = File(cacheDir, COIL_CACHE_DIR_NAME)
        val httpCacheDir = File(cacheDir, HTTP_CACHE_DIR_NAME)
        val chapterCacheDir = File(cacheDir, CHAPTER_CACHE_DIR_NAME)

        val prefs = getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        val configuredLimitBytes = prefs
            .getLong(KEY_CACHE_LIMIT_BYTES, DEFAULT_MAX_CACHE_SIZE_BYTES)
            .coerceIn(MIN_CACHE_SIZE_BYTES, MAX_CONFIGURABLE_CACHE_SIZE_BYTES)

        cacheManager = ChapterCacheManager(
            context = this,
            managedDirectories = listOf(coilCacheDir, httpCacheDir, chapterCacheDir),
            initialMaxSizeBytes = configuredLimitBytes,
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
        val coilCacheDir = File(cacheDir, COIL_CACHE_DIR_NAME)
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(coilCacheDir)
                    .maxSizeBytes(COIL_DISK_CACHE_LIMIT_BYTES)
                    .build()
            }
            .crossfade(false) // Flackerfreies Rendering ohne 200ms-Aufblitzen
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * Erlaubt der UI (z. B. SettingsScreen / LibraryView), das Cache-Limit dynamisch anzupassen.
     */
    suspend fun updateCacheLimitBytes(newMaxBytes: Long): CachePruneReport {
        val clamped = newMaxBytes.coerceIn(MIN_CACHE_SIZE_BYTES, MAX_CONFIGURABLE_CACHE_SIZE_BYTES)
        getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CACHE_LIMIT_BYTES, clamped)
            .apply()
        cacheManager.updateMaxSizeBytes(clamped)
        return cacheManager.pruneIfNeeded(forceCheck = true)
    }

    /**
     * Manuelle vollständige Cache-Löschung (ersetzt die entfernte 31-Tage-Automatik).
     */
    suspend fun clearCacheManually(): CachePruneReport =
        cacheManager.clearAllManually()

    /**
     * Manuelle Prüfung und Kürzung auf das aktuell konfigurierte Speicherlimit.
     */
    suspend fun runManualCacheLimitCheck(): CachePruneReport =
        cacheManager.pruneIfNeeded(forceCheck = true)

    private fun buildHardenedOkHttpClient(httpCacheDir: File): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
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

        // Konfigurierbares Speicherlimit (Standard: 4 GB, Bereich: 512 MB – 32 GB)
        const val MIN_CACHE_SIZE_BYTES: Long = 512L * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_SIZE_BYTES: Long = 4L * 1024L * 1024L * 1024L // 4 GB
        const val MAX_CONFIGURABLE_CACHE_SIZE_BYTES: Long = 32L * 1024L * 1024L * 1024L // 32 GB absolutes Maximum
        const val EVICTION_TARGET_RATIO: Double = 0.90 // 90 % Hysterese nach Überschreitung
        private const val COIL_DISK_CACHE_LIMIT_BYTES: Long = 3L * 1024L * 1024L * 1024L
        private const val HTTP_DISK_CACHE_LIMIT_BYTES: Long = 1L * 1024L * 1024L * 1024L
    }
}

/**
 * Ergebnisbericht einer proaktiven Cache-Bereinigung.
 */
data class CachePruneReport(
    val removedExpiredCount: Int,
    val removedSizeEvictedCount: Int,
    val freedBytes: Long,
    val remainingBytes: Long,
    val remainingFileCount: Int,
    val timestampMs: Long,
)

/**
 * Konfigurierbarer Cache-Manager für Kapitel- & Bild-Caches.
 *
 * Regeln:
 * 1. Keine automatische 31-Tage-Löschung — zwischengespeicherte Seiten bleiben erhalten,
 *    bis sie manuell über `clearAllManually()` gelöscht werden oder das Limit überschritten wird.
 * 2. Prüft die Gesamtgröße über alle verwalteten Cache-Ordner; überschreitet sie das
 *    konfigurierbare Limit (Standard: 4 GB), werden die ältesten Einträge bis zur 90-%-Hysterese entfernt.
 * 3. Thread-sicher über Kotlin Coroutines `Mutex` und vollständig auf `Dispatchers.IO`.
 */
class ChapterCacheManager(
    private val context: Context,
    private val managedDirectories: List<File>,
    initialMaxSizeBytes: Long = MangaReaderApplication.DEFAULT_MAX_CACHE_SIZE_BYTES,
) {
    private val pruneMutex = Mutex()
    @Volatile
    private var maxSizeBytes: Long = initialMaxSizeBytes.coerceIn(
        MangaReaderApplication.MIN_CACHE_SIZE_BYTES,
        MangaReaderApplication.MAX_CONFIGURABLE_CACHE_SIZE_BYTES
    )
    @Volatile
    private var lastPruneTimestampMs: Long = 0L

    fun getMaxSizeBytes(): Long = maxSizeBytes

    fun updateMaxSizeBytes(newMaxBytes: Long) {
        maxSizeBytes = newMaxBytes.coerceIn(
            MangaReaderApplication.MIN_CACHE_SIZE_BYTES,
            MangaReaderApplication.MAX_CONFIGURABLE_CACHE_SIZE_BYTES
        )
    }

    /**
     * Startet eine leichtgewichtige periodische Hintergrund-Wartung (alle 6 Stunden),
     * die ausschließlich das konfigurierbare Speicherlimit (Standard: 4 GB) überwacht.
     */
    fun startPeriodicMaintenance(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TimeUnit.HOURS.toMillis(6))
                pruneIfNeeded(forceCheck = true)
            }
        }
    }

    /**
     * Leichter Trigger nach neuen Bild-Downloads (gedrosselt auf max. alle 5 Minuten),
     * sodass ein Überschreiten des konfigurierten Speicherlimits unmittelbar abgefangen wird.
     */
    fun scheduleThresholdCheck(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        if (now - lastPruneTimestampMs < TimeUnit.MINUTES.toMillis(5)) return
        scope.launch(Dispatchers.IO) {
            pruneIfNeeded(forceCheck = false)
        }
    }

    suspend fun getCurrentCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        collectManagedFiles().sumOf { it.length() }
    }

    /**
     * Löscht alle zwischengespeicherten Kapitel- und Bilddateien explizit auf Nutzerwunsch.
     */
    suspend fun clearAllManually(): CachePruneReport = withContext(Dispatchers.IO) {
        pruneMutex.withLock {
            val now = System.currentTimeMillis()
            val allFiles = collectManagedFiles()
            var removedCount = 0
            var freedBytes = 0L
            var remainingBytes = 0L
            var remainingCount = 0

            for (file in allFiles) {
                val size = file.length()
                if (safeDeleteFile(file)) {
                    removedCount++
                    freedBytes += size
                } else {
                    remainingBytes += size
                    remainingCount++
                }
            }

            managedDirectories.forEach { pruneEmptySubdirectories(it) }
            lastPruneTimestampMs = now

            CachePruneReport(
                removedExpiredCount = 0,
                removedSizeEvictedCount = removedCount,
                freedBytes = freedBytes,
                remainingBytes = remainingBytes,
                remainingFileCount = remainingCount,
                timestampMs = now,
            )
        }
    }

    suspend fun pruneIfNeeded(forceCheck: Boolean = false): CachePruneReport =
        withContext(Dispatchers.IO) {
            pruneMutex.withLock {
                val now = System.currentTimeMillis()
                if (!forceCheck && now - lastPruneTimestampMs < TimeUnit.MINUTES.toMillis(5)) {
                    val existing = collectManagedFiles()
                    return@withLock CachePruneReport(
                        removedExpiredCount = 0,
                        removedSizeEvictedCount = 0,
                        freedBytes = 0L,
                        remainingBytes = existing.sumOf { it.length() },
                        remainingFileCount = existing.size,
                        timestampMs = now,
                    )
                }

                var removedSizeEvictedCount = 0
                var freedBytes = 0L

                val survivingFiles = collectManagedFiles().toMutableList()
                val currentLimitBytes = maxSizeBytes
                val targetAfterEvictionBytes =
                    (currentLimitBytes * MangaReaderApplication.EVICTION_TARGET_RATIO).toLong()

                // Speicher-Schwellenwert prüfen und älteste Einträge bis zur 90-%-Hysterese bereinigen
                var currentTotalBytes = survivingFiles.sumOf { it.length() }
                if (currentTotalBytes > currentLimitBytes) {
                    survivingFiles.sortBy { it.lastModified() }
                    val iterator = survivingFiles.iterator()
                    while (iterator.hasNext() && currentTotalBytes > targetAfterEvictionBytes) {
                        val oldest = iterator.next()
                        val size = oldest.length()
                        if (safeDeleteFile(oldest)) {
                            currentTotalBytes = (currentTotalBytes - size).coerceAtLeast(0L)
                            freedBytes += size
                            removedSizeEvictedCount++
                            iterator.remove()
                        }
                    }
                }

                // Leere verwaiste Kapitel-Unterordner entfernen
                managedDirectories.forEach { pruneEmptySubdirectories(it) }

                lastPruneTimestampMs = now

                CachePruneReport(
                    removedExpiredCount = 0,
                    removedSizeEvictedCount = removedSizeEvictedCount,
                    freedBytes = freedBytes,
                    remainingBytes = currentTotalBytes,
                    remainingFileCount = survivingFiles.size,
                    timestampMs = now,
                )
            }
        }

    private fun collectManagedFiles(): List<File> {
        val cacheRootCanonical = context.cacheDir.canonicalFile
        val result = mutableListOf<File>()

        for (dir in managedDirectories) {
            if (!dir.exists() || !dir.isDirectory) continue
            val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: continue
            // Pfad-Sicherheit: Nur Dateien strikt innerhalb von context.cacheDir anfassen
            if (!canonicalDir.path.startsWith(cacheRootCanonical.path)) continue

            canonicalDir.walkTopDown()
                .filter { it.isFile && it.name != "journal" && !it.name.endsWith(".tmp") }
                .forEach { result.add(it) }
        }
        return result
    }

    private fun safeDeleteFile(file: File): Boolean =
        runCatching { file.exists() && file.delete() }.getOrDefault(false)

    private fun pruneEmptySubdirectories(root: File) {
        if (!root.exists() || !root.isDirectory) return
        root.walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { dir ->
                if (dir.listFiles()?.isEmpty() == true) {
                    runCatching { dir.delete() }
                }
            }
    }
}

/**
 * Interceptor mit Cookie-Fallback auf die Referer-Domain, sicherem Accept-Header
 * (ohne erzwungenes AVIF), 3-Stufen-Referer-Fallback und HTML-Challenge-Erkennung.
 */
private class MangaHeaderAndChallengeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val imageUrlStr = original.url.toString()
        val refererHeader = original.header("Referer")

        val mergedCookies = resolveMergedCookies(imageUrlStr, refererHeader)
        val imageOrigin = "${original.url.scheme}://${original.url.host}/"

        val refererTiers = listOfNotNull(refererHeader, imageOrigin, null).distinct()
        var lastResponse: Response? = null

        for (tierReferer in refererTiers) {
            lastResponse?.close()

            val builder = original.newBuilder()
                .removeHeader("Origin") // Verhindert Ablehnungen strenger Bild-CDNs
                .header("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", computeSecFetchSite(imageUrlStr, tierReferer))

            if (tierReferer != null) {
                builder.header("Referer", tierReferer)
            } else {
                builder.removeHeader("Referer")
            }

            if (!mergedCookies.isNullOrBlank()) {
                builder.header("Cookie", mergedCookies)
            }

            val response = chain.proceed(builder.build())
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val peekPrefix = runCatching { response.peekBody(256).string().trimStart().lowercase() }
                .getOrDefault("")

            val isHtmlChallenge =
                contentType.contains("text/html") ||
                    peekPrefix.startsWith("<!doctype html") ||
                    peekPrefix.startsWith("<html")

            if (response.isSuccessful && !isHtmlChallenge && contentType.startsWith("image/")) {
                return response
            }

            if (response.code == 404 || response.code == 429) {
                return response
            }

            if (isHtmlChallenge) {
                response.close()
                lastResponse = Response.Builder()
                    .request(original)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("HTML-Sicherheitsabfrage erkannt")
                    .header("X-Manga-Diagnostic", "HTML_CHALLENGE")
                    .body("HTTP 403: HTML-Sicherheitsabfrage".toResponseBody(null))
                    .build()
                continue
            }

            lastResponse = response
            if (response.code != 403 && response.code != 401) {
                return response
            }
        }

        return lastResponse ?: chain.proceed(original)
    }

    private fun resolveMergedCookies(imageUrl: String, refererUrl: String?): String? {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val parts = linkedMapOf<String, String>()

        listOfNotNull(refererUrl, imageUrl).forEach { target ->
            cookieManager.getCookie(target)
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.contains("=") }
                ?.forEach { pair ->
                    val idx = pair.indexOf('=')
                    parts[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
                }
        }
        return if (parts.isEmpty()) null else parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun computeSecFetchSite(imageUrl: String, refererUrl: String?): String {
        if (refererUrl == null) return "none"
        return runCatching {
            val img = URI(imageUrl)
            val ref = URI(refererUrl)
            when {
                img.scheme == ref.scheme && img.host == ref.host && img.port == ref.port -> "same-origin"
                img.host?.split(".")?.takeLast(2) == ref.host?.split(".")?.takeLast(2) -> "same-site"
                else -> "cross-site"
            }
        }.getOrDefault("cross-site")
    }
}

/**
 * Speichert ausschließlich verifizierte `image/*`-Antworten im Cache
 * und triggert danach eine leichtgewichtige Speicherlimit-Prüfung (Standard: 4 GB).
 */
private class SafeImageCacheInterceptor(
    private val cacheManager: ChapterCacheManager,
    private val scope: CoroutineScope,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type").orEmpty().lowercase()

        if (!response.isSuccessful || !contentType.startsWith("image/") || contentType.contains("html")) {
            return response
        }

        cacheManager.scheduleThresholdCheck(scope)

        return response.newBuilder()
            .removeHeader("Pragma")
            .header("Cache-Control", "public, max-age=2592000, immutable")
            .build()
    }
}
