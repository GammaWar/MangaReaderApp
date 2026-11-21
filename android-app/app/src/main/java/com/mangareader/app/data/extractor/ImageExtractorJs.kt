package com.mangareader.app.data.extractor

import android.webkit.JavascriptInterface
import com.mangareader.app.data.model.ExtractionResult
import org.json.JSONArray
import org.json.JSONObject

class ImageExtractorJs(
    private val onResult: (ExtractionResult) -> Unit
) {

    @JavascriptInterface
    fun postExtractionResult(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val title = json.optString("title", "Unbekanntes Kapitel")
            val nextUrl = json.optString("nextChapterUrl").takeIf { it.isNotBlank() && it != "null" }

            val imagesArray = json.optJSONArray("images") ?: JSONArray()
            val imageUrls = mutableListOf<String>()
            for (i in 0 until imagesArray.length()) {
                val url = imagesArray.getString(i)
                if (url.isNotBlank()) {
                    imageUrls.add(url)
                }
            }

            val result = ExtractionResult(
                title = title,
                images = imageUrls,
                nextChapterUrl = nextUrl
            )
            onResult(result)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val JS_INTERFACE_NAME = "MangaBridge"

        /**
         * Comprehensive JavaScript snippet injected into the WebView.
         * Scans for chapter images with smart lazy-load attribute prioritization
         * and searches for 'Next Chapter' navigation elements.
         */
        val EXTRACTION_SCRIPT = """
            (function() {
                try {
                    const validExtensions = ['.jpg', '.jpeg', '.png', '.webp', '.avif'];
                    const excludedNoise = [
                        'avatar', 'logo', 'banner', 'favicon', 'badge',
                        'spinner', 'pixel.gif', 'pixel.png', 'gravatar.com',
                        'facebook.com/tr', 'ads/', 'ad-'
                    ];

                    function isValidImageUrl(url) {
                        if (!url || typeof url !== 'string') return false;
                        const lower = url.toLowerCase().trim();
                        if (lower.startsWith('data:image/svg') || lower.startsWith('data:image/gif')) return false;
                        if (lower.includes('.svg?') || lower.endsWith('.svg')) return false;
                        if (excludedNoise.some(token => lower.includes(token))) {
                            return false;
                        }
                        return validExtensions.some(ext => lower.includes(ext)) || lower.startsWith('http');
                    }

                    function isPlaceholder(url) {
                        if (!url || typeof url !== 'string') return true;
                        const lower = url.toLowerCase();
                        return lower.includes('placeholder') ||
                               lower.includes('loading') ||
                               lower.includes('blank') ||
                               lower.includes('spacer') ||
                               lower.includes('1x1') ||
                               lower.includes('thumb_loading') ||
                               lower.includes('lazy_placeholder') ||
                               lower.startsWith('data:');
                    }

                    function resolveUrl(url) {
                        try {
                            return new URL(url, window.location.href).href;
                        } catch(e) {
                            return url;
                        }
                    }

                    const foundUrls = [];
                    const seen = new Set();

                    function addUrl(rawUrl) {
                        if (!rawUrl) return;
                        const absolute = resolveUrl(rawUrl.trim());
                        if (isValidImageUrl(absolute) && !seen.has(absolute)) {
                            seen.add(absolute);
                            foundUrls.push(absolute);
                        }
                    }

                    function getBestImageSource(img) {
                        // Priority order: data attributes (real manga source) BEFORE fallback/placeholder src!
                        const attributes = [
                            'data-src',
                            'data-lazy-src',
                            'data-original',
                            'data-url',
                            'data-full-url',
                            'data-echo',
                            'data-cfsrc',
                            'src'
                        ];

                        for (let attr of attributes) {
                            const val = img.getAttribute(attr);
                            if (val && !isPlaceholder(val) && isValidImageUrl(val)) {
                                return val;
                            }
                        }

                        // Check srcset or data-srcset
                        const srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset');
                        if (srcset) {
                            const candidate = srcset.split(',')[0].trim().split(' ')[0];
                            if (candidate && !isPlaceholder(candidate)) {
                                return candidate;
                            }
                        }

                        // Last resort fallback
                        const fallbackSrc = img.getAttribute('src');
                        if (fallbackSrc && !isPlaceholder(fallbackSrc)) {
                            return fallbackSrc;
                        }
                        return null;
                    }

                    // 1. Scan known manga reader containers first (highest priority)
                    const readerSelectors = [
                        '.container-chapter-reader img',
                        '#vungdoc img',
                        '.reading-content img',
                        '.reader-content img',
                        '#chapter-images img',
                        '#readerArea img',
                        '.page-break img',
                        '.chapter-image img',
                        'div.separator img',
                        'div.entry-content img',
                        'div#viewer img'
                    ];

                    let readerImgs = [];
                    for (let sel of readerSelectors) {
                        const matches = document.querySelectorAll(sel);
                        if (matches && matches.length > 0) {
                            readerImgs = Array.from(matches);
                            break;
                        }
                    }

                    const targetElements = readerImgs.length > 0 ? readerImgs : Array.from(document.querySelectorAll('img'));

                    targetElements.forEach(img => {
                        const best = getBestImageSource(img);
                        if (best) {
                            addUrl(best);
                        }
                    });

                    // 2. Identify 'Next Chapter' Link
                    let nextChapterUrl = null;
                    const nextSelectors = [
                        'a[rel="next"]',
                        'a[aria-label*="next" i]',
                        'a.next',
                        'a.next-chapter',
                        'a.next_chapter',
                        'a.btn-next',
                        'a.ch-next',
                        'a.nav-next',
                        'a.navi-change-chapter-next',
                        'a[id*="next" i]'
                    ];

                    for (let sel of nextSelectors) {
                        const el = document.querySelector(sel);
                        if (el && el.href && el.href !== window.location.href) {
                            nextChapterUrl = resolveUrl(el.href);
                            break;
                        }
                    }

                    if (!nextChapterUrl) {
                        const allLinks = Array.from(document.querySelectorAll('a'));
                        const nextKeywords = ['next chapter', 'nächstes kapitel', 'next', 'folgende', 'chapitre suivant', 'siguiente', '»', 'chap >'];
                        for (let a of allLinks) {
                            const text = (a.innerText || a.textContent || '').trim().toLowerCase();
                            if (nextKeywords.some(kw => text === kw || text.includes(kw))) {
                                if (a.href && !a.href.startsWith('javascript:') && a.href !== window.location.href) {
                                    nextChapterUrl = resolveUrl(a.href);
                                    break;
                                }
                            }
                        }
                    }

                    // 3. Extract title
                    let pageTitle = document.title || 'Manga Kapitel';
                    const h1 = document.querySelector('h1, .entry-title, .chapter-title, .title');
                    if (h1 && h1.innerText) {
                        pageTitle = h1.innerText.trim();
                    }

                    const result = {
                        title: pageTitle,
                        images: foundUrls,
                        nextChapterUrl: nextChapterUrl
                    };

                    return JSON.stringify(result);
                } catch(e) {
                    return JSON.stringify({ error: e.toString(), images: [] });
                }
            })();
        """.trimIndent()

        fun parseExtractionJson(rawJson: String): ExtractionResult {
            val cleanJson = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                try {
                    JSONObject("{ \"v\": $rawJson }").getString("v")
                } catch (e: Exception) {
                    rawJson
                }
            } else {
                rawJson
            }

            val json = JSONObject(cleanJson)
            val title = json.optString("title", "Manga Kapitel")
            val nextUrl = json.optString("nextChapterUrl").takeIf { it.isNotBlank() && it != "null" }
            val imagesArray = json.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<String>()
            for (i in 0 until imagesArray.length()) {
                val u = imagesArray.getString(i)
                if (u.isNotBlank()) {
                    images.add(u)
                }
            }
            return ExtractionResult(title, images, nextUrl)
        }
    }
}
