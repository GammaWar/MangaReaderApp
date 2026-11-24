package com.mangareader.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.local.LegacyStorageMigrator
import com.mangareader.app.data.local.MangaDatabase
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacyStorageMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: MangaDatabase
    private lateinit var dsScope: CoroutineScope
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = MangaDatabase.createInMemory(context)
        dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tempFolder.newFolder(), "migration_settings.preferences_pb") }
        )
        settingsDataStore = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        database.close()
        dsScope.cancel()
    }

    @Test
    fun `migrates legacy JSON files, disk downloads, and SharedPreferences cache limit once`() = runBlocking {
        val filesDir = tempFolder.newFolder("legacy_files")

        // 1. library_bookmarks.json (includes duplicate bookmark URL to verify deduplication)
        File(filesDir, LegacyStorageMigrator.LEGACY_LIBRARY_FILE).writeText(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", "bm-old")
                        put("title", "One Piece")
                        put("chapterTitle", "Ch. 1100")
                        put("chapterUrl", "https://example.org/op/1100")
                        put("lastReadPage", 3)
                        put("totalPages", 18)
                        put("timestamp", 1000L)
                        put("isBookmark", true)
                    }
                )
                put(
                    JSONObject().apply {
                        put("id", "bm-new")
                        put("title", "One Piece")
                        put("chapterTitle", "Ch. 1100")
                        put("chapterUrl", "https://example.org/op/1100")
                        put("lastReadPage", 12)
                        put("totalPages", 18)
                        put("timestamp", 5000L)
                        put("isBookmark", true)
                    }
                )
                put(
                    JSONObject().apply {
                        put("id", "hist-1")
                        put("title", "JJK")
                        put("chapterTitle", "Ch. 240")
                        put("chapterUrl", "https://example.org/jjk/240")
                        put("lastReadPage", 9)
                        put("totalPages", 20)
                        put("timestamp", 4000L)
                        put("isBookmark", false)
                    }
                )
            }.toString()
        )

        // 2. reader_preferences.json
        File(filesDir, LegacyStorageMigrator.LEGACY_PREFS_FILE).writeText(
            JSONObject().apply {
                put("isHistoryTrackingEnabled", false)
                put("isAutoScrollEnabled", true)
                put("autoScrollSpeed", 1.75)
                put("isPrefetchEnabled", false)
                put("isCustomBrightnessEnabled", true)
                put("brightnessLevel", 0.55)
                put("isNightFilterEnabled", true)
                put("nightFilterStrength", 0.45)
                put("readingDirection", ReadingDirection.LTR.name)
                put("keepScreenOn", false)
            }.toString()
        )

        // 3. tabs_session.json
        File(filesDir, LegacyStorageMigrator.LEGACY_SESSION_FILE).writeText(
            JSONObject().apply {
                put("activeTabId", "tab-b")
                put("readerMode", ReaderMode.HORIZONTAL_PAGED.name)
                put(
                    "tabs",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("id", "tab-a")
                                put("title", "MangaDex")
                                put("url", "https://mangadex.org")
                                put("lastReadPage", 0)
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("id", "tab-b")
                                put("title", "Tower of God")
                                put("url", "https://example.org/tog/500")
                                put("lastReadPage", 7)
                                put(
                                    "chapter",
                                    JSONObject().apply {
                                        put("title", "Episode 500")
                                        put("chapterUrl", "https://example.org/tog/500")
                                        put("nextChapterUrl", "https://example.org/tog/501")
                                        put("isOffline", false)
                                        put("images", JSONArray().put("https://cdn.example.org/1.jpg").put("https://cdn.example.org/2.jpg"))
                                    }
                                )
                            }
                        )
                    }
                )
            }.toString()
        )

        // 4. manga_downloads/dl_1/metadata.json
        val dlDir = File(filesDir, "manga_downloads/dl_1").apply { mkdirs() }
        val dlImg = File(dlDir, "page_001.jpg").apply { writeBytes(ByteArray(16)) }
        File(dlDir, "metadata.json").writeText(
            JSONObject().apply {
                put("id", "dl_1")
                put("title", "Saved Ch 1")
                put("sourceUrl", "https://example.org/saved/1")
                put("timestamp", 9000L)
                put("files", JSONArray().put(dlImg.absolutePath))
            }.toString()
        )

        // 5. Legacy 8 GB cache limit
        val legacyCacheLimitBytes = ChapterCacheManager.mbToBytes(8192)
        val migrator = LegacyStorageMigrator(
            filesDir = filesDir,
            database = database,
            settingsDataStore = settingsDataStore,
            readLegacyCacheLimitBytes = { legacyCacheLimitBytes }
        )

        val firstReport = migrator.migrateIfNeeded()
        assertTrue(firstReport.performed)
        assertEquals(1, firstReport.migratedBookmarks)
        assertEquals(1, firstReport.migratedHistory)
        assertEquals(3, firstReport.migratedProgress)
        assertEquals(2, firstReport.migratedTabs)
        assertEquals(1, firstReport.migratedDownloads)
        assertTrue(firstReport.migratedPreferences)
        assertTrue(firstReport.migratedCacheLimit)

        // Verify Room tables
        val bookmarks = database.bookmarkDao().observeBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals("bm-new", bookmarks[0].id)
        assertEquals(12, bookmarks[0].lastReadPage)

        val history = database.bookmarkDao().observeHistory().first()
        assertEquals(1, history.size)
        assertEquals("https://example.org/jjk/240", history[0].chapterUrl)

        val togProgress = database.readingProgressDao().getByChapterUrl("https://example.org/tog/500")
        assertNotNull(togProgress)
        assertEquals(7, togProgress!!.lastReadPage)

        val tabs = database.webTabDao().getAll()
        assertEquals(2, tabs.size)
        assertEquals("tab-a", tabs[0].id)
        assertEquals("tab-b", tabs[1].id)
        assertEquals(2, tabs[1].chapterImages?.size)

        val downloads = database.downloadedChapterDao().getAll()
        assertEquals(1, downloads.size)
        assertEquals("dl_1", downloads[0].id)

        // Verify DataStore settings
        val settings = settingsDataStore.getSettings()
        assertEquals(ReaderMode.HORIZONTAL_PAGED, settings.readerMode)
        assertEquals("tab-b", settings.activeWebTabId)
        assertEquals(8192, settings.cacheLimitMb)
        assertEquals(ReadingDirection.LTR, settings.readerPreferences.readingDirection)
        assertFalse(settings.readerPreferences.isHistoryTrackingEnabled)
        assertTrue(settings.readerPreferences.isCustomBrightnessEnabled)
        assertEquals(0.55f, settings.readerPreferences.brightnessLevel, 0.001f)
        assertTrue(settings.readerPreferences.isNightFilterEnabled)
        assertEquals(0.45f, settings.readerPreferences.nightFilterStrength, 0.001f)
        assertFalse(settings.readerPreferences.keepScreenOn)
        assertTrue(settings.legacyMigrationCompleted)

        // Second run must be a no-op (idempotent)
        settingsDataStore.updateCacheLimitMb(16384)
        val secondReport = migrator.migrateIfNeeded()
        assertFalse(secondReport.performed)
        assertEquals(16384, settingsDataStore.getSettings().cacheLimitMb)
    }

    @Test
    fun `survives corrupt legacy JSON without failing remaining migration steps`() = runBlocking {
        val filesDir = tempFolder.newFolder("corrupt_files")
        File(filesDir, LegacyStorageMigrator.LEGACY_LIBRARY_FILE).writeText("{not valid json[[")
        File(filesDir, LegacyStorageMigrator.LEGACY_SESSION_FILE).writeText("corrupted")

        val migrator = LegacyStorageMigrator(
            filesDir = filesDir,
            database = database,
            settingsDataStore = settingsDataStore,
            readLegacyCacheLimitBytes = { ChapterCacheManager.mbToBytes(2048) }
        )

        val report = migrator.migrateIfNeeded()
        assertTrue(report.performed)
        assertEquals(0, report.migratedBookmarks)
        assertEquals(0, report.migratedTabs)
        assertTrue(report.migratedCacheLimit)
        assertEquals(2048, settingsDataStore.getSettings().cacheLimitMb)
        assertTrue(settingsDataStore.isLegacyMigrationCompleted())
    }
}
