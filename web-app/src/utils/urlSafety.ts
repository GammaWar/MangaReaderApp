const IPV4_BLOCKED_CIDRS: ReadonlyArray<readonly [number, number]> = [
  // [network32, mask32]
  [0x00000000, 0xff000000], // 0.0.0.0/8       (This network)
  [0x0a000000, 0xff000000], // 10.0.0.0/8      (RFC 1918 Private)
  [0x64400000, 0xffc00000], // 100.64.0.0/10   (CGNAT / Shared Address Space)
  [0x7f000000, 0xff000000], // 127.0.0.0/8     (Loopback)
  [0xa9fe0000, 0xffff0000], // 169.254.0.0/16  (Link-local / Cloud Metadata)
  [0xac100000, 0xfff00000], // 172.16.0.0/12   (RFC 1918 Private)
  [0xc0000000, 0xffffff00], // 192.0.0.0/24    (IETF Protocol Assignments)
  [0xc0000200, 0xffffff00], // 192.0.2.0/24    (TEST-NET-1)
  [0xc0a80000, 0xffff0000], // 192.168.0.0/16  (RFC 1918 Private)
  [0xc6120000, 0xfffe0000], // 198.18.0.0/15   (Benchmarking)
  [0xc6336400, 0xffffff00], // 198.51.100.0/24 (TEST-NET-2)
  [0xcb007100, 0xffffff00], // 203.0.113.0/24  (TEST-NET-3)
  [0xe0000000, 0xf0000000], // 224.0.0.0/4     (Multicast 224.0.0.0 - 239.255.255.255)
  [0xf0000000, 0xf0000000], // 240.0.0.0/4     (Reserved & Broadcast 240.0.0.0 - 255.255.255.255)
];

const BLOCKED_HOSTNAMES = new Set([
  'localhost',
  'localhost.localdomain',
  'ip6-localhost',
  'ip6-loopback',
  'metadata.google.internal',
  'metadata',
  '0.0.0.0',
  '::1',
  '[::1]',
]);

const ALLOWED_EXTERNAL_PORTS = new Set(['', '80', '443']);

function parseIpv4ToUint32(ip: string): number | null {
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return null;
  const parts = ip.split('.');
  let result = 0;
  for (const part of parts) {
    if (part.length > 1 && part.startsWith('0')) {
      // Octal-like leading zeros are ambiguous -> treat as invalid/blocked by caller
      return null;
    }
    const octet = Number(part);
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) {
      return null;
    }
    result = ((result << 8) | octet) >>> 0;
  }
  return result >>> 0;
}

function isBlockedIpv4Uint32(ip32: number): boolean {
  const unsigned = ip32 >>> 0;
  for (const [network, mask] of IPV4_BLOCKED_CIDRS) {
    if (((unsigned & mask) >>> 0) === (network >>> 0)) {
      return true;
    }
  }
  return false;
}

/**
 * Expands any valid IPv6 literal (including compressed ::, mixed dotted-IPv4 suffixes,
 * and uppercase hex) into exactly eight 16-bit unsigned integers (0..0xffff).
 * Returns null if the string is not a valid IPv6 address.
 */
function parseIpv6ToGroups(rawIp: string): number[] | null {
  // Strip optional zone ID (e.g. fe80::1%eth0)
  const zoneIdx = rawIp.indexOf('%');
  const ip = zoneIdx >= 0 ? rawIp.slice(0, zoneIdx) : rawIp;
  if (!ip.includes(':')) return null;

  // Handle embedded dotted IPv4 tail (e.g. ::ffff:127.0.0.1 or 64:ff9b::169.254.169.254)
  let work = ip;
  const lastColon = work.lastIndexOf(':');
  const tail = work.slice(lastColon + 1);
  if (tail.includes('.')) {
    const v4 = parseIpv4ToUint32(tail);
    if (v4 === null) return null;
    const hi = ((v4 >>> 16) & 0xffff).toString(16);
    const lo = (v4 & 0xffff).toString(16);
    work = `${work.slice(0, lastColon + 1)}${hi}:${lo}`;
  }

  const doubleColonParts = work.split('::');
  if (doubleColonParts.length > 2) return null;

  const parseSegmentList = (segmentStr: string): number[] | null => {
    if (!segmentStr) return [];
    const tokens = segmentStr.split(':');
    const out: number[] = [];
    for (const token of tokens) {
      if (!/^[0-9a-f]{1,4}$/i.test(token)) return null;
      out.push(parseInt(token, 16));
    }
    return out;
  };

  if (doubleColonParts.length === 1) {
    const groups = parseSegmentList(doubleColonParts[0]);
    if (!groups || groups.length !== 8) return null;
    return groups;
  }

  const left = parseSegmentList(doubleColonParts[0]);
  const right = parseSegmentList(doubleColonParts[1]);
  if (!left || !right) return null;

  const missing = 8 - (left.length + right.length);
  if (missing < 1) return null;

  return [...left, ...new Array<number>(missing).fill(0), ...right];
}

function isBlockedIpv6Groups(g: number[]): boolean {
  const [g0, g1, g2, g3, g4, g5, g6, g7] = g;

  // ::/128 (Unspecified) and ::1/128 (Loopback)
  const firstSixZero = g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0;
  if (firstSixZero && g6 === 0 && (g7 === 0 || g7 === 1)) {
    return true;
  }

  // ::ffff:0:0/96 (IPv4-mapped IPv6, covers both ::ffff:127.0.0.1 and canonical ::ffff:7f00:1 / ::ffff:a9fe:a9fe)
  if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0xffff) {
    const embeddedV4 = (((g6 & 0xffff) << 16) | (g7 & 0xffff)) >>> 0;
    return isBlockedIpv4Uint32(embeddedV4);
  }

  // ::ffff:0:0:0/96 (SIIT IPv4-translated)
  if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0xffff && g5 === 0) {
    const embeddedV4 = (((g6 & 0xffff) << 16) | (g7 & 0xffff)) >>> 0;
    return isBlockedIpv4Uint32(embeddedV4);
  }

  // ::/96 (deprecated IPv4-compatible IPv6)
  if (firstSixZero && (g6 !== 0 || g7 > 1)) {
    const embeddedV4 = (((g6 & 0xffff) << 16) | (g7 & 0xffff)) >>> 0;
    return isBlockedIpv4Uint32(embeddedV4);
  }

  // 64:ff9b::/96 (NAT64 Well-Known Prefix) & 64:ff9b:1::/48 (NAT64 Local-Use Prefix)
  if (g0 === 0x0064 && g1 === 0xff9b) {
    if (g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0) {
      const embeddedV4 = (((g6 & 0xffff) << 16) | (g7 & 0xffff)) >>> 0;
      return isBlockedIpv4Uint32(embeddedV4);
    }
    if (g2 === 0x0001) {
      return true;
    }
  }

  // 2002::/16 (6to4 tunneling — embeds IPv4 in g1:g2; block private/reserved or tunneling outright)
  if (g0 === 0x2002) {
    return true;
  }

  // 2001::/32 (Teredo tunneling)
  if (g0 === 0x2001 && g1 === 0x0000) {
    return true;
  }

  // 2001:db8::/32 (Documentation)
  if (g0 === 0x2001 && g1 === 0x0db8) {
    return true;
  }

  // 100::/64 (Discard-Only RFC 6666)
  if (g0 === 0x0100 && g1 === 0 && g2 === 0 && g3 === 0) {
    return true;
  }

  // fc00::/7 (Unique Local Address: fc00..fdff)
  if ((g0 & 0xfe00) === 0xfc00) {
    return true;
  }

  // fe80::/10 (Link-Local: fe80..febf)
  if ((g0 & 0xffc0) === 0xfe80) {
    return true;
  }

  // fec0::/10 (Deprecated Site-Local: fec0..feff)
  if ((g0 & 0xffc0) === 0xfec0) {
    return true;
  }

  // ff00::/8 (Multicast: ff00..ffff)
  if ((g0 & 0xff00) === 0xff00) {
    return true;
  }

  return false;
}

export function stripIpv6Brackets(hostname: string): string {
  return hostname.trim().replace(/^\[|\]$/g, '');
}

export function isPrivateOrLoopbackIp(ip: string): boolean {
  const normalized = stripIpv6Brackets(ip).toLowerCase();
  if (!normalized) return true;

  // IPv6 literals (with optional zone ID)
  if (normalized.includes(':')) {
    const groups = parseIpv6ToGroups(normalized);
    // Fail-closed if an IPv6-like literal cannot be parsed cleanly
    if (!groups) return true;
    return isBlockedIpv6Groups(groups);
  }

  // Block purely numeric, octal, or hex IPv4 representations (e.g. 2130706433, 0x7f000001)
  if (/^(0x[0-9a-f]+|\d+)$/i.test(normalized)) {
    return true;
  }

  // Standard dotted IPv4 check (fail-closed on malformed octets or leading zeros)
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(normalized)) {
    const v4 = parseIpv4ToUint32(normalized);
    if (v4 === null) return true;
    return isBlockedIpv4Uint32(v4);
  }

  return false;
}

export interface UrlValidationResult {
  valid: boolean;
  url?: URL;
  reason?: string;
}

export function validateExternalUrl(rawUrl: string): UrlValidationResult {
  const trimmed = rawUrl.trim();
  if (!trimmed || trimmed.length > 2048) {
    return { valid: false, reason: 'Ungültige oder zu lange URL.' };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { valid: false, reason: 'URL konnte nicht geparst werden.' };
  }

  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return { valid: false, reason: 'Nur http:// und https:// URLs sind erlaubt.' };
  }

  if (parsed.username || parsed.password) {
    return { valid: false, reason: 'URLs mit eingebetteten Zugangsdaten sind nicht erlaubt.' };
  }

  if (!ALLOWED_EXTERNAL_PORTS.has(parsed.port)) {
    return { valid: false, reason: 'Nur Standard-HTTP/HTTPS-Ports (80/443) sind erlaubt.' };
  }

  // Normalize trailing FQDN dots (blocks localhost., metadata.google.internal., router.local.)
  const rawHost = parsed.hostname.toLowerCase();
  const host = stripIpv6Brackets(rawHost).replace(/\.+$/, '');

  if (
    !host ||
    BLOCKED_HOSTNAMES.has(host) ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    host.endsWith('.internal')
  ) {
    return { valid: false, reason: 'Lokale oder interne Hosts sind aus Sicherheitsgründen blockiert.' };
  }

  if (isPrivateOrLoopbackIp(host)) {
    return { valid: false, reason: 'Private oder Loopback-IP-Adressen sind blockiert.' };
  }

  return { valid: true, url: parsed };
}

/**
 * Validates URLs rendered in client <img src> or <a href> attributes.
 * Allows local app assets (/src/assets/, /api/) or validated http(s) URLs.
 */
export function isSafeClientUrl(rawUrl: string): boolean {
  const trimmed = rawUrl.trim();
  if (!trimmed) return false;
  if (trimmed.startsWith('/src/assets/') || trimmed.startsWith('/api/')) {
    return !trimmed.includes('..');
  }
  const lower = trimmed.toLowerCase();
  if (lower.startsWith('javascript:') || lower.startsWith('data:') || lower.startsWith('vbscript:')) {
    return false;
  }
  try {
    const parsed = new URL(trimmed);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}
