package com.mangareader.app.util

import java.net.URI

object AppConstants {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
        "co.uk",
        "org.uk",
        "ac.uk",
        "gov.uk",
        "com.br",
        "com.au",
        "net.au",
        "org.au",
        "com.cn",
        "co.jp",
        "ne.jp",
        "or.jp",
        "co.kr",
        "or.kr",
        "com.tw",
        "com.mx",
        "co.nz",
        "co.za",
        "pages.dev",
        "workers.dev",
        "github.io",
        "vercel.app",
        "netlify.app",
        "cloudfront.net",
        "amazonaws.com"
    )

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
        } catch (_: Exception) {
            pageUrl
        }
    }

    internal fun getRegistrableSite(hostname: String): String {
        val parts = hostname.lowercase().trimEnd('.').split(".").filter { it.isNotBlank() }
        if (parts.size <= 2) return parts.joinToString(".")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (MULTI_LABEL_PUBLIC_SUFFIXES.contains(lastTwo) && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
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
        } catch (_: Exception) {
            null
        } ?: return "cross-site"

        val imgClean = imageHost.lowercase().trimEnd('.').removePrefix("www.")
        val refClean = refHost.lowercase().trimEnd('.').removePrefix("www.")

        return when {
            imgClean == refClean -> "same-origin"
            getRegistrableSite(imgClean) == getRegistrableSite(refClean) -> "same-site"
            else -> "cross-site"
        }
    }

    /**
     * Full URI comparison variant for Sec-Fetch-Site (matching scheme, host, and port),
     * respecting multi-label public suffixes such as `co.uk`, `co.jp`, `pages.dev`, `cloudfront.net`.
     */
    fun computeSecFetchSite(imageUrl: String, refererUrl: String?): String {
        if (refererUrl.isNullOrBlank()) return "none"
        return runCatching {
            val img = URI(imageUrl)
            val ref = URI(refererUrl)
            val imgHost = img.host?.lowercase()?.trimEnd('.').orEmpty()
            val refHost = ref.host?.lowercase()?.trimEnd('.').orEmpty()
            val imgPort = effectivePort(img.scheme, img.port)
            val refPort = effectivePort(ref.scheme, ref.port)
            when {
                img.scheme.equals(ref.scheme, ignoreCase = true) &&
                    imgHost == refHost &&
                    imgPort == refPort &&
                    imgHost.isNotEmpty() -> "same-origin"
                imgHost.isNotEmpty() &&
                    refHost.isNotEmpty() &&
                    getRegistrableSite(imgHost) == getRegistrableSite(refHost) -> "same-site"
                else -> "cross-site"
            }
        }.getOrDefault("cross-site")
    }

    private fun effectivePort(scheme: String?, port: Int): Int {
        if (port != -1) return port
        return when (scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }
}
