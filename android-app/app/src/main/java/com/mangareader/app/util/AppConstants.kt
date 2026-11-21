package com.mangareader.app.util

import java.net.URI

object AppConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Extracts the origin (scheme + host [+ port]) from a given URL to use as fallback Referer.
     * e.g. "https://www.natomanga.com/manga/title/ch-1" -> "https://www.natomanga.com/"
     */
    fun getRefererForUrl(pageUrl: String): String {
        return try {
            val uri = URI(pageUrl)
            val scheme = uri.scheme ?: return pageUrl
            val host = uri.host ?: return pageUrl
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$portPart/"
        } catch (e: Exception) {
            pageUrl
        }
    }

    /**
     * Calculates the appropriate Sec-Fetch-Site header value ('none', 'same-origin', 'same-site', or 'cross-site')
     * to avoid triggering strict CDN anti-hotlinking and bot protection rules.
     */
    fun calculateSecFetchSite(imageHost: String, refererUrl: String?): String {
        if (refererUrl.isNullOrBlank()) return "none"
        val refHost = try {
            URI(refererUrl).host
        } catch (e: Exception) {
            null
        } ?: return "cross-site"

        val imgClean = imageHost.lowercase().removePrefix("www.")
        val refClean = refHost.lowercase().removePrefix("www.")

        return when {
            imgClean == refClean -> "same-origin"
            imgClean.endsWith(".$refClean") || refClean.endsWith(".$imgClean") -> "same-site"
            else -> {
                val imgRoot = imgClean.split(".").takeLast(2).joinToString(".")
                val refRoot = refClean.split(".").takeLast(2).joinToString(".")
                if (imgRoot.isNotBlank() && imgRoot == refRoot) "same-site" else "cross-site"
            }
        }
    }

    /**
     * Full URI comparison variant for Sec-Fetch-Site (matching scheme, host, and port).
     */
    fun computeSecFetchSite(imageUrl: String, refererUrl: String?): String {
        if (refererUrl.isNullOrBlank()) return "none"
        return runCatching {
            val img = URI(imageUrl)
            val ref = URI(refererUrl)
            val imgHost = img.host?.lowercase().orEmpty()
            val refHost = ref.host?.lowercase().orEmpty()
            when {
                img.scheme.equals(ref.scheme, ignoreCase = true) &&
                    imgHost == refHost &&
                    img.port == ref.port &&
                    imgHost.isNotEmpty() -> "same-origin"
                imgHost.isNotEmpty() &&
                    imgHost.split(".").takeLast(2) == refHost.split(".").takeLast(2) -> "same-site"
                else -> "cross-site"
            }
        }.getOrDefault("cross-site")
    }
}
