package com.mangareader.app.util

import java.net.URI

data class UrlValidationResult(
    val valid: Boolean,
    val normalizedUrl: String? = null,
    val reason: String? = null
)

/**
 * Sicherheitsprüfung für externe URLs und Client-Bild-/Link-URLs.
 * Blockiert lokale/private IP-Bereiche (SSRF-Schutz), Metadaten-Endpunkte sowie unsichere Schemata.
 */
object UrlSafety {

    private val PRIVATE_IPV4_PATTERNS = listOf(
        Regex("""^0\."""),
        Regex("""^10\."""),
        Regex("""^127\."""),
        Regex("""^169\.254\."""),
        Regex("""^172\.(1[6-9]|2\d|3[0-1])\."""),
        Regex("""^192\.168\."""),
        Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\."""),
        Regex("""^224\."""),
        Regex("""^240\."""),
        Regex("""^255\.""")
    )

    private val BLOCKED_HOSTNAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "metadata.google.internal",
        "metadata",
        "0.0.0.0",
        "::1",
        "[::1]"
    )

    private val NUMERIC_OR_HEX_IP_REGEX = Regex("""(?i)^(0x[0-9a-f]+|\d+)$""")
    private val DOTTED_IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_ULA_REGEX = Regex("""(?i)^(fc|fd)[0-9a-f]{2}:""")
    private val IPV6_LINK_LOCAL_REGEX = Regex("""(?i)^fe[89ab][0-9a-f]:""")

    fun isPrivateOrLoopbackIp(ip: String): Boolean {
        val normalized = ip.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (normalized.isEmpty()) return true

        // IPv4-mapped IPv6 addresses like ::ffff:127.0.0.1
        if (normalized.startsWith("::ffff:")) {
            return isPrivateOrLoopbackIp(normalized.removePrefix("::ffff:"))
        }

        // IPv6 loopback / unspecified / unique local (fc00::/7) / link-local (fe80::/10)
        if (normalized.contains(':')) {
            if (
                normalized == "::1" ||
                normalized == "::" ||
                normalized == "0:0:0:0:0:0:0:1" ||
                normalized == "0:0:0:0:0:0:0:0"
            ) {
                return true
            }
            if (IPV6_ULA_REGEX.containsMatchIn(normalized)) return true
            if (IPV6_LINK_LOCAL_REGEX.containsMatchIn(normalized)) return true
            return false
        }

        // Purely numeric or hex IPv4 representations (e.g. 2130706433, 0x7f000001)
        if (NUMERIC_OR_HEX_IP_REGEX.matches(normalized)) {
            return true
        }

        // Standard dotted IPv4
        if (DOTTED_IPV4_REGEX.matches(normalized)) {
            val octets = normalized.split(".").map { it.toIntOrNull() }
            if (octets.any { it == null || it < 0 || it > 255 }) {
                return true
            }
            return PRIVATE_IPV4_PATTERNS.any { it.containsMatchIn(normalized) }
        }

        return false
    }

    fun validateExternalUrl(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) {
            return UrlValidationResult(valid = false, reason = "Ungültige oder zu lange URL.")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlValidationResult(valid = false, reason = "URL konnte nicht geparst werden.")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult(valid = false, reason = "Nur http:// und https:// URLs sind erlaubt.")
        }

        if (!uri.userInfo.isNullOrBlank()) {
            return UrlValidationResult(
                valid = false,
                reason = "URLs mit eingebetteten Zugangsdaten sind nicht erlaubt."
            )
        }

        val host = uri.host?.lowercase()?.trim().orEmpty()
        if (
            host.isEmpty() ||
            BLOCKED_HOSTNAMES.contains(host) ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal")
        ) {
            return UrlValidationResult(
                valid = false,
                reason = "Lokale oder interne Hosts sind aus Sicherheitsgründen blockiert."
            )
        }

        if (isPrivateOrLoopbackIp(host)) {
            return UrlValidationResult(
                valid = false,
                reason = "Private oder Loopback-IP-Adressen sind blockiert."
            )
        }

        return UrlValidationResult(valid = true, normalizedUrl = uri.toString())
    }

    fun isSafeClientUrl(rawUrl: String, allowLocalFile: Boolean = false): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false

        val lower = trimmed.lowercase()
        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("vbscript:")
        ) {
            return false
        }

        if (allowLocalFile && (trimmed.startsWith("/") || lower.startsWith("file://"))) {
            return !trimmed.contains("..")
        }

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }
}
