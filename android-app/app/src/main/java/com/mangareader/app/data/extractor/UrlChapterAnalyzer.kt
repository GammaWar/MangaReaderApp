package com.mangareader.app.data.extractor

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.math.floor

data class ChapterAnalysis(
    val chapterNumber: Double?,
    val hasNextChapter: Boolean,
    val nextChapterUrl: String?,
    val prevChapterUrl: String?,
    val isUuidPath: Boolean
)

object UrlChapterAnalyzer {

    // Regex to detect UUID in path (e.g. MangaDex /chapter/5f8b91c2-3e4a-4b5c-8d1e-0123456789ab)
    private val UUID_REGEX =
        Regex("""(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

    // Strict boundary chapter patterns to avoid false positives on UUIDs or strings like "abc123"
    private val CHAPTER_PATTERNS = listOf(
        Regex("""(?i)(?<=[/_?&=-]|^)(chapter|kapitel|chap|episode|ep|ch|c)([-_/=])(\d+(?:\.\d+)?)(?=[/_?&#.-]|$)"""),
        Regex("""(?i)(?<=[/_?&=-]|^)(chapter|kapitel|chap|episode|ep|ch)()(\d+(?:\.\d+)?)(?=[/_?&#.-]|$)"""),
        Regex("""(?i)(/)()(0*\d{1,4})(/?(?:#|\?|$))""")
    )

    private val EXCLUDED_IMAGE_PATTERNS = listOf(
        Regex("""(?i)logo"""),
        Regex("""(?i)icon"""),
        Regex("""(?i)favicon"""),
        Regex("""(?i)avatar"""),
        Regex("""(?i)banner"""),
        Regex("""(?i)ads?[-_/]"""),
        Regex("""(?i)spinner"""),
        Regex("""(?i)placeholder"""),
        Regex("""(?i)spacer"""),
        Regex("""(?i)1x1"""),
        Regex("""(?i)blank\.(gif|png|jpg|webp)"""),
        Regex("""(?i)loading\.(gif|svg|png)"""),
        Regex("""(?i)pixel\.(gif|png)"""),
        Regex("""(?i)\.svg(\?|$)"""),
        Regex("""(?i)gravatar\.com"""),
        Regex("""(?i)facebook\.com/tr""")
    )

    fun analyzeChapterUrl(rawUrl: String): ChapterAnalysis {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return ChapterAnalysis(
                chapterNumber = null,
                hasNextChapter = false,
                nextChapterUrl = null,
                prevChapterUrl = null,
                isUuidPath = false
            )
        }

        if (UUID_REGEX.containsMatchIn(trimmed)) {
            return ChapterAnalysis(
                chapterNumber = null,
                hasNextChapter = false,
                nextChapterUrl = null,
                prevChapterUrl = null,
                isUuidPath = true
            )
        }

        val fullUri = runCatching { URI(trimmed) }.getOrNull()
            ?.takeIf { !it.scheme.isNullOrBlank() && !it.host.isNullOrBlank() }
        val targetSegment = if (fullUri != null) {
            val path = fullUri.rawPath.orEmpty()
            val query = fullUri.rawQuery?.let { "?$it" }.orEmpty()
            "$path$query"
        } else {
            trimmed
        }

        for (pattern in CHAPTER_PATTERNS) {
            val matches = pattern.findAll(targetSegment).toList()
            if (matches.isEmpty()) continue

            val match = matches.last()
            val prefix = match.groups[1]?.value ?: continue
            val sep = match.groups[2]?.value ?: ""
            val numString = match.groups[3]?.value ?: continue
            val suffix = if (match.groups.size > 4) match.groups[4]?.value.orEmpty() else ""

            val currentNum = numString.toDoubleOrNull() ?: continue
            if (!currentNum.isFinite() || currentNum < 0.0) continue

            val isDecimal = numString.contains('.')
            val nextNum = if (isDecimal) floor(currentNum) + 1.0 else currentNum + 1.0
            val prevNum = if (isDecimal) floor(currentNum) else currentNum - 1.0

            fun formatNumber(value: Double): String {
                val intVal = value.toLong()
                return if (!isDecimal && numString.startsWith("0") && numString.length > 1) {
                    String.format(Locale.US, "%0${numString.length}d", intVal)
                } else {
                    intVal.toString()
                }
            }

            fun replaceInTarget(newVal: Double): String {
                val replacement = "$prefix$sep${formatNumber(newVal)}$suffix"
                val range = match.range
                val updatedSegment =
                    targetSegment.substring(0, range.first) + replacement + targetSegment.substring(range.last + 1)
                return if (fullUri != null) {
                    val portPart = if (fullUri.port != -1) ":${fullUri.port}" else ""
                    val hashPart = fullUri.rawFragment?.let { "#$it" }.orEmpty()
                    "${fullUri.scheme}://${fullUri.host}$portPart$updatedSegment$hashPart"
                } else {
                    updatedSegment
                }
            }

            return ChapterAnalysis(
                chapterNumber = currentNum,
                hasNextChapter = true,
                nextChapterUrl = replaceInTarget(nextNum),
                prevChapterUrl = if (prevNum >= 1.0) replaceInTarget(prevNum) else null,
                isUuidPath = false
            )
        }

        return ChapterAnalysis(
            chapterNumber = null,
            hasNextChapter = false,
            nextChapterUrl = null,
            prevChapterUrl = null,
            isUuidPath = false
        )
    }

    /**
     * Attempts to calculate the URL for the next chapter based on current URL structure.
     */
    fun predictNextChapterUrl(currentUrl: String): String? {
        return analyzeChapterUrl(currentUrl).nextChapterUrl
    }

    /**
     * Attempts to calculate the URL for the previous chapter based on current URL structure.
     */
    fun predictPrevChapterUrl(currentUrl: String): String? {
        return analyzeChapterUrl(currentUrl).prevChapterUrl
    }

    /**
     * Generates a collision-safe folder/storage ID combining sanitized title + FNV-1a hex hash of URL.
     * Handles Japanese/Korean/Unicode titles cleanly without collapsing into "_____".
     */
    fun buildDownloadFolderId(title: String, chapterUrl: String): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
        val withoutMarks = normalized.replace(Regex("""[\u0300-\u036f]"""), "")
        val sanitized = withoutMarks
            .replace(Regex("""[^a-zA-Z0-9\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]+"""), "_")
            .trim('_')
            .take(40)
            .ifBlank { "chapter" }

        var hash = 0x811c9dc5.toInt()
        val input = "$title|$chapterUrl"
        for (ch in input) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        val hexHash = String.format(Locale.US, "%08x", hash.toLong() and 0xffffffffL)
        return "${sanitized}_$hexHash"
    }

    /**
     * Extracts a human readable chapter title from a URL as fallback.
     */
    fun extractChapterTitleFromUrl(url: String): String {
        return try {
            val cleanUrl = url.substringBefore("#").substringBefore("?")
            val segments = cleanUrl.split("/").filter { it.isNotBlank() }
            if (segments.isNotEmpty()) {
                val last = segments.last()
                val title = last.replace("-", " ").replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                if (title.length < 50) title else "Kapitel"
            } else {
                "Aktuelles Kapitel"
            }
        } catch (e: Exception) {
            "Aktuelles Kapitel"
        }
    }

    /**
     * Extracts manga images directly from HTML string without requiring a WebView.
     * Supports lazy-loading attributes (data-src, data-lazy-src, data-original, data-cfsrc, etc.)
     */
    fun extractImagesFromHtml(html: String, baseUrl: String): List<String> {
        val found = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun resolve(relOrAbs: String): String? {
            return try {
                URI(baseUrl).resolve(relOrAbs.trim()).toString()
            } catch (e: Exception) {
                null
            }
        }

        fun isValidImageCandidate(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase()
            if (lower.startsWith("data:") || lower.startsWith("javascript:")) return false
            return !EXCLUDED_IMAGE_PATTERNS.any { it.containsMatchIn(lower) }
        }

        val imgTagRegex = Regex("""(?i)<img\b[^>]*>""")
        val attrPriority = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-url",
            "data-full-url",
            "data-echo",
            "data-cfsrc",
            "src"
        )

        for (match in imgTagRegex.findAll(html)) {
            val tag = match.value
            var rawSrc: String? = null

            for (attr in attrPriority) {
                val attrRegex = Regex("""(?i)\b${Regex.escape(attr)}\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""")
                val m = attrRegex.find(tag) ?: continue
                val candidate = (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).trim()
                if (isValidImageCandidate(candidate)) {
                    rawSrc = candidate
                    break
                }
            }

            if (rawSrc == null) {
                val srcsetAttrs = listOf("data-srcset", "srcset")
                for (attr in srcsetAttrs) {
                    val attrRegex = Regex("""(?i)\b${Regex.escape(attr)}\s*=\s*(?:"([^"]+)"|'([^']+)')""")
                    val m = attrRegex.find(tag) ?: continue
                    val rawSet = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
                    val firstCandidate = rawSet.substringBefore(",").trim().substringBefore(" ").trim()
                    if (isValidImageCandidate(firstCandidate)) {
                        rawSrc = firstCandidate
                        break
                    }
                }
            }

            if (rawSrc == null) continue
            val resolved = resolve(rawSrc) ?: continue
            val lowerResolved = resolved.lowercase()
            if (!lowerResolved.startsWith("http://") && !lowerResolved.startsWith("https://")) continue
            if (EXCLUDED_IMAGE_PATTERNS.any { it.containsMatchIn(lowerResolved) }) continue
            if (seen.add(resolved)) {
                found.add(resolved)
            }
        }
        return found
    }

    /**
     * Scans HTML for 'Next Chapter' navigation links (rel="next", next-chapter classes, or link text).
     */
    fun extractNextChapterUrlFromHtml(html: String, baseUrl: String): String? {
        fun resolveSafeHref(href: String): String? {
            val trimmed = href.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.lowercase().startsWith("javascript:")) {
                return null
            }
            val resolved = runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrNull() ?: return null
            if (resolved == baseUrl) return null
            val lower = resolved.lowercase()
            return if (lower.startsWith("http://") || lower.startsWith("https://")) resolved else null
        }

        val aTagFullRegex = Regex("""(?i)<a\b([^>]*)>([\s\S]*?)</a>""")
        val hrefRegex = Regex("""(?i)\bhref\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""")
        val relNextRegex = Regex("""(?i)\brel\s*=\s*(?:"[^"]*\bnext\b[^"]*"|'[^']*\bnext\b[^']*'|next\b)""")
        val classNextRegex = Regex(
            """(?i)\bclass\s*=\s*(?:"[^"]*\b(next-chapter|next_chapter|btn-next|ch-next|nav-next|navi-change-chapter-next)\b[^"]*"|'[^']*\b(next-chapter|next_chapter|btn-next|ch-next|nav-next|navi-change-chapter-next)\b[^']*')"""
        )
        val nextKeywords = listOf("next chapter", "nächstes kapitel", "next", "nächstes", "weiter", "suivant", "»")

        var textMatchFallback: String? = null

        for (match in aTagFullRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            val hrefMatch = hrefRegex.find(attrs) ?: continue
            val href = hrefMatch.groupValues[1].ifEmpty { hrefMatch.groupValues[2].ifEmpty { hrefMatch.groupValues[3] } }
            val resolved = resolveSafeHref(href) ?: continue

            if (relNextRegex.containsMatchIn(attrs) || classNextRegex.containsMatchIn(attrs)) {
                return resolved
            }

            if (textMatchFallback == null) {
                val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim().lowercase()
                if (nextKeywords.any { text == it || text.contains(it) }) {
                    textMatchFallback = resolved
                }
            }
        }
        return textMatchFallback
    }

    /**
     * Extracts title from <title> tag in HTML, decoding common entities.
     */
    fun extractTitleFromHtml(html: String, fallbackUrl: String = ""): String {
        val titleRegex = Regex("""(?i)<title[^>]*>([\s\S]*?)</title>""")
        val raw = titleRegex.find(html)?.groupValues?.get(1)
        if (!raw.isNullOrBlank()) {
            val cleaned = raw
                .replace(Regex("""\s+"""), " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
            if (cleaned.isNotEmpty()) return cleaned.take(120)
        }
        if (fallbackUrl.isNotBlank()) {
            return runCatching {
                val u = URI(fallbackUrl)
                "${u.host.orEmpty()}${u.path.orEmpty()}".take(120)
            }.getOrDefault("Extrahiertes Kapitel")
        }
        return ""
    }
}
