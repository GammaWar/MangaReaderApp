package com.mangareader.app.data.local

import android.content.Context
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.download.DownloadRepository
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.data.model.WebTab
import com.mangareader.app.data.settings.SettingsDataStore
import com.mangareader.app.ui.screens.reader.AutoScrollLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LegacyMigrationReport(
    val performed: Boolean,
    val migratedBookmarks: Int = 0,
    val migratedHistory: Int = 0,
    val migratedProgress: Int = 0,
    val migratedTabs: Int = 0,
    val migratedDownloads: Int = 0,
    val migratedPreferences: Boolean = false,
    val migratedCacheLimit: Boolean = false
)

/**
 * Einmalige, idempotente Migration der Alt-Dateien:
 * - `library_bookmarks.json` -> `BookmarkEntity` + `ReadingProgressEntity`
 * - `reader_preferences.json` -> `SettingsDataStore`
 * - `tabs_session.json` -> `WebTabEntity` + `ReadingProgressEntity` + `SettingsDataStore`
 * - `SharedPreferences("mangareader_cache_prefs")` -> `SettingsDataStore`
 * - `manga_downloads/<id>/metadata.json` -> `DownloadedChapterEntity`
 *
 * Läuft ausschließlich auf `Dispatchers.IO` und niemals auf dem Main-Thread.
 */
class LegacyStorageMigrator(
    private val filesDir: File,
    private val database: MangaDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val readLegacyCacheLimitBytes: () -> Long?
) {
    constructor(
        context: Context,
        database: MangaDatabase,
        settingsDataStore: SettingsDataStore
    ) : this(
        filesDir = context.applicationContext.filesDir,
        database = database,
        settingsDataStore = settingsDataStore,
        readLegacyCacheLimitBytes = { readLegacyCacheLimitFromPrefs(context.applicationContext) }
    )

    private val migrationMutex = Mutex()

    suspend fun migrateIfNeeded(): LegacyMigrationReport = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            if (settingsDataStore.isLegacyMigrationCompleted()) {
                return@withLock LegacyMigrationReport(performed = false)
            }

            val progressMap = linkedMapOf<String, ReadingProgressEntity>()

            // 1. library_bookmarks.json -> bookmarks & reading_progress
            val legacyLibraryItems = parseLegacyLibraryFile(File(filesDir, LEGACY_LIBRARY_FILE))
            val bookmarkEntities = deduplicateByUrlAndKind(legacyLibraryItems)
            var migratedBookmarks = 0
            var migratedHistory = 0
            if (bookmarkEntities.isNotEmpty() && database.bookmarkDao().countAll() == 0) {
                database.bookmarkDao().insertAllIgnore(bookmarkEntities)
                migratedBookmarks = bookmarkEntities.count { it.isBookmark }
                migratedHistory = bookmarkEntities.count { !it.isBookmark }
            }
            bookmarkEntities.forEach { entity ->
                if (entity.chapterUrl.isNotBlank()) {
                    mergeProgressCandidate(
                        target = progressMap,
                        candidate = ReadingProgressEntity(
                            chapterUrl = entity.chapterUrl,
                            seriesTitle = entity.title.ifBlank { entity.chapterTitle },
                            chapterTitle = entity.chapterTitle,
                            lastReadPage = entity.lastReadPage.coerceAtLeast(0),
                            totalPages = entity.totalPages.coerceAtLeast(0),
                            updatedAt = entity.timestamp
                        )
                    )
                }
            }

            // 2. reader_preferences.json -> SettingsDataStore
            val legacyPreferences = parseLegacyPreferencesFile(File(filesDir, LEGACY_PREFS_FILE))

            // 3. tabs_session.json -> web_tabs, reading_progress, readerMode, activeTabId
            val legacySession = parseLegacySessionFile(File(filesDir, LEGACY_SESSION_FILE))
            var migratedTabs = 0
            if (legacySession != null && legacySession.tabs.isNotEmpty() && database.webTabDao().count() == 0) {
                val now = System.currentTimeMillis()
                val tabEntities = legacySession.tabs.mapIndexed { index, tab ->
                    WebTabEntity.fromDomain(tab = tab, sortOrder = index, updatedAt = now)
                }
                database.webTabDao().insertAllIgnore(tabEntities)
                migratedTabs = tabEntities.size
            }
            legacySession?.tabs?.forEach { tab ->
                val ch = tab.chapter
                if (ch != null && ch.chapterUrl.isNotBlank()) {
                    mergeProgressCandidate(
                        target = progressMap,
                        candidate = ReadingProgressEntity(
                            chapterUrl = ch.chapterUrl,
                            seriesTitle = tab.title.ifBlank { ch.title },
                            chapterTitle = ch.title,
                            lastReadPage = tab.lastReadPage.coerceAtLeast(0),
                            totalPages = ch.images.size,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            // 4. Persist merged reading_progress entries
            val progressList = progressMap.values.toList()
            if (progressList.isNotEmpty()) {
                database.readingProgressDao().insertAllIgnore(progressList)
            }

            // 5. Existing offline downloads -> downloaded_chapters
            val diskDownloads = DownloadRepository.scanDiskDownloads(filesDir)
            if (diskDownloads.isNotEmpty()) {
                database.downloadedChapterDao().upsertAll(diskDownloads)
            }

            // 6. Legacy SharedPreferences cache limit -> SettingsDataStore
            val legacyCacheLimit = runCatching { readLegacyCacheLimitBytes() }.getOrNull()
                ?.let { ChapterCacheManager.clampCacheLimitBytes(it) }

            settingsDataStore.applyLegacyMigration(
                readerMode = legacySession?.readerMode,
                readerPreferences = legacyPreferences,
                cacheLimitBytes = legacyCacheLimit,
                activeWebTabId = legacySession?.activeTabId
            )

            LegacyMigrationReport(
                performed = true,
                migratedBookmarks = migratedBookmarks,
                migratedHistory = migratedHistory,
                migratedProgress = progressList.size,
                migratedTabs = migratedTabs,
                migratedDownloads = diskDownloads.size,
                migratedPreferences = legacyPreferences != null,
                migratedCacheLimit = legacyCacheLimit != null
            )
        }
    }

    private fun mergeProgressCandidate(
        target: MutableMap<String, ReadingProgressEntity>,
        candidate: ReadingProgressEntity
    ) {
        val existing = target[candidate.chapterUrl]
        if (existing == null || candidate.updatedAt > existing.updatedAt) {
            target[candidate.chapterUrl] = candidate
        }
    }

    private fun deduplicateByUrlAndKind(items: List<BookmarkEntity>): List<BookmarkEntity> {
        val unique = linkedMapOf<Pair<String, Boolean>, BookmarkEntity>()
        for (item in items) {
            if (item.chapterUrl.isBlank()) continue
            val key = item.chapterUrl to item.isBookmark
            val existing = unique[key]
            if (existing == null || item.timestamp > existing.timestamp) {
                unique[key] = item
            }
        }
        return unique.values.toList()
    }

    private fun parseLegacyLibraryFile(file: File): List<BookmarkEntity> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            val result = ArrayList<BookmarkEntity>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val chapterUrl = obj.optString("chapterUrl", "").trim()
                if (chapterUrl.isBlank()) continue
                result.add(
                    BookmarkEntity(
                        id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                        title = obj.optString("title", ""),
                        chapterTitle = obj.optString("chapterTitle", ""),
                        chapterUrl = chapterUrl,
                        coverImageUrl = obj.optString("coverImageUrl")
                            .takeIf { it.isNotBlank() && it != "null" },
                        lastReadPage = obj.optInt("lastReadPage", 0).coerceAtLeast(0),
                        totalPages = obj.optInt("totalPages", 0).coerceAtLeast(0),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isBookmark = obj.optBoolean("isBookmark", true)
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun parseLegacyPreferencesFile(file: File): ReaderPreferences? {
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return null
            val obj = JSONObject(text)
            val directionName = obj.optString("readingDirection", ReadingDirection.RTL.name)
            val direction = runCatching { ReadingDirection.valueOf(directionName) }
                .getOrDefault(ReadingDirection.RTL)

            ReaderPreferences(
                isHistoryTrackingEnabled = obj.optBoolean("isHistoryTrackingEnabled", true),
                isAutoScrollEnabled = obj.optBoolean("isAutoScrollEnabled", true),
                autoScrollSpeed = AutoScrollLogic.clampSpeed(obj.optDouble("autoScrollSpeed", 1.0).toFloat()),
                isPrefetchEnabled = obj.optBoolean("isPrefetchEnabled", true),
                isCustomBrightnessEnabled = obj.optBoolean("isCustomBrightnessEnabled", false),
                brightnessLevel = obj.optDouble("brightnessLevel", 1.0).toFloat().coerceIn(0.20f, 1.0f),
                isNightFilterEnabled = obj.optBoolean("isNightFilterEnabled", false),
                nightFilterStrength = obj.optDouble("nightFilterStrength", 0.35).toFloat().coerceIn(0.10f, 0.80f),
                readingDirection = direction,
                keepScreenOn = obj.optBoolean("keepScreenOn", true)
            )
        }.getOrNull()
    }

    private data class LegacySessionPayload(
        val tabs: List<WebTab>,
        val activeTabId: String?,
        val readerMode: ReaderMode
    )

    private fun parseLegacySessionFile(file: File): LegacySessionPayload? {
        if (!file.exists()) return null
        return runCatching {
            val content = file.readText()
            if (content.isBlank()) return null
            val root = JSONObject(content)
            val activeTabId = root.optString("activeTabId", "").takeIf { it.isNotBlank() }
            val readerModeStr = root.optString("readerMode", ReaderMode.VERTICAL_WEBTOON.name)
            val readerMode = runCatching { ReaderMode.valueOf(readerModeStr) }
                .getOrDefault(ReaderMode.VERTICAL_WEBTOON)

            val tabsArray = root.optJSONArray("tabs") ?: return null
            val tabsList = ArrayList<WebTab>(tabsArray.length())

            for (i in 0 until tabsArray.length()) {
                val tabObj = tabsArray.optJSONObject(i) ?: continue
                val id = tabObj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                val title = tabObj.optString("title", "Manga")
                val url = tabObj.optString("url", "https://mangadex.org")
                val lastReadPage = tabObj.optInt("lastReadPage", 0).coerceAtLeast(0)

                var chapter: Chapter? = null
                if (tabObj.has("chapter") && !tabObj.isNull("chapter")) {
                    val chObj = tabObj.optJSONObject("chapter")
                    if (chObj != null) {
                        val chTitle = chObj.optString("title", "")
                        val chUrl = chObj.optString("chapterUrl", "")
                        val nextUrl = chObj.optString("nextChapterUrl")
                            .takeIf { it.isNotBlank() && it != "null" }
                        val prevUrl = chObj.optString("prevChapterUrl")
                            .takeIf { it.isNotBlank() && it != "null" }
                        val isOffline = chObj.optBoolean("isOffline", false)

                        val imgArr = chObj.optJSONArray("images") ?: JSONArray()
                        val images = ArrayList<String>(imgArr.length())
                        for (j in 0 until imgArr.length()) {
                            val img = imgArr.optString(j, "")
                            if (img.isNotBlank()) images.add(img)
                        }

                        if (images.isNotEmpty()) {
                            chapter = Chapter(
                                title = chTitle,
                                chapterUrl = chUrl,
                                images = images,
                                nextChapterUrl = nextUrl,
                                prevChapterUrl = prevUrl,
                                isOffline = isOffline
                            )
                        }
                    }
                }

                tabsList.add(
                    WebTab(
                        id = id,
                        title = title,
                        url = url,
                        chapter = chapter,
                        lastReadPage = lastReadPage
                    )
                )
            }

            if (tabsList.isEmpty()) return null
            val resolvedActiveId = if (activeTabId != null && tabsList.any { it.id == activeTabId }) {
                activeTabId
            } else {
                tabsList.first().id
            }

            LegacySessionPayload(
                tabs = tabsList,
                activeTabId = resolvedActiveId,
                readerMode = readerMode
            )
        }.getOrNull()
    }

    companion object {
        const val LEGACY_LIBRARY_FILE = "library_bookmarks.json"
        const val LEGACY_PREFS_FILE = "reader_preferences.json"
        const val LEGACY_SESSION_FILE = "tabs_session.json"
        const val LEGACY_CACHE_PREFS_NAME = "mangareader_cache_prefs"
        const val LEGACY_KEY_CACHE_LIMIT_BYTES = "cache_limit_bytes"

        fun readLegacyCacheLimitFromPrefs(appContext: Context): Long? = runCatching {
            val prefs = appContext.getSharedPreferences(
                LEGACY_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            if (prefs.contains(LEGACY_KEY_CACHE_LIMIT_BYTES)) {
                prefs.getLong(
                    LEGACY_KEY_CACHE_LIMIT_BYTES,
                    ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES
                )
            } else {
                null
            }
        }.getOrNull()
    }
}
