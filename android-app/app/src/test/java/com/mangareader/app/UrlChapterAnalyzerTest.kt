package com.mangareader.app

import com.mangareader.app.data.extractor.UrlChapterAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlChapterAnalyzerTest {

    @Test
    fun `analyzeChapterUrl increments and decrements chapter numbers with strict boundaries`() {
        val res = UrlChapterAnalyzer.analyzeChapterUrl("https://reader.example.com/manga/kurogane/chapter-12/")
        assertEquals(12.0, res.chapterNumber!!, 0.001)
        assertTrue(res.hasNextChapter)
        assertEquals("https://reader.example.com/manga/kurogane/chapter-13/", res.nextChapterUrl)
        assertEquals("https://reader.example.com/manga/kurogane/chapter-11/", res.prevChapterUrl)
        assertFalse(res.isUuidPath)
    }

    @Test
    fun `analyzeChapterUrl handles decimal chapters, zero-padding, kapitel prefix, and query params`() {
        val dec = UrlChapterAnalyzer.analyzeChapterUrl("https://example.com/series/ch_12.5")
        assertEquals(12.5, dec.chapterNumber!!, 0.001)
        assertEquals("https://example.com/series/ch_13", dec.nextChapterUrl)
        assertEquals("https://example.com/series/ch_12", dec.prevChapterUrl)

        val padded = UrlChapterAnalyzer.analyzeChapterUrl("https://example.com/manga/chapter-005")
        assertEquals("https://example.com/manga/chapter-006", padded.nextChapterUrl)

        val query = UrlChapterAnalyzer.analyzeChapterUrl("https://example.com/read?chapter=104&lang=de")
        assertEquals(104.0, query.chapterNumber!!, 0.001)
        assertEquals("https://example.com/read?chapter=105&lang=de", query.nextChapterUrl)

        val dePrefix = UrlChapterAnalyzer.analyzeChapterUrl("https://example.com/manga/kapitel-1")
        assertEquals("https://example.com/manga/kapitel-2", dePrefix.nextChapterUrl)
        assertNull(dePrefix.prevChapterUrl)
    }

    @Test
    fun `analyzeChapterUrl protects MangaDex UUID paths and ignores mid-word c123 matches`() {
        val mangadex = UrlChapterAnalyzer.analyzeChapterUrl(
            "https://mangadex.org/chapter/a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        )
        assertTrue(mangadex.isUuidPath)
        assertFalse(mangadex.hasNextChapter)
        assertNull(mangadex.nextChapterUrl)

        val falsePositive = UrlChapterAnalyzer.analyzeChapterUrl("https://example.com/manga/abc123/overview")
        assertNull(falsePositive.chapterNumber)
        assertFalse(falsePositive.hasNextChapter)
    }

    @Test
    fun `buildDownloadFolderId avoids collisions and preserves CJK characters`() {
        val idA = UrlChapterAnalyzer.buildDownloadFolderId("Chapter 5", "https://site-a.example.com/manga-1/chapter-5")
        val idB = UrlChapterAnalyzer.buildDownloadFolderId("Chapter 5", "https://site-b.example.com/manga-2/chapter-5")
        assertNotEquals(idA, idB)

        val jp = UrlChapterAnalyzer.buildDownloadFolderId("黒鉄の刃 第12話", "https://jp.example.com/kurogane/12")
        assertTrue(jp.contains("黒鉄の刃"))
        assertFalse(jp.startsWith("____"))
    }

    @Test
    fun `extractImagesFromHtml prefers lazy attributes and filters noise icons`() {
        val html = """
            <html>
              <head><title>Kurogane Chapter 12 &amp; 13 - MangaSite</title></head>
              <body>
                <img src="/assets/logo.png" />
                <img src="/placeholder.gif" data-src="https://cdn.example.com/ch12/01.webp" />
                <img data-original="/ch12/02.jpg" src="/spinner.svg" />
                <img data-cfsrc="https://cdn.example.com/ch12/03.png" src="/blank.png" />
                <img src="https://cdn.example.com/avatar/user.png" />
              </body>
            </html>
        """.trimIndent()

        val urls = UrlChapterAnalyzer.extractImagesFromHtml(html, "https://manga.example.com/read/ch12")
        assertEquals(
            listOf(
                "https://cdn.example.com/ch12/01.webp",
                "https://manga.example.com/ch12/02.jpg",
                "https://cdn.example.com/ch12/03.png"
            ),
            urls
        )
        assertEquals(
            "Kurogane Chapter 12 & 13 - MangaSite",
            UrlChapterAnalyzer.extractTitleFromHtml(html, "https://manga.example.com/read/ch12")
        )
    }

    @Test
    fun `extractImagesFromHtml supports data-srcset and extractNextChapterUrlFromHtml detects rel next and classes`() {
        val html = """
            <html>
              <body>
                <img src="/img/placeholder.png" />
                <img data-srcset="https://cdn.example.com/p1.webp 1x, https://cdn.example.com/p1_2x.webp 2x" />
                <a class="navi-change-chapter-next" href="/manga/series/chapter-13">Weiterlesen</a>
              </body>
            </html>
        """.trimIndent()

        val images = UrlChapterAnalyzer.extractImagesFromHtml(html, "https://manga.example.com/manga/series/chapter-12")
        assertEquals(listOf("https://cdn.example.com/p1.webp"), images)
        assertEquals(
            "https://manga.example.com/manga/series/chapter-13",
            UrlChapterAnalyzer.extractNextChapterUrlFromHtml(html, "https://manga.example.com/manga/series/chapter-12")
        )
    }
}
