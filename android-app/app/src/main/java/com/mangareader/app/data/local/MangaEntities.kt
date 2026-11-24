package com.mangareader.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mangareader.app.data.model.BookmarkItem
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.DownloadedChapter
import com.mangareader.app.data.model.WebTab

@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["chapterUrl", "isBookmark"], unique = true),
        Index(value = ["isBookmark", "timestamp"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val coverImageUrl: String? = null,
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isBookmark: Boolean = true
) {
    fun toDomain(): BookmarkItem = BookmarkItem(
        id = id,
        title = title,
        chapterTitle = chapterTitle,
        chapterUrl = chapterUrl,
        coverImageUrl = coverImageUrl,
        lastReadPage = lastReadPage,
        totalPages = totalPages,
        timestamp = timestamp,
        isBookmark = isBookmark
    )

    companion object {
        fun fromDomain(item: BookmarkItem): BookmarkEntity = BookmarkEntity(
            id = item.id,
            title = item.title,
            chapterTitle = item.chapterTitle,
            chapterUrl = item.chapterUrl,
            coverImageUrl = item.coverImageUrl,
            lastReadPage = item.lastReadPage.coerceAtLeast(0),
            totalPages = item.totalPages.coerceAtLeast(0),
            timestamp = item.timestamp,
            isBookmark = item.isBookmark
        )
    }
}

@Entity(
    tableName = "reading_progress",
    indices = [
        Index(value = ["updatedAt"])
    ]
)
data class ReadingProgressEntity(
    @PrimaryKey val chapterUrl: String,
    val seriesTitle: String,
    val chapterTitle: String,
    val lastReadPage: Int,
    val totalPages: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloaded_chapters",
    indices = [
        Index(value = ["sourceUrl"]),
        Index(value = ["downloadTimestamp"])
    ]
)
data class DownloadedChapterEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceUrl: String,
    val imageFilePaths: List<String>,
    val downloadTimestamp: Long = System.currentTimeMillis(),
    val totalPages: Int = imageFilePaths.size
) {
    fun toDomain(): DownloadedChapter = DownloadedChapter(
        id = id,
        title = title,
        sourceUrl = sourceUrl,
        imageFilePaths = imageFilePaths,
        downloadTimestamp = downloadTimestamp,
        totalPages = totalPages.coerceAtLeast(imageFilePaths.size)
    )

    companion object {
        fun fromDomain(chapter: DownloadedChapter): DownloadedChapterEntity = DownloadedChapterEntity(
            id = chapter.id,
            title = chapter.title,
            sourceUrl = chapter.sourceUrl,
            imageFilePaths = chapter.imageFilePaths,
            downloadTimestamp = chapter.downloadTimestamp,
            totalPages = chapter.totalPages.coerceAtLeast(chapter.imageFilePaths.size)
        )
    }
}

@Entity(
    tableName = "web_tabs",
    indices = [
        Index(value = ["sortOrder"])
    ]
)
data class WebTabEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val title: String,
    val url: String,
    val lastReadPage: Int = 0,
    val chapterTitle: String? = null,
    val chapterUrl: String? = null,
    val chapterImages: List<String>? = null,
    val chapterNextUrl: String? = null,
    val chapterPrevUrl: String? = null,
    val chapterIsOffline: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): WebTab {
        val images = chapterImages.orEmpty()
        val restoredChapter = if (!chapterUrl.isNullOrBlank() && images.isNotEmpty()) {
            Chapter(
                title = chapterTitle.orEmpty(),
                chapterUrl = chapterUrl,
                images = images,
                nextChapterUrl = chapterNextUrl,
                prevChapterUrl = chapterPrevUrl,
                isOffline = chapterIsOffline
            )
        } else {
            null
        }
        return WebTab(
            id = id,
            title = title,
            url = url,
            chapter = restoredChapter,
            lastReadPage = lastReadPage.coerceAtLeast(0)
        )
    }

    companion object {
        fun fromDomain(tab: WebTab, sortOrder: Int, updatedAt: Long = System.currentTimeMillis()): WebTabEntity {
            val ch = tab.chapter
            return WebTabEntity(
                id = tab.id,
                sortOrder = sortOrder,
                title = tab.title,
                url = tab.url,
                lastReadPage = tab.lastReadPage.coerceAtLeast(0),
                chapterTitle = ch?.title,
                chapterUrl = ch?.chapterUrl,
                chapterImages = ch?.images,
                chapterNextUrl = ch?.nextChapterUrl,
                chapterPrevUrl = ch?.prevChapterUrl,
                chapterIsOffline = ch?.isOffline ?: false,
                updatedAt = updatedAt
            )
        }
    }
}
