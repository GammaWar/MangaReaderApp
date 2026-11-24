package com.mangareader.app.util

import java.net.URI

data class UrlValidationResult(
    val valid: Boolean,
    val normalizedUrl: String? = null,
    val reason: String? = null
)

/**
 * Sicherheitsprüfung für externe URLs und Client-Bild-/Link-URLs.
 * Blockiert lokale/private IP-Bereiche (SSRF-Schutz), Hex-IPv4-mapped IPv6,
 * NAT64/6to4/Teredo/Multicast, Zone-IDs, Trailing-Dot-Hosts, Nicht-Standard-Ports
 * sowie unsichere Schemata.
 */
object UrlSafety {

    // Pair(networkUint32, maskUint32) stored as unsigned 32-bit Longs
    private val IPV4_BLOCKED_CIDRS: List<Pair<Long, Long>> = listOf(
        0x00000000L to 0xff000000L, // 0.0.0.0/8       (This network)
        0x0a000000L to 0xff000000L, // 10.0.0.0/8      (RFC 1918 Private)
        0x64400000L to 0xffc00000L, // 100.64.0.0/10   (CGNAT / Shared Address Space)
        0x7f000000L to 0xff000000L, // 127.0.0.0/8     (Loopback)
        0xa9fe0000L to 0xffff0000L, // 169.254.0.0/16  (Link-local / Cloud Metadata)
        0xac100000L to 0xfff00000L, // 172.16.0.0/12   (RFC 1918 Private)
        0xc0000000L to 0xffffff00L, // 192.0.0.0/24    (IETF Protocol Assignments)
        0xc0000200L to 0xffffff00L, // 192.0.2.0/24    (TEST-NET-1)
        0xc0a80000L to 0xffff0000L, // 192.168.0.0/16  (RFC 1918 Private)
        0xc6120000L to 0xfffe0000L, // 198.18.0.0/15   (Benchmarking)
        0xc6336400L to 0xffffff00L, // 198.51.100.0/24 (TEST-NET-2)
        0xcb007100L to 0xffffff00L, // 203.0.113.0/24  (TEST-NET-3)
        0xe0000000L to 0xf0000000L, // 224.0.0.0/4     (Multicast 224.0.0.0 - 239.255.255.255)
        0xf0000000L to 0xf0000000L  // 240.0.0.0/4     (Reserved & Broadcast 240.0.0.0 - 255.255.255.255)
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

    private val ALLOWED_EXTERNAL_PORTS = setOf(-1, 80, 443)

    private val NUMERIC_OR_HEX_IP_REGEX = Regex("""(?i)^(0x[0-9a-f]+|\d+)$""")
    private val SHORTHAND_OR_HEX_DOTTED_IP_REGEX =
        Regex("""(?i)^(0x[0-9a-f]+|\d+)(\.(0x[0-9a-f]+|\d+)){1,3}$""")
    private val DOTTED_IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_HEX_SEGMENT_REGEX = Regex("""(?i)^[0-9a-f]{1,4}$""")

    fun stripIpv6Brackets(hostname: String): String {
        val trimmed = hostname.trim()
        return if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length >= 2) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed.removePrefix("[").removeSuffix("]")
        }
    }

    private fun parseIpv4ToUint32(ip: String): Long? {
        if (!DOTTED_IPV4_REGEX.matches(ip)) return null
        val parts = ip.split(".")
        var result = 0L
        for (part in parts) {
            if (part.length > 1 && part.startsWith("0")) {
                // Octal-like leading zeros are ambiguous -> fail-closed
                return null
            }
            val octet = part.toIntOrNull() ?: return null
            if (octet < 0 || octet > 255) return null
            result = ((result shl 8) or octet.toLong()) and 0xffffffffL
        }
        return result
    }

    private fun isBlockedIpv4Uint32(ip32: Long): Boolean {
        val unsigned = ip32 and 0xffffffffL
        return IPV4_BLOCKED_CIDRS.any { (network, mask) ->
            (unsigned and mask) == (network and 0xffffffffL)
        }
    }

    /**
     * Expands any valid IPv6 literal (including compressed ::, zone IDs, mixed dotted-IPv4 suffixes,
     * and uppercase hex) into exactly eight 16-bit unsigned integers (0..0xffff).
     * Returns null if the string is not a valid IPv6 address.
     */
    private fun parseIpv6ToGroups(rawIp: String): IntArray? {
        // Strip optional zone ID (e.g. fe80::1%eth0 or URL-encoded fe80::1%25eth0)
        val zoneIdx = rawIp.indexOf('%')
        val ip = if (zoneIdx >= 0) rawIp.substring(0, zoneIdx) else rawIp
        if (!ip.contains(':')) return null

        // Handle embedded dotted IPv4 tail (e.g. ::ffff:127.0.0.1 or 64:ff9b::169.254.169.254)
        var work = ip
        val lastColon = work.lastIndexOf(':')
        val tail = work.substring(lastColon + 1)
        if (tail.contains('.')) {
            val v4 = parseIpv4ToUint32(tail) ?: return null
            val hi = ((v4 ushr 16) and 0xffffL).toString(16)
            val lo = (v4 and 0xffffL).toString(16)
            work = "${work.substring(0, lastColon + 1)}$hi:$lo"
        }

        val doubleColonParts = work.split("::")
        if (doubleColonParts.size > 2) return null

        fun parseSegmentList(segmentStr: String): List<Int>? {
            if (segmentStr.isEmpty()) return emptyList()
            val tokens = segmentStr.split(":")
            val out = ArrayList<Int>(tokens.size)
            for (token in tokens) {
                if (!IPV6_HEX_SEGMENT_REGEX.matches(token)) return null
                out.add(token.toInt(16))
            }
            return out
        }

        if (doubleColonParts.size == 1) {
            val groups = parseSegmentList(doubleColonParts[0]) ?: return null
            if (groups.size != 8) return null
            return groups.toIntArray()
        }

        val left = parseSegmentList(doubleColonParts[0]) ?: return null
        val right = parseSegmentList(doubleColonParts[1]) ?: return null
        val missing = 8 - (left.size + right.size)
        if (missing < 1) return null

        val result = IntArray(8)
        var idx = 0
        for (v in left) result[idx++] = v
        idx += missing
        for (v in right) result[idx++] = v
        return result
    }

    private fun isBlockedIpv6Groups(g: IntArray): Boolean {
        val g0 = g[0]
        val g1 = g[1]
        val g2 = g[2]
        val g3 = g[3]
        val g4 = g[4]
        val g5 = g[5]
        val g6 = g[6]
        val g7 = g[7]

        val firstSixZero = g0 == 0 && g1 == 0 && g2 == 0 && g3 == 0 && g4 == 0 && g5 == 0

        // ::/128 (Unspecified) and ::1/128 (Loopback)
        if (firstSixZero && g6 == 0 && (g7 == 0 || g7 == 1)) {
            return true
        }

        // ::ffff:0:0/96 (IPv4-mapped IPv6: dotted ::ffff:127.0.0.1 and hex ::ffff:7f00:1 / ::ffff:a9fe:a9fe)
        if (g0 == 0 && g1 == 0 && g2 == 0 && g3 == 0 && g4 == 0 && g5 == 0xffff) {
            val embeddedV4 = (((g6 and 0xffff).toLong() shl 16) or (g7 and 0xffff).toLong()) and 0xffffffffL
            return isBlockedIpv4Uint32(embeddedV4)
        }

        // ::ffff:0:0:0/96 (SIIT IPv4-translated)
        if (g0 == 0 && g1 == 0 && g2 == 0 && g3 == 0 && g4 == 0xffff && g5 == 0) {
            val embeddedV4 = (((g6 and 0xffff).toLong() shl 16) or (g7 and 0xffff).toLong()) and 0xffffffffL
            return isBlockedIpv4Uint32(embeddedV4)
        }

        // ::/96 (deprecated IPv4-compatible IPv6)
        if (firstSixZero && (g6 != 0 || g7 > 1)) {
            val embeddedV4 = (((g6 and 0xffff).toLong() shl 16) or (g7 and 0xffff).toLong()) and 0xffffffffL
            return isBlockedIpv4Uint32(embeddedV4)
        }

        // 64:ff9b::/96 (NAT64 Well-Known Prefix) & 64:ff9b:1::/48 (NAT64 Local-Use Prefix)
        if (g0 == 0x0064 && g1 == 0xff9b) {
            if (g2 == 0 && g3 == 0 && g4 == 0 && g5 == 0) {
                val embeddedV4 = (((g6 and 0xffff).toLong() shl 16) or (g7 and 0xffff).toLong()) and 0xffffffffL
                return isBlockedIpv4Uint32(embeddedV4)
            }
            if (g2 == 0x0001) {
                return true
            }
        }

        // 2002::/16 (6to4 tunneling)
        if (g0 == 0x2002) return true

        // 2001::/32 (Teredo tunneling)
        if (g0 == 0x2001 && g1 == 0x0000) return true

        // 2001:db8::/32 (Documentation)
        if (g0 == 0x2001 && g1 == 0x0db8) return true

        // 100::/64 (Discard-Only RFC 6666)
        if (g0 == 0x0100 && g1 == 0 && g2 == 0 && g3 == 0) return true

        // fc00::/7 (Unique Local Address: fc00..fdff)
        if ((g0 and 0xfe00) == 0xfc00) return true

        // fe80::/10 (Link-Local: fe80..febf)
        if ((g0 and 0xffc0) == 0xfe80) return true

        // fec0::/10 (Deprecated Site-Local: fec0..feff)
        if ((g0 and 0xffc0) == 0xfec0) return true

        // ff00::/8 (Multicast: ff00..ffff)
        if ((g0 and 0xff00) == 0xff00) return true

        return false
    }

    fun isPrivateOrLoopbackIp(ip: String): Boolean {
        val normalized = stripIpv6Brackets(ip).lowercase()
        if (normalized.isEmpty()) return true

        // IPv6 literals (with optional zone ID)
        if (normalized.contains(':')) {
            val groups = parseIpv6ToGroups(normalized) ?: return true // Fail-closed on malformed IPv6
            return isBlockedIpv6Groups(groups)
        }

        // Block purely numeric, octal, or hex IPv4 representations (e.g. 2130706433, 0x7f000001)
        if (NUMERIC_OR_HEX_IP_REGEX.matches(normalized)) {
            return true
        }

        // Standard dotted IPv4 check (fail-closed on malformed octets or leading zeros)
        if (DOTTED_IPV4_REGEX.matches(normalized)) {
            val v4 = parseIpv4ToUint32(normalized) ?: return true
            return isBlockedIpv4Uint32(v4)
        }

        // Block shorthand or hex dotted IPv4 forms (e.g. 127.1, 127.0.1, 0x7f.0.0.1)
        if (SHORTHAND_OR_HEX_DOTTED_IP_REGEX.matches(normalized)) {
            return true
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
        } catch (_: Exception) {
            return UrlValidationResult(valid = false, reason = "URL konnte nicht geparst werden.")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult(valid = false, reason = "Nur http:// und https:// URLs sind erlaubt.")
        }

        if (!uri.userInfo.isNullOrBlank() || uri.rawAuthority?.contains('@') == true) {
            return UrlValidationResult(
                valid = false,
                reason = "URLs mit eingebetteten Zugangsdaten sind nicht erlaubt."
            )
        }

        if (!ALLOWED_EXTERNAL_PORTS.contains(uri.port)) {
            return UrlValidationResult(
                valid = false,
                reason = "Nur Standard-HTTP/HTTPS-Ports (80/443) sind erlaubt."
            )
        }

        val rawHost = uri.host?.lowercase()?.trim().orEmpty()
        if (rawHost.isEmpty()) {
            return UrlValidationResult(
                valid = false,
                reason = "Ungültiger oder fehlender Hostname."
            )
        }

        // Normalize trailing FQDN dots (blocks localhost., metadata.google.internal., router.local.)
        val strippedHost = stripIpv6Brackets(rawHost)
        val host = if (strippedHost.contains(':')) strippedHost else strippedHost.trimEnd('.')

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
        } catch (_: Exception) {
            false
        }
    }
}
