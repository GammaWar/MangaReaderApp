package com.mangareader.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mangareader.app.data.download.DownloadRepository
import com.mangareader.app.data.library.LibraryRepository
import com.mangareader.app.data.local.MangaDatabase
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.WebTab
import com.mangareader.app.data.session.SessionManager
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
import org.junit.Assert.assertNull
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
class MangaDatabaseAndRepositoryTest {

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
            produceFile = { File(tempFolder.newFolder(), "test_settings.preferences_pb") }
        )
        settingsDataStore = SettingsDataStore(dataStore)
    }

    @After
    fun tearDown() {
        database.close()
        dsScope.cancel()
    }

    @Test
    fun `LibraryRepository manages bookmarks, exact reading progress, and history reactively`() = runBlocking {
        val repo = LibraryRepository(
            bookmarkDao = database.bookmarkDao(),
            readingProgressDao = database.readingProgressDao(),
            settingsDataStore = settingsDataStore
        )

        val chapter = Chapter(
            title = "Chapter 12",
            chapterUrl = "https://example.org/manga/solo/chapter-12",
            images = List(24) { idx -> "https://cdn.example.org/p${idx + 1}.jpg" },
            nextChapterUrl = "https://example.org/manga/solo/chapter-13"
        )

        // 1. Add bookmark at page 5
        val added = repo.toggleBookmark(chapter, displayTitle = "Solo Series", currentPage = 5)
        assertTrue(added)

        val bookmarksAfterAdd = repo.bookmarksFlow.first()
        assertEquals(1, bookmarksAfterAdd.size)
        assertEquals("Solo Series", bookmarksAfterAdd[0].title)
        assertEquals(5, bookmarksAfterAdd[0].lastReadPage)
        assertEquals(24, bookmarksAfterAdd[0].totalPages)

        // 2. Advance reading progress to page 14 with history enabled
        repo.recordReadingProgress(chapter, displayTitle = "Solo Series", page = 14, trackHistory = true)

        val progress = repo.getReadingProgress(chapter.chapterUrl)
        assertNotNull(progress)
        assertEquals(14, progress!!.lastReadPage)
        assertEquals(24, progress.totalPages)

        val updatedBookmarks = repo.bookmarksFlow.first()
        assertEquals(14, updatedBookmarks[0].lastReadPage)

        val history = repo.historyFlow.first()
        assertEquals(1, history.size)
        assertEquals(14, history[0].lastReadPage)

        // 3. Advance with history disabled -> progress & bookmark update, new chapter not added to history
        val chapter13 = chapter.copy(
            title = "Chapter 13",
            chapterUrl = "https://example.org/manga/solo/chapter-13"
        )
        repo.recordReadingProgress(chapter13, displayTitle = "Solo Series", page = 3, trackHistory = false)
        assertEquals(3, repo.getReadingProgress(chapter13.chapterUrl)?.lastReadPage)
        assertEquals(1, repo.historyFlow.first().size)

        // 4. Toggle bookmark again removes it, clearHistory clears only history
        val stillBookmarked = repo.toggleBookmark(chapter, displayTitle = "Solo Series", currentPage = 14)
        assertFalse(stillBookmarked)
        assertTrue(repo.bookmarksFlow.first().isEmpty())

        repo.clearHistory()
        assertTrue(repo.historyFlow.first().isEmpty())
    }

    @Test
    fun `BookmarkDao trims history to configured maximum limit`() = runBlocking {
        val repo = LibraryRepository(
            bookmarkDao = database.bookmarkDao(),
            readingProgressDao = database.readingProgressDao(),
            settingsDataStore = settingsDataStore
        )

        for (i in 1..68) {
            val ch = Chapter(
                title = "Chapter $i",
                chapterUrl = "https://example.org/series/chapter-$i",
                images = listOf("https://cdn.example.org/$i.jpg")
            )
            repo.recordReadingProgress(ch, displayTitle = "Series", page = 0, trackHistory = true)
            // Small timestamp progression via explicit dao call not needed since timestamps are monotonic or equal,
            // verify cap <= MAX_HISTORY_ITEMS
        }

        val history = repo.historyFlow.first()
        assertEquals(LibraryRepository.MAX_HISTORY_ITEMS, history.size)
    }

    @Test
    fun `SessionManager persists and restores ordered WebTabs, chapters, active tab, and reader mode`() = runBlocking {
        val sessionManager = SessionManager(
            webTabDao = database.webTabDao(),
            settingsDataStore = settingsDataStore
        )

        val ch = Chapter(
            title = "Episode 7",
            chapterUrl = "https://example.org/webtoon/ep-7",
            images = listOf("https://cdn.example.org/1.webp", "https://cdn.example.org/2.webp"),
            nextChapterUrl = "https://example.org/webtoon/ep-8",
            prevChapterUrl = "https://example.org/webtoon/ep-6",
            isOffline = false
        )
        val tab1 = WebTab(id = "tab-1", title = "Home", url = "https://mangadex.org", chapter = null, lastReadPage = 0)
        val tab2 = WebTab(id = "tab-2", title = "Webtoon Ep 7", url = ch.chapterUrl, chapter = ch, lastReadPage = 1)

        sessionManager.saveSession(
            tabs = listOf(tab1, tab2),
            activeTabId = "tab-2",
            readerMode = ReaderMode.HORIZONTAL_PAGED
        )

        val restored = sessionManager.loadSession()
        assertNotNull(restored)
        assertEquals(2, restored!!.tabs.size)
        assertEquals("tab-1", restored.tabs[0].id)
        assertEquals("tab-2", restored.tabs[1].id)
        assertEquals("tab-2", restored.activeTabId)
        assertEquals(ReaderMode.HORIZONTAL_PAGED, restored.readerMode)
        assertEquals(1, restored.tabs[1].lastReadPage)
        assertEquals(ch, restored.tabs[1].chapter)

        // Update page directly and verify
        sessionManager.updateTabLastReadPage("tab-2", 0)
        assertEquals(0, sessionManager.loadSession()!!.tabs[1].lastReadPage)

        // Closing tab-1 replaces persisted tabs cleanly
        sessionManager.saveSession(
            tabs = listOf(tab2),
            activeTabId = "tab-2",
            readerMode = ReaderMode.VERTICAL_WEBTOON
        )
        val afterClose = sessionManager.loadSession()!!
        assertEquals(1, afterClose.tabs.size)
        assertEquals("tab-2", afterClose.tabs[0].id)
        assertEquals(ReaderMode.VERTICAL_WEBTOON, afterClose.readerMode)
    }

    @Test
    fun `DownloadRepository syncs disk metadata with Room and deletes both cleanly`() = runBlocking {
        val downloadsBase = File(context.filesDir, "manga_downloads").apply {
            deleteRecursively()
            mkdirs()
        }
        val chDir = File(downloadsBase, "test_chap_01").apply { mkdirs() }
        val page1 = File(chDir, "page_001.jpg").apply { writeBytes(ByteArray(32)) }
        val metaFile = File(chDir, "metadata.json")
        metaFile.writeText(
            JSONObject().apply {
                put("id", "test_chap_01")
                put("title", "Offline Kapitel 1")
                put("sourceUrl", "https://example.org/manga/ch-1")
                put("timestamp", 1_700_000_000_000L)
                put("files", JSONArray().put(page1.absolutePath))
            }.toString()
        )

        val downloadRepo = DownloadRepository(context, database.downloadedChapterDao())

        val synced = downloadRepo.syncWithDisk()
        assertEquals(1, synced.size)
        assertEquals("test_chap_01", synced[0].id)
        assertEquals("Offline Kapitel 1", synced[0].title)

        val fromFlow = downloadRepo.downloadedChaptersFlow.first()
        assertEquals(1, fromFlow.size)
        assertEquals(listOf(page1.absolutePath), fromFlow[0].imageFilePaths)

        val deleted = downloadRepo.deleteChapter("test_chap_01")
        assertTrue(deleted)
        assertFalse(chDir.exists())
        assertTrue(downloadRepo.downloadedChaptersFlow.first().isEmpty())
        assertNull(database.downloadedChapterDao().getById("test_chap_01"))
    }
}
