package com.mangareader.data

import com.mangareader.model.ChapterPage
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class ChapterUrlAnalysis(
    val chapterNumber: String?,
    val nextChapterUrl: String?,
    val prevChapterUrl: String?,
    val isUuidProtected: Boolean,
)

object UrlChapterAnalyzer {
    private val uuidRegex = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE
    )
    private val chapterTokenRegex = Regex(
        "(?<=[/_?&=-]|^)(chapter|chap|ch|episode|ep|kapitel|c)([/_=-]?)(\\d+(?:\\.\\d+)?)(?=[/_?&#-]|$)",
        RegexOption.IGNORE_CASE
    )
    private val ignoredImageKeywords = listOf(
        "logo", "icon", "favicon", "avatar", "banner", "ads", "doubleclick",
        "analytics", "pixel", "spinner", "loader", "placeholder", "discord", "patreon"
    )

    fun isSafeExternalUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") {
            return false
        }
        // Block private/link-local numeric IP literals
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return false
        }
        val ipCheck = runCatching {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress
        }.getOrDefault(false)
        return !ipCheck
    }

    fun analyzeChapterUrl(url: String): ChapterUrlAnalysis {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("mangadex.org") || uuidRegex.containsMatchIn(url)) {
            return ChapterUrlAnalysis(
                chapterNumber = null,
                nextChapterUrl = null,
                prevChapterUrl = null,
                isUuidProtected = true,
            )
        }

        val match = chapterTokenRegex.findAll(url).lastOrNull() ?: return ChapterUrlAnalysis(
            chapterNumber = null,
            nextChapterUrl = null,
            prevChapterUrl = null,
            isUuidProtected = false,
        )

        val prefix = match.groupValues[1]
        val separator = match.groupValues[2]
        val numStr = match.groupValues[3]
        val numVal = numStr.toDoubleOrNull() ?: return ChapterUrlAnalysis(null, null, null, false)

        val isWhole = !numStr.contains(".")
        val nextNum = if (isWhole) {
            (numVal.toInt() + 1).toString().padStart(numStr.length, '0')
        } else {
            (numVal.toInt() + 1).toString()
        }
        val prevNum = if (numVal > 1.0) {
            (numVal.toInt() - 1).coerceAtLeast(1).toString().padStart(if (isWhole) numStr.length else 1, '0')
        } else {
            null
        }

        val nextUrl = url.replaceRange(match.range, "$prefix$separator$nextNum")
        val prevUrl = prevNum?.let { url.replaceRange(match.range, "$prefix$separator$it") }

        return ChapterUrlAnalysis(
            chapterNumber = numStr,
            nextChapterUrl = nextUrl,
            prevChapterUrl = prevUrl,
            isUuidProtected = false,
        )
    }

    fun extractTitleAndPagesFromHtml(html: String, baseUrl: String): Pair<String, List<ChapterPage>> {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim().ifBlank {
            runCatching { URI(baseUrl).host ?: "Kapitel" }.getOrDefault("Kapitel")
        }

        val seen = linkedSetOf<String>()
        val pages = mutableListOf<ChapterPage>()

        for (img in doc.select("img")) {
            val rawCandidate = listOf(
                img.attr("abs:data-src"),
                img.attr("abs:data-original"),
                img.attr("abs:data-lazy-src"),
                img.attr("abs:src"),
            ).firstOrNull { it.startsWith("https://") || it.startsWith("http://") } ?: continue

            val lower = rawCandidate.lowercase(Locale.ROOT)
            if (lower.endsWith(".svg") || lower.endsWith(".ico")) continue
            if (ignoredImageKeywords.any { lower.contains(it) }) continue

            val widthAttr = img.attr("width").toIntOrNull()
            val heightAttr = img.attr("height").toIntOrNull()
            if ((widthAttr != null && widthAttr in 1..99) || (heightAttr != null && heightAttr in 1..99)) {
                continue
            }

            if (seen.add(rawCandidate)) {
                pages.add(
                    ChapterPage(
                        index = pages.size,
                        url = rawCandidate,
                        label = "Seite ${pages.size + 1}",
                    )
                )
            }
        }

        return title to pages
    }
}
