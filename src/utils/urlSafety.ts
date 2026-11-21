const PRIVATE_IPV4_PATTERNS = [
  /^0\./,
  /^10\./,
  /^127\./,
  /^169\.254\./,
  /^172\.(1[6-9]|2\d|3[0-1])\./,
  /^192\.168\./,
  /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./,
  /^224\./,
  /^240\./,
  /^255\./,
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

export function isPrivateOrLoopbackIp(ip: string): boolean {
  const normalized = ip.trim().toLowerCase().replace(/^\[|\]$/g, '');
  if (!normalized) return true;

  // Handle IPv4-mapped IPv6 addresses like ::ffff:127.0.0.1
  if (normalized.startsWith('::ffff:')) {
    return isPrivateOrLoopbackIp(normalized.slice(7));
  }

  // IPv6 loopback / unspecified / unique local (fc00::/7) / link-local (fe80::/10)
  if (normalized.includes(':')) {
    if (
      normalized === '::1' ||
      normalized === '::' ||
      normalized === '0:0:0:0:0:0:0:1' ||
      normalized === '0:0:0:0:0:0:0:0'
    ) {
      return true;
    }
    if (/^(fc|fd)[0-9a-f]{2}:/i.test(normalized)) return true;
    if (/^fe[89ab][0-9a-f]:/i.test(normalized)) return true;
    return false;
  }

  // Block purely numeric or hex IPv4 representations (e.g. 2130706433, 0x7f000001)
  if (/^(0x[0-9a-f]+|\d+)$/i.test(normalized)) {
    return true;
  }

  // Standard dotted IPv4 check
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(normalized)) {
    const octets = normalized.split('.').map(Number);
    if (octets.some((o) => Number.isNaN(o) || o < 0 || o > 255)) {
      return true;
    }
    return PRIVATE_IPV4_PATTERNS.some((re) => re.test(normalized));
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

  const host = parsed.hostname.toLowerCase();
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
