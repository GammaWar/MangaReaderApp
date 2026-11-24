package com.mangareader.app.data.library

import android.content.Context
import com.mangareader.app.data.local.BookmarkDao
import com.mangareader.app.data.local.BookmarkEntity
import com.mangareader.app.data.local.MangaDatabase
import com.mangareader.app.data.local.ReadingProgressDao
import com.mangareader.app.data.local.ReadingProgressEntity
import com.mangareader.app.data.model.BookmarkItem
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Reaktives Bibliotheks-Repository auf Basis von Room (`BookmarkDao`, `ReadingProgressDao`)
 * und Jetpack DataStore (`SettingsDataStore`) — vollständig asynchron ohne Main-Thread-I/O.
 */
class LibraryRepository(
    private val bookmarkDao: BookmarkDao,
    private val readingProgressDao: ReadingProgressDao,
    private val settingsDataStore: SettingsDataStore
) {
    constructor(
        context: Context,
        database: MangaDatabase = MangaDatabase.getInstance(context),
        settingsDataStore: SettingsDataStore = SettingsDataStore(context)
    ) : this(
        bookmarkDao = database.bookmarkDao(),
        readingProgressDao = database.readingProgressDao(),
        settingsDataStore = settingsDataStore
    )

    val bookmarksFlow: Flow<List<BookmarkItem>> = combine(
        bookmarkDao.observeBookmarks(),
        readingProgressDao.observeAll()
    ) { entities, progressList ->
        val progressByUrl = progressList.associateBy { it.chapterUrl }
        entities.map { entity -> enrichWithProgress(entity.toDomain(), progressByUrl[entity.chapterUrl]) }
    }

    val historyFlow: Flow<List<BookmarkItem>> = combine(
        bookmarkDao.observeHistory(),
        readingProgressDao.observeAll()
    ) { entities, progressList ->
        val progressByUrl = progressList.associateBy { it.chapterUrl }
        entities.map { entity -> enrichWithProgress(entity.toDomain(), progressByUrl[entity.chapterUrl]) }
    }

    val readingProgressFlow: Flow<Map<String, ReadingProgressEntity>> =
        readingProgressDao.observeAll().map { list -> list.associateBy { it.chapterUrl } }

    val readerPreferencesFlow: Flow<ReaderPreferences> = settingsDataStore.readerPreferencesFlow

    /**
     * Schaltet ein Kapitel-Lesezeichen um und gibt `true` zurück, wenn es hinzugefügt wurde,
     * bzw. `false`, wenn ein vorhandenes Lesezeichen entfernt wurde.
     */
    suspend fun toggleBookmark(
        chapter: Chapter,
        displayTitle: String,
        currentPage: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val url = chapter.chapterUrl
        val existing = bookmarkDao.getBookmarkByUrl(url)
        if (existing != null) {
            bookmarkDao.deleteById(existing.id)
            false
        } else {
            val now = System.currentTimeMillis()
            val safePage = currentPage.coerceAtLeast(0)
            val safeTitle = displayTitle.ifBlank { chapter.title }
            val entity = BookmarkEntity(
                id = UUID.randomUUID().toString(),
                title = safeTitle,
                chapterTitle = chapter.title,
                chapterUrl = url,
                coverImageUrl = chapter.images.firstOrNull(),
                lastReadPage = safePage,
                totalPages = chapter.images.size,
                timestamp = now,
                isBookmark = true
            )
            bookmarkDao.upsert(entity)
            if (url.isNotBlank()) {
                readingProgressDao.upsert(
                    ReadingProgressEntity(
                        chapterUrl = url,
                        seriesTitle = safeTitle,
                        chapterTitle = chapter.title,
                        lastReadPage = safePage,
                        totalPages = chapter.images.size,
                        updatedAt = now
                    )
                )
            }
            true
        }
    }

    /**
     * Persistiert die exakte Leseposition in `reading_progress`, hält vorhandene Lesezeichen
     * synchron und schreibt bei aktiviertem Verlauf den chronologischen Verlaufseintrag.
     */
    suspend fun recordReadingProgress(
        chapter: Chapter,
        displayTitle: String,
        page: Int,
        trackHistory: Boolean
    ) = withContext(Dispatchers.IO) {
        val url = chapter.chapterUrl.ifBlank { chapter.title }
        if (url.isBlank()) return@withContext

        val now = System.currentTimeMillis()
        val safePage = page.coerceAtLeast(0)
        val totalPages = chapter.images.size
        val safeTitle = displayTitle.ifBlank { chapter.title }

        readingProgressDao.upsert(
            ReadingProgressEntity(
                chapterUrl = url,
                seriesTitle = safeTitle,
                chapterTitle = chapter.title,
                lastReadPage = safePage,
                totalPages = totalPages,
                updatedAt = now
            )
        )

        bookmarkDao.updateBookmarkProgress(
            chapterUrl = url,
            lastReadPage = safePage,
            totalPages = totalPages,
            timestamp = now
        )

        if (trackHistory) {
            bookmarkDao.upsertHistoryAndTrim(
                entity = BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    title = safeTitle,
                    chapterTitle = chapter.title,
                    chapterUrl = url,
                    coverImageUrl = chapter.images.firstOrNull(),
                    lastReadPage = safePage,
                    totalPages = totalPages,
                    timestamp = now,
                    isBookmark = false
                ),
                maxEntries = MAX_HISTORY_ITEMS
            )
        }
    }

    suspend fun getReadingProgress(chapterUrl: String): ReadingProgressEntity? =
        withContext(Dispatchers.IO) {
            if (chapterUrl.isBlank()) return@withContext null
            readingProgressDao.getByChapterUrl(chapterUrl)
        }

    suspend fun deleteItem(id: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        bookmarkDao.clearHistory()
    }

    suspend fun savePreferences(prefs: ReaderPreferences) {
        settingsDataStore.updateReaderPreferences(prefs)
    }

    private fun enrichWithProgress(
        item: BookmarkItem,
        progress: ReadingProgressEntity?
    ): BookmarkItem {
        if (progress == null || progress.updatedAt < item.timestamp) return item
        return item.copy(
            lastReadPage = progress.lastReadPage,
            totalPages = if (progress.totalPages > 0) progress.totalPages else item.totalPages,
            timestamp = maxOf(item.timestamp, progress.updatedAt)
        )
    }

    companion object {
        const val MAX_HISTORY_ITEMS = 60
    }
}
