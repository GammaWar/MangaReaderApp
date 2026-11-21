package com.mangareader.app.data.model

data class Chapter(
    val title: String,
    val chapterUrl: String,
    val images: List<String>,
    val nextChapterUrl: String? = null,
    val prevChapterUrl: String? = null,
    val isOffline: Boolean = false
)

enum class ReaderMode {
    VERTICAL_WEBTOON, // Continuous vertical scrolling (Manhua/Webtoon style)
    HORIZONTAL_PAGED  // Paged horizontal swipe (Classic Manga style)
}

enum class ReadingDirection {
    RTL, // Rechts -> Links (klassisches Manga-Format)
    LTR  // Links -> Rechts
}

enum class AppTab {
    LIBRARY,
    BROWSER,
    READER,
    ASSISTANT
}

enum class PrefetchStatus {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE
}

data class PrefetchState(
    val forChapterUrl: String = "",
    val status: PrefetchStatus = PrefetchStatus.IDLE,
    val chapter: Chapter? = null
)

data class BookmarkItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val coverImageUrl: String? = null,
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isBookmark: Boolean = true
)

data class ReaderPreferences(
    val isHistoryTrackingEnabled: Boolean = true,
    val isAutoScrollEnabled: Boolean = true,
    val autoScrollSpeed: Float = 1.0f,
    val isPrefetchEnabled: Boolean = true,
    val isCustomBrightnessEnabled: Boolean = false,
    val brightnessLevel: Float = 1.0f,
    val isNightFilterEnabled: Boolean = false,
    val nightFilterStrength: Float = 0.35f,
    val readingDirection: ReadingDirection = ReadingDirection.RTL,
    val keepScreenOn: Boolean = true
)

data class DownloadedChapter(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val imageFilePaths: List<String>,
    val downloadTimestamp: Long = System.currentTimeMillis(),
    val totalPages: Int = imageFilePaths.size
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class InProgress(val progress: Float, val current: Int, val total: Int, val title: String) : DownloadState()
    data class Completed(val chapterTitle: String) : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

data class ExtractionResult(
    val title: String,
    val images: List<String>,
    val nextChapterUrl: String? = null
)

data class WebTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "Neuer Tab",
    val url: String = "https://mangadex.org",
    val chapter: Chapter? = null,
    val lastReadPage: Int = 0
)
