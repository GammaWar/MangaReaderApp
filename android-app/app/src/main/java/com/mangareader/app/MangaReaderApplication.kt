package com.mangareader.app

import android.app.Application
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mangareader.app.data.cache.CachePruneReport
import com.mangareader.app.data.cache.CacheStats
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.local.LegacyMigrationReport
import com.mangareader.app.data.local.LegacyStorageMigrator
import com.mangareader.app.data.local.MangaDatabase
import com.mangareader.app.data.network.GuardedBitmapDecoder
import com.mangareader.app.data.network.MangaNetworkClients
import com.mangareader.app.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Zentrale Application-Klasse mit:
 * - Room-Datenbank ([database]) & Jetpack DataStore ([settingsDataStore]) ohne Main-Thread-I/O
 * - Nahtloser Einmal-Migration ([legacyStorageMigrator]) alter JSON-/SharedPreferences-Daten
 * - Konfigurierbarem [ChapterCacheManager] (Standardlimit 4 GB, Bereich 512 MB – 32 GB,
 *   reaktiv an DataStore angebunden, ohne automatische 31-Tage-Löschung)
 * - Strikt getrennten nativen OkHttp-Clients ([imageHttpClient] & [chapterHttpClient])
 */
class MangaReaderApplication : Application(), ImageLoaderFactory {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: MangaDatabase by lazy { MangaDatabase.getInstance(this) }

    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }

    val legacyStorageMigrator: LegacyStorageMigrator by lazy {
        LegacyStorageMigrator(
            context = this,
            database = database,
            settingsDataStore = settingsDataStore
        )
    }

    lateinit var cacheManager: ChapterCacheManager
        private set

    lateinit var imageHttpClient: OkHttpClient
        private set

    lateinit var chapterHttpClient: OkHttpClient
        private set

    @Deprecated(
        message = "Explizit imageHttpClient für Bilder oder chapterHttpClient für HTML-Kapitelseiten verwenden.",
        replaceWith = ReplaceWith("imageHttpClient")
    )
    val okHttpClient: OkHttpClient
        get() = imageHttpClient

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

        // Kein synchrones SharedPreferences-/Disk-I/O auf dem Main-Thread:
        // Initial mit Default 4 GB starten und anschließend reaktiv aus DataStore übernehmen.
        cacheManager = ChapterCacheManager(
            cacheRootDir = cacheDir,
            managedDirectories = listOf(coilCacheDir, httpCacheDir, chapterCacheDir, legacyCoilCacheDir),
            initialMaxSizeBytes = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES
        )

        imageHttpClient = MangaNetworkClients.buildImageHttpClient(
            httpCacheDir = httpCacheDir,
            onImageCached = { cacheManager.scheduleThresholdCheck(applicationScope) }
        )
        chapterHttpClient = MangaNetworkClients.buildChapterHttpClient()

        // 1. Einmal-Migration (inkl. Alt-Cache-Limit aus SharedPreferences)
        // 2. DataStore-Cache-Limit an ChapterCacheManager anbinden & Startup-Prüfung ausführen
        // 3. Periodische 6-Stunden-Wartung starten (ohne automatische 31-Tage-Löschung)
        applicationScope.launch {
            val migrationReport = runCatching { ensureLegacyMigrationCompleted() }.getOrNull()
            if (migrationReport?.performed == true) {
                Log.i(
                    TAG,
                    "Legacy-Migration abgeschlossen: ${migrationReport.migratedBookmarks} Lesezeichen, " +
                        "${migrationReport.migratedHistory} Verlauf, ${migrationReport.migratedTabs} Tabs, " +
                        "${migrationReport.migratedDownloads} Downloads."
                )
            }

            val persistedLimitBytes = runCatching { settingsDataStore.getCacheLimitBytes() }
                .getOrDefault(ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES)
            cacheManager.updateMaxSizeBytes(persistedLimitBytes)

            val startupReport = cacheManager.pruneIfNeeded(forceCheck = true)
            Log.i(
                TAG,
                "Startup-Cache-Prüfung (${ChapterCacheManager.formatCacheLimitLabel(ChapterCacheManager.bytesToMb(persistedLimitBytes))}): " +
                    "${startupReport.removedSizeEvictedCount} Einträge wegen Speicherlimit entfernt, " +
                    "${startupReport.freedBytes / (1024 * 1024)} MB freigegeben."
            )

            cacheManager.bindToLimitFlow(
                scope = applicationScope,
                limitBytesFlow = settingsDataStore.cacheLimitBytesFlow,
                pruneOnChange = false
            )
            cacheManager.startPeriodicMaintenance(applicationScope)
        }
    }

    suspend fun ensureLegacyMigrationCompleted(): LegacyMigrationReport =
        legacyStorageMigrator.migrateIfNeeded()

    override fun newImageLoader(): ImageLoader {
        val dir = if (::coilCacheDir.isInitialized) coilCacheDir else File(cacheDir, COIL_CACHE_DIR_NAME)
        val client = if (::imageHttpClient.isInitialized) {
            imageHttpClient
        } else {
            MangaNetworkClients.buildImageHttpClient(
                httpCacheDir = File(cacheDir, HTTP_CACHE_DIR_NAME)
            )
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
        val clamped = settingsDataStore.updateCacheLimitBytes(newMaxBytes)
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
        runCatching { imageHttpClient.cache?.evictAll() }
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
            imageHttpClient.cache?.urls()?.let { iter ->
                while (iter.hasNext()) {
                    if (iter.next() == imageUrl) {
                        iter.remove()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MangaReaderApp"
        private const val COIL_CACHE_DIR_NAME = "image_cache"
        private const val HTTP_CACHE_DIR_NAME = "http_cache"
        private const val CHAPTER_CACHE_DIR_NAME = "chapter_cache"
        private const val LEGACY_COIL_CACHE_DIR_NAME = "manga_image_cache"

        const val HEADER_BYPASS_CACHE = "X-Manga-Bypass-Cache"
        const val PARAM_IMAGE_URL = "manga_image_url"
    }
}
