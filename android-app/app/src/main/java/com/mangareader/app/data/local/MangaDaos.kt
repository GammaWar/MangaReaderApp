package com.mangareader.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE isBookmark = 1 ORDER BY timestamp DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE isBookmark = 0 ORDER BY timestamp DESC")
    fun observeHistory(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE chapterUrl = :chapterUrl AND isBookmark = 1 LIMIT 1")
    suspend fun getBookmarkByUrl(chapterUrl: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE chapterUrl = :chapterUrl AND isBookmark = 0 LIMIT 1")
    suspend fun getHistoryByUrl(chapterUrl: String): BookmarkEntity?

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE chapterUrl = :chapterUrl AND isBookmark = 1")
    suspend fun deleteBookmarkByUrl(chapterUrl: String)

    @Query("DELETE FROM bookmarks WHERE isBookmark = 0")
    suspend fun clearHistory()

    @Query(
        """
        UPDATE bookmarks
        SET lastReadPage = :lastReadPage,
            totalPages = CASE WHEN :totalPages > 0 THEN :totalPages ELSE totalPages END,
            timestamp = :timestamp
        WHERE chapterUrl = :chapterUrl AND isBookmark = 1
        """
    )
    suspend fun updateBookmarkProgress(
        chapterUrl: String,
        lastReadPage: Int,
        totalPages: Int,
        timestamp: Long
    )

    @Query(
        """
        DELETE FROM bookmarks
        WHERE isBookmark = 0 AND id NOT IN (
            SELECT id FROM bookmarks WHERE isBookmark = 0 ORDER BY timestamp DESC LIMIT :maxEntries
        )
        """
    )
    suspend fun trimHistory(maxEntries: Int = 60)

    @Transaction
    suspend fun upsertHistoryAndTrim(entity: BookmarkEntity, maxEntries: Int = 60) {
        val existing = getHistoryByUrl(entity.chapterUrl)
        val merged = if (existing != null) entity.copy(id = existing.id, isBookmark = false) else entity.copy(isBookmark = false)
        upsert(merged)
        trimHistory(maxEntries)
    }
}

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE chapterUrl = :chapterUrl LIMIT 1")
    fun observeByChapterUrl(chapterUrl: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE chapterUrl = :chapterUrl LIMIT 1")
    suspend fun getByChapterUrl(chapterUrl: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<ReadingProgressEntity>)

    @Query("DELETE FROM reading_progress WHERE chapterUrl = :chapterUrl")
    suspend fun deleteByChapterUrl(chapterUrl: String)

    @Query("DELETE FROM reading_progress")
    suspend fun clearAll()
}

@Dao
interface DownloadedChapterDao {

    @Query("SELECT * FROM downloaded_chapters ORDER BY downloadTimestamp DESC")
    fun observeAll(): Flow<List<DownloadedChapterEntity>>

    @Query("SELECT * FROM downloaded_chapters ORDER BY downloadTimestamp DESC")
    suspend fun getAll(): List<DownloadedChapterEntity>

    @Query("SELECT * FROM downloaded_chapters WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadedChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadedChapterEntity>)

    @Query("DELETE FROM downloaded_chapters WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloaded_chapters WHERE id NOT IN (:validIds)")
    suspend fun deleteNotIn(validIds: List<String>)

    @Query("DELETE FROM downloaded_chapters")
    suspend fun clearAll()

    @Transaction
    suspend fun syncAll(diskEntities: List<DownloadedChapterEntity>) {
        if (diskEntities.isEmpty()) {
            clearAll()
        } else {
            upsertAll(diskEntities)
            deleteNotIn(diskEntities.map { it.id })
        }
    }
}

@Dao
interface WebTabDao {

    @Query("SELECT * FROM web_tabs ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<WebTabEntity>>

    @Query("SELECT * FROM web_tabs ORDER BY sortOrder ASC")
    suspend fun getAll(): List<WebTabEntity>

    @Query("SELECT COUNT(*) FROM web_tabs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WebTabEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<WebTabEntity>)

    @Query("DELETE FROM web_tabs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM web_tabs WHERE id NOT IN (:activeIds)")
    suspend fun deleteNotIn(activeIds: List<String>)

    @Query("DELETE FROM web_tabs")
    suspend fun clearAll()

    @Query("UPDATE web_tabs SET lastReadPage = :lastReadPage, updatedAt = :updatedAt WHERE id = :tabId")
    suspend fun updateLastReadPage(tabId: String, lastReadPage: Int, updatedAt: Long)

    @Transaction
    suspend fun replaceAll(entities: List<WebTabEntity>) {
        if (entities.isEmpty()) {
            clearAll()
        } else {
            upsertAll(entities)
            deleteNotIn(entities.map { it.id })
        }
    }
}
