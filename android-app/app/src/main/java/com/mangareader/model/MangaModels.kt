package com.mangareader.model

enum class AppTab(val label: String) {
    LIBRARY("Bibliothek"),
    BROWSER("Browser"),
    READER("Reader"),
}

enum class ReadingMode(val label: String) {
    WEBTOON("Webtoon (Vertikal)"),
    PAGED("Manga (Blättern)"),
}

enum class ImageErrorKind {
    HTTP_404,
    HTML_CHALLENGE,
    DECODE_ERROR,
    HTTP_429,
    HTTP_5XX,
    TIMEOUT,
    UNKNOWN,
}

data class ImageDiagnostic(
    val kind: ImageErrorKind,
    val httpStatus: Int? = null,
    val receivedContentType: String? = null,
    val title: String,
    val description: String,
    val suggestion: String,
    val shouldAutoRetry: Boolean,
    val suggestOpenBrowser: Boolean = false,
    val suggestCacheBypass: Boolean = false,
    val suggestRefreshSession: Boolean = false,
)

data class ChapterPage(
    val index: Int,
    val url: String,
    val label: String = "Seite ${index + 1}",
)

data class ReaderChapter(
    val id: String,
    val title: String,
    val chapterLabel: String,
    val chapterUrl: String,
    val pages: List<ChapterPage>,
    val nextChapterUrl: String? = null,
    val prevChapterUrl: String? = null,
    val initialPage: Int = 0,
)

data class BookmarkItem(
    val id: String,
    val title: String,
    val chapterLabel: String,
    val chapterUrl: String,
    val lastReadPage: Int,
    val totalPages: Int,
    val updatedAtMs: Long,
)

data class HistoryItem(
    val id: String,
    val title: String,
    val chapterLabel: String,
    val chapterUrl: String,
    val lastReadPage: Int,
    val totalPages: Int,
    val readAtMs: Long,
)

data class BrowserTabState(
    val id: String,
    val title: String,
    val url: String,
    val isLoading: Boolean = false,
)

data class ReaderSettings(
    val readingMode: ReadingMode = ReadingMode.WEBTOON,
    val historyEnabled: Boolean = true,
    val dimmerEnabled: Boolean = false,
    val dimmerStrength: Float = 0.25f, // 0.0 - 0.80
    val sepiaEnabled: Boolean = false,
    val sepiaStrength: Float = 0.25f,  // 0.10 - 0.80
    val autoscrollEnabled: Boolean = false,
    val autoscrollSpeed: Float = 1.0f, // 0.5, 1.0, 1.5, 2.0, 3.0
    val autoPrefetchEnabled: Boolean = true,
    val cacheLimitMb: Int = 4096,      // 512 MB – 32768 MB (4 GB default)
)
